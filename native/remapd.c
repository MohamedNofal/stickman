/*
 * remapd - Fire TV remote button remapper (Stickman). Runs as the shell user (uid 2000),
 * started by the Stickman app through its self-ADB bridge.
 *
 * shell is in groups `input` (read /dev/input/eventN) and `net_bt_admin` (rw /dev/uinput), so:
 *   1. EVIOCGRAB the real "Amazon Fire TV Remote" -> Android stops seeing it directly
 *   2. create a uinput clone with the same vendor/product (same .kl keylayout applies)
 *   3. forward every event to the clone; remapped keys go through the tap/double/hold
 *      state machine in layers.c and run their rule's action instead.
 * Unmapped keys pass straight through with no added delay.
 * Safety: if remapd dies the kernel releases the grab; holding BACK for 10 s exits remapd.
 *
 * Usage: remapd [conf] [device-name] [pid-file]  (device-name defaults to "Amazon Fire TV Remote"; tools/devtest.sh
 * passes "Stickman Test Remote" to drive remapd from native/fakeremote instead of the real remote, and its own
 * pid file so a test remapd is never mistaken for the production one).
 *
 * Config: /data/local/tmp/stickman/remap.conf (v2: "set", "profile <name>", "<code> <layer> <action>",
 * v1 "<code> <action>" = tap). Active profile: /data/local/tmp/stickman/profile (missing -> default).
 * Both are re-read when their mtime/size change (inotify on the directory makes that immediate). Actions:
 *   start <pkg/activity> | key <code> | none | orig | act <id> [arg] (-> act/ spool) | sh <command...>
 * Listen: while /data/local/tmp/stickman/listen exists (and is younger than LISTEN_MAX_S, so a crashed app can
 * never leave the remote dead) every button is swallowed and only logged ("KEY <code>"): the app's Listen mode
 * reads the log, and nothing else happens (no app opens, no original key). Back held 10 s still exits.
 */
#define _GNU_SOURCE
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <linux/input.h>
#include <linux/uinput.h>
#include <linux/uhid.h>
#include <poll.h>
#include <signal.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <ctype.h>
#include <sys/inotify.h>
#include <sys/ioctl.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/un.h>
#include <sys/wait.h>
#include <time.h>
#include <unistd.h>
#include "layers.h"
#include "amcheck.h"

#define REMOTE_NAME "Amazon Fire TV Remote"   /* default device to grab; also the clone's name */
#define VIRT_PHYS "remapd/virtual"
#define MAXKEY LYR_MAXKEY
#define PANIC_KEY KEY_BACK
#define PANIC_MS 10000
#ifndef DIR_PATH
#define DIR_PATH "/data/local/tmp/stickman/"
#endif
#define DEF_CONF DIR_PATH "remap.conf"
#define PROFILE_PATH DIR_PATH "profile"
#define SPOOL_DIR DIR_PATH "act"
#define PID_PATH DIR_PATH "remapd.pid"
#define LISTEN_PATH DIR_PATH "listen"
#define LISTEN_MAX_S 30
#define NOUT (2 * MAXKEY + 8)

/* parsed remap.conf: profiles[0] is "default"; rules are last-write-wins per (profile, code, layer) */
struct crule { int prof, code, layer; char action[256]; };
static struct crule *crules; static int ncrules, capcrules;
static char (*profs)[128]; static int nprofs, capprofs;
static int cfg_double_ms = 250, cfg_hold_ms = 600;
static int eff[MAXKEY][3];          /* effective rule index per code/layer, -1 = none (implicit orig) */
static int nmapped;
static char active_prof[128] = "default";

static const char *conf_path;
static const char *grab_name = REMOTE_NAME;   /* argv[2]: test mode, e.g. "Stickman Test Remote" */
static const char *pid_path = PID_PATH;       /* argv[3]: test mode pid file */
static long long conf_key = -2, prof_key = -2;   /* mtime ns + size; -1 = missing, -2 = never read */
static int listening;                            /* listen file present and fresh: swallow every button */
static int dino = -1;                            /* inotify on DIR_PATH: conf / profile / listen changes */
static lyr_state *L;
static lyr_out outs[NOUT];
static int rfd = -1, ufd = -1, grabbed;
static long long back_down_at;
static long long key_t0[MAXKEY];
static int ino = -1;
static int logfd = -1;

static long long now_ms(void) {
    struct timespec ts; clock_gettime(CLOCK_MONOTONIC, &ts);
    return (long long)ts.tv_sec * 1000 + ts.tv_nsec / 1000000;
}

/* ---- write straight to logd (/dev/socket/logdw), tag RemapD ---- */
static void alog(const char *fmt, ...) {
    char msg[300]; va_list ap; va_start(ap, fmt); vsnprintf(msg, sizeof msg, fmt, ap); va_end(ap);
    if (logfd < 0) {
        logfd = socket(AF_UNIX, SOCK_DGRAM | SOCK_CLOEXEC | SOCK_NONBLOCK, 0);
        struct sockaddr_un a = { .sun_family = AF_UNIX };
        strcpy(a.sun_path, "/dev/socket/logdw");
        if (logfd >= 0 && connect(logfd, (struct sockaddr *)&a, sizeof a) < 0) { close(logfd); logfd = -1; }
    }
    if (logfd < 0) return;
    struct timespec rt; clock_gettime(CLOCK_REALTIME, &rt);
    unsigned char pkt[400]; int n = 0;
    pkt[n++] = 0;                                   /* LOG_ID_MAIN */
    unsigned short tid = (unsigned short)getpid(); memcpy(pkt + n, &tid, 2); n += 2;
    unsigned int s = (unsigned int)rt.tv_sec, ns = (unsigned int)rt.tv_nsec;
    memcpy(pkt + n, &s, 4); n += 4; memcpy(pkt + n, &ns, 4); n += 4;
    pkt[n++] = 4;                                   /* ANDROID_LOG_INFO */
    memcpy(pkt + n, "RemapD", 7); n += 7;
    int ml = (int)strlen(msg); if (ml > 300) ml = 300;
    memcpy(pkt + n, msg, ml); n += ml; pkt[n++] = 0;
    send(logfd, pkt, n, MSG_DONTWAIT);
}


static long long file_key(const char *path) {
    struct stat st;
    if (stat(path, &st) < 0) return -1;
    return (long long)st.st_mtim.tv_sec * 1000000000LL + st.st_mtim.tv_nsec + st.st_size;
}

static int prof_index(const char *name, int add) {
    for (int i = 0; i < nprofs; i++) if (!strcmp(profs[i], name)) return i;
    if (!add) return -1;
    if (nprofs == capprofs) {
        int nc = capprofs ? capprofs * 2 : 8;
        void *np = realloc(profs, nc * sizeof *profs); if (!np) return 0;
        profs = np; capprofs = nc;
    }
    snprintf(profs[nprofs], sizeof profs[0], "%s", name);
    return nprofs++;
}

static void add_rule(int prof, const rmp_rule *r) {
    for (int i = 0; i < ncrules; i++)
        if (crules[i].prof == prof && crules[i].code == r->code && crules[i].layer == r->layer) {
            snprintf(crules[i].action, sizeof crules[i].action, "%s", r->action); return;
        }
    if (ncrules == capcrules) {
        int nc = capcrules ? capcrules * 2 : 64;
        void *np = realloc(crules, nc * sizeof *crules); if (!np) return;
        crules = np; capcrules = nc;
    }
    struct crule *c = &crules[ncrules++];
    c->prof = prof; c->code = r->code; c->layer = r->layer;
    snprintf(c->action, sizeof c->action, "%s", r->action);
}

/* buttons with a "Mouse pointer" rule (any layer, any profile): pointer mode never takes them over, so the toggle
 * can always turn the mode off again, however the mode was turned on */
static unsigned char mouse_toggle_key[MAXKEY];

static void parse_conf(void) {
    ncrules = 0; nprofs = 0; cfg_double_ms = 250; cfg_hold_ms = 600;
    memset(mouse_toggle_key, 0, sizeof mouse_toggle_key);
    prof_index("default", 1);
    FILE *f = fopen(conf_path, "r"); if (!f) return;
    char *line = NULL; size_t cap = 0; int cur = 0;
    while (getline(&line, &cap, f) >= 0) {
        rmp_rule r;
        if (!rmp_parse_line(line, &r)) continue;
        if (r.kind == RMP_RULE && r.code >= 0 && r.code < MAXKEY && strstr(r.action, "mouse_toggle")) mouse_toggle_key[r.code] = 1;
        if (r.kind == RMP_PROFILE) cur = prof_index(r.name, 1);
        else if (r.kind == RMP_SET) {
            if (!strcmp(r.name, "double_ms")) cfg_double_ms = r.value; else cfg_hold_ms = r.value;
        } else add_rule(cur, &r);
    }
    free(line); fclose(f);
}

static void read_profile(void) {
    snprintf(active_prof, sizeof active_prof, "default");
    FILE *f = fopen(PROFILE_PATH, "r"); if (!f) return;
    char buf[sizeof active_prof];
    if (fgets(buf, sizeof buf, f)) {
        char *b = buf; while (*b == ' ' || *b == '\t') b++;
        b[strcspn(b, " \t\r\n")] = 0;
        if (*b) snprintf(active_prof, sizeof active_prof, "%s", b);
    }
    fclose(f);
}

static int is_orig(int idx) { return idx >= 0 && !strcmp(crules[idx].action, "orig"); }

/* rule lookup: the active profile's layers for a code if it has any, else default's */
static void compute_effective(void) {
    int ap = prof_index(active_prof, 0);
    for (int c = 0; c < MAXKEY; c++) eff[c][0] = eff[c][1] = eff[c][2] = -1;
    for (int pass = 0; pass < 2; pass++) {
        int want = pass == 0 ? 0 : ap;             /* default first, then the active profile overrides per code */
        if (pass == 1 && ap <= 0) break;
        static unsigned char seen[MAXKEY]; memset(seen, 0, sizeof seen);
        for (int i = 0; i < ncrules; i++) {
            if (crules[i].prof != want) continue;
            int c = crules[i].code;
            if (pass == 1 && !seen[c]) { eff[c][0] = eff[c][1] = eff[c][2] = -1; seen[c] = 1; }
            eff[c][crules[i].layer] = i;
        }
    }
    nmapped = 0;
    lyr_set_timing(L, cfg_double_ms, cfg_hold_ms);
    for (int c = 1; c < MAXKEY; c++) {
        lyr_keycfg k = { 0, 0, 0, 0 };
        /* an explicit "orig" tap/hold is the same as the implicit default; only double orig differs */
        k.has_tap = eff[c][LYR_TAP] >= 0 && !is_orig(eff[c][LYR_TAP]);
        k.has_double = eff[c][LYR_DOUBLE] >= 0;
        k.has_hold = eff[c][LYR_HOLD] >= 0 && !is_orig(eff[c][LYR_HOLD]);
        if (k.has_tap || k.has_double || k.has_hold) { k.tap_only = c >= 744 && c <= 747; nmapped++; }
        lyr_set_cfg(L, c, k);
    }
}

static void release_all(void);
static void mouse_set(int on);

/* listen file present and touched within LISTEN_MAX_S -> swallow every button; on entry drop anything the clone
 * or the layer state machine still holds (e.g. the OK press that started Listen) so no key sticks or fires later */
static void check_listen(void) {
    struct stat st;
    int on = stat(LISTEN_PATH, &st) == 0 && time(NULL) - st.st_mtime < LISTEN_MAX_S;
    if (on == listening) return;
    if (on) { release_all(); mouse_set(0); }   /* a held arrow / OK must not keep moving or clicking unseen */
    listening = on;
    alog(on ? "listening: buttons swallowed" : "listening off");
}

static void load_conf(void) {
    check_listen();
    long long ck = file_key(conf_path), pk = file_key(PROFILE_PATH);
    if (ck == conf_key && pk == prof_key) return;
    int conf_changed = ck != conf_key;
    conf_key = ck; prof_key = pk;
    if (conf_changed) parse_conf();
    read_profile();
    compute_effective();
    alog("config loaded: %d rules, %d profiles, profile=%s, %d mapped keys, double=%d hold=%d",
         ncrules, nprofs, active_prof, nmapped, cfg_double_ms, cfg_hold_ms);
}

static int passthrough;         /* no virtual remote (uinput refused): buttons cannot be swallowed, actions still run */

/* key injector (Fire OS 5-7: no /dev/uinput for shell): a child app_process that injects through InputManager */
static int ifd = -1, inj_lost;
static pid_t ipid = -1;
/* injector still starting (app_process takes 1 s on a fast stick, 10+ s on a slow one): its stdin, its READY pipe */
static int pend_in = -1, pend_out = -1;
static long long pend_deadline;
static char pend_buf[256]; static size_t pend_got;

static int mouse_on;
static int mouse_pass(int code, int value);

static void emit(int type, int code, int value) {
    /* a "Mouse pointer" button bypasses pointer mode so its rule can always run; its plain press still does the
     * pointer's job here (OK clicks at the pointer, an arrow nudges it, Rewind/Fast-forward scroll a notch) */
    if (mouse_on && type == EV_KEY && code >= 0 && code < MAXKEY && mouse_toggle_key[code] && mouse_pass(code, value)) return;
    if (ufd < 0) {
        /* the injector takes key down/up only and makes its own auto-repeat (the dispatcher repeats real devices only) */
        if (ifd >= 0 && type == EV_KEY && value != 2) {
            char b[32]; int n = snprintf(b, sizeof b, "%d %d\n", code, value);
            if (passthrough) return;   /* injector ready but not switched over yet (inj_wait): Android has the keys */
            if (write(ifd, b, n) != n) inj_lost = 1;   /* incl. EAGAIN: a stalled injector must not eat a key-up */
        }
        return;
    }
    struct input_event ev; memset(&ev, 0, sizeof ev);
    ev.type = type; ev.code = code; ev.value = value;
    if (write(ufd, &ev, sizeof ev) < 0) {}
}
static void tap(int code) { emit(EV_KEY, code, 1); emit(EV_SYN, 0, 0); emit(EV_KEY, code, 0); emit(EV_SYN, 0, 0); }

static void run_sh(const char *cmd) {
    pid_t p = fork();
    if (p == 0) {
        setsid();
        int dn = open("/dev/null", O_RDWR); dup2(dn, 0); dup2(dn, 1); dup2(dn, 2);
        execl("/system/bin/sh", "sh", "-c", cmd, (char *)0);
        _exit(127);
    }
}

/* start rules: run am start in a child; if am reports an error (e.g. the target app was uninstalled), emit the
 * original key so the button is not dead. The child inherits the uinput fd (CLOEXEC only acts on exec). */
static void run_start(const char *cmd, int code) {
    pid_t p = fork();
    if (p != 0) return;
    signal(SIGCHLD, SIG_DFL);
    setsid();
    /* not popen(): libc runs /bin/sh, which only exists from Android 10 (Fire OS 5-7 have /system/bin/sh only),
     * so every "open app" shortcut silently did nothing there */
    int pf[2];
    if (pipe(pf) < 0) _exit(127);
    pid_t c = fork();
    if (c < 0) _exit(127);
    if (c == 0) {
        int dn = open("/dev/null", O_RDONLY); dup2(dn, 0); dup2(pf[1], 1); dup2(pf[1], 2);
        close(pf[0]); close(pf[1]);
        execl("/system/bin/sh", "sh", "-c", cmd, (char *)0);
        _exit(127);
    }
    close(pf[1]);
    char out[1024]; size_t n = 0; ssize_t r;
    while (n < sizeof out - 1 && (r = read(pf[0], out + n, sizeof out - 1 - n)) > 0) n += r;
    out[n] = 0;
    { char drain[256]; while (read(pf[0], drain, sizeof drain) > 0) {} }
    close(pf[0]);
    int st = 0; waitpid(c, &st, 0);
    if (am_start_failed(out)) { tap(code); alog("key %d start failed -> original", code); }
    _exit(0);
}

/* act rules: spool protocol shared with stickd/Commands.act — write act/<name>.tmp, rename to act/<name>.act,
 * <name> = <epoch-ms>-<pid>-<seq> (13-digit ms so numeric and lexical order agree). act.fifo is impossible:
 * SELinux on Fire OS 8 denies shell fifo_file create. */
static void act_spool(const char *arg) {
    static unsigned seq;
    struct timespec ts; clock_gettime(CLOCK_REALTIME, &ts);
    long long ms = (long long)ts.tv_sec * 1000 + ts.tv_nsec / 1000000;
    char base[192], tmp[200], fin[200];
    snprintf(base, sizeof base, SPOOL_DIR "/%lld-%d-%u", ms, (int)getpid(), ++seq);
    snprintf(tmp, sizeof tmp, "%s.tmp", base);
    snprintf(fin, sizeof fin, "%s.act", base);
    mkdir(SPOOL_DIR, 0775);   /* EEXIST is fine */
    int fd = open(tmp, O_WRONLY | O_CREAT | O_EXCL | O_CLOEXEC, 0644);
    if (fd < 0) { alog("act spool open %s: %s", tmp, strerror(errno)); return; }
    char buf[300]; int len = snprintf(buf, sizeof buf, "%s\n", arg);
    if (len >= (int)sizeof buf) { len = sizeof buf - 1; buf[len - 1] = '\n'; }
    int ok = write(fd, buf, len) == len;
    if (!ok) alog("act spool write: %s", strerror(errno));
    if (close(fd) != 0) ok = 0;
    if (!ok) { unlink(tmp); return; }
    if (rename(tmp, fin) != 0) { alog("act spool rename: %s", strerror(errno)); unlink(tmp); }
}

static int mouse_on;
static int mouse_eat_back;           /* Back left pointer mode: swallow the rest of that press */
static void mouse_set(int on);

static const char *layer_name[3] = { "tap", "double", "hold" };

static void do_action(int code, int layer) {
    int idx = (layer >= 0 && layer < 3) ? eff[code][layer] : -1;
    const char *a = idx >= 0 ? crules[idx].action : "orig";
    const char *ln = layer_name[layer >= 0 && layer < 3 ? layer : 0];
    const char *arg = strchr(a, ' '); arg = arg ? arg + 1 : "";
    while (*arg == ' ' || *arg == '\t') arg++;
    char cmd[400];
    if (!strncmp(a, "start ", 6)) {
        snprintf(cmd, sizeof cmd, "am start -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -f 0x10200000 -n '%s'", arg);
        run_start(cmd, code); alog("key %d %s -> start %s", code, ln, arg);
    } else if (!strncmp(a, "sh ", 3)) { run_sh(arg); alog("key %d %s -> sh", code, ln); }
    else if (!strncmp(a, "key ", 4)) {
        int k = atoi(arg);
        if (k > 0 && k < MAXKEY) tap(k);
        alog("key %d %s -> key %d", code, ln, k);
    } else if (!strcmp(a, "act mouse_toggle")) {
        alog("key %d %s -> mouse pointer", code, ln);
        mouse_set(!mouse_on);
    }
    else if (!strncmp(a, "act ", 4)) { act_spool(arg); alog("key %d %s -> act %s", code, ln, arg); }
    else if (!strcmp(a, "orig")) { tap(code); alog("key %d %s -> original", code, ln); }
    else alog("key %d %s -> disabled", code, ln);
}

/* PASS -> original event on the clone; FIRE -> the rule's action. syn: add SYN_REPORT after a PASS
 * (tick/reset outputs; outputs of a real event are followed by that event's own SYN). */
static void run_outs(int n, int syn) {
    for (int i = 0; i < n; i++) {
        if (outs[i].kind == OUT_PASS) {
            emit(EV_KEY, outs[i].code, outs[i].value);
            if (syn) emit(EV_SYN, 0, 0);
            if (outs[i].value == 1 && syn) alog("key %d held -> original", outs[i].code);
        } else do_action(outs[i].code, outs[i].layer);
    }
}

/* release anything the clone still holds down (device lost / grab dropped / exit) */
static void release_all(void) { run_outs(lyr_reset(L, outs, NOUT), 1); }

/* ---- which device is the remote, and what state it is in (for the app's Status page) ---- */
static char remote_name[128];   /* name of the device we opened */
static int strict_name;         /* argv[2] given (test harness): exact name only */

/* one line in DIR/remote.state, rewritten only when it changes: "ok <name>", "passthrough <name>: <why>",
 * "notfound <name|name|...>" */
static void set_state(const char *fmt, ...) {
    static char last[512];
    char cur[512]; va_list ap; va_start(ap, fmt); vsnprintf(cur, sizeof cur, fmt, ap); va_end(ap);
    if (!strcmp(cur, last)) return;
    snprintf(last, sizeof last, "%s", cur);
    alog("STATUS remote %s", cur);
    FILE *f = fopen(DIR_PATH "/remote.state.tmp", "w");
    if (!f) return;
    fprintf(f, "%s\n", cur); fclose(f);
    rename(DIR_PATH "/remote.state.tmp", DIR_PATH "/remote.state");
}

static int has_word(const char *name, const char *w) {
    char low[128]; size_t i = 0;
    for (; name[i] && i < sizeof low - 1; i++) low[i] = (char)tolower((unsigned char)name[i]);
    low[i] = 0;
    return strstr(low, w) != NULL;
}

/* How much a device looks like the Fire TV remote: 0 = not it. The exact name always wins (every Fire OS 8 remote
 * is "Amazon Fire TV Remote"); other Fire OS versions and remotes use other names, so fall back to "has a d-pad
 * plus Back or Home", preferring Amazon/remote names and avoiding the HDMI-CEC and keyboard devices. */
static int score_remote(int fd, const char *name) {
    if (!strcmp(name, grab_name) && !getenv("REMAPD_TEST_NO_EXACT")) return 1000;   /* env: dev test of the fallback */
    if (strict_name) return 0;
    unsigned char b[(KEY_MAX + 8) / 8]; memset(b, 0, sizeof b);
    if (ioctl(fd, EVIOCGBIT(EV_KEY, sizeof b), b) < 0) return 0;
#define HAS(k) (b[(k) / 8] & (1 << ((k) % 8)))
    if (!(HAS(KEY_UP) && HAS(KEY_DOWN) && HAS(KEY_LEFT) && HAS(KEY_RIGHT))) return 0;
    if (!(HAS(KEY_BACK) || HAS(KEY_HOMEPAGE) || HAS(KEY_HOME))) return 0;
    int sc = 20;
    if (has_word(name, "amazon")) sc += 40;
    if (has_word(name, "remote")) sc += 40;
    if (has_word(name, "fire tv")) sc += 20;
    if (has_word(name, "cec")) sc -= 60;
    if (HAS(KEY_Q) && HAS(KEY_SPACE)) sc -= 10;   /* a full keyboard */
#undef HAS
    return sc > 0 ? sc : 0;
}

static int is_virtual(int fd) {
    char phys[64] = {0};
    ioctl(fd, EVIOCGPHYS(sizeof phys - 1), phys);
    return strcmp(phys, VIRT_PHYS) == 0;
}

static int open_remote(void) {
    DIR *d = opendir("/dev/input"); if (!d) return -1;
    struct dirent *e; int found = -1, best = 0;
    char seen[400] = "", bestp[64] = ""; size_t sl = 0;
    while ((e = readdir(d))) {
        if (strncmp(e->d_name, "event", 5)) continue;
        char p[64]; snprintf(p, sizeof p, "/dev/input/%s", e->d_name);
        int fd = open(p, O_RDONLY | O_CLOEXEC | O_NONBLOCK);
        if (fd < 0) { static int warned; if (warned++ < 8) alog("open %s: %s", p, strerror(errno)); continue; }
        char name[128] = {0}; ioctl(fd, EVIOCGNAME(sizeof name - 1), name);
        if (is_virtual(fd)) { close(fd); continue; }
        if (sl < sizeof seen - 2) sl += snprintf(seen + sl, sizeof seen - sl, "%s%s", sl ? "|" : "", name[0] ? name : "?");
        if (sl >= sizeof seen) sl = sizeof seen - 1;
        int sc = score_remote(fd, name);
        if (sc > best) {
            if (found >= 0) close(found);
            found = fd; best = sc;
            snprintf(remote_name, sizeof remote_name, "%s", name); snprintf(bestp, sizeof bestp, "%s", p);
            if (sc >= 1000) break;
        } else close(fd);
    }
    closedir(d);
    if (found >= 0) alog("remote at %s \"%s\" (match %d)", bestp, remote_name, best);
    else set_state("notfound %s", seen[0] ? seen : "-");
    return found;
}

static int uinput_err;
static int make_uinput(int src) {
    int fd = getenv("REMAPD_TEST_NO_UINPUT") ? (errno = EACCES, -1)   /* env: dev test of passthrough */
                                             : open("/dev/uinput", O_WRONLY | O_CLOEXEC | O_NONBLOCK);
    if (fd < 0) { uinput_err = errno; alog("uinput open failed: %s", strerror(errno)); return -1; }
    unsigned char bits[(KEY_MAX + 8) / 8]; memset(bits, 0, sizeof bits);
    ioctl(src, EVIOCGBIT(EV_KEY, sizeof bits), bits);
    ioctl(fd, UI_SET_EVBIT, EV_KEY); ioctl(fd, UI_SET_EVBIT, EV_SYN);
    ioctl(fd, UI_SET_EVBIT, EV_MSC); ioctl(fd, UI_SET_MSCBIT, MSC_SCAN);
    for (int k = 1; k < MAXKEY; k++) {
        int has = bits[k / 8] & (1 << (k % 8));
        /* also allow any plain KEY_* (< BTN_MISC) so "act as another button" can emit it */
        if (has || k < BTN_MISC) ioctl(fd, UI_SET_KEYBIT, k);
    }
    ioctl(fd, UI_SET_PHYS, VIRT_PHYS);
    struct input_id id; ioctl(src, EVIOCGID, &id);
    struct uinput_user_dev u; memset(&u, 0, sizeof u);
    snprintf(u.name, sizeof u.name, "%s", remote_name[0] ? remote_name : REMOTE_NAME);   /* same name -> same key layout */
    u.id = id;                          /* same bus/vendor/product -> Vendor_0171_Product_0427.kl */
    if (write(fd, &u, sizeof u) != sizeof u || ioctl(fd, UI_DEV_CREATE) < 0) {
        uinput_err = errno; alog("uinput create failed: %s", strerror(errno)); close(fd); return -1;
    }
    alog("virtual remote created");
    return fd;
}

static int inj_wait;                 /* injector ready, switching over once no button is physically held */
static int inj_fails;                /* consecutive failed starts / losses, for the retry backoff */
static long long inj_retry_at;       /* when to start the injector again (0 = no retry pending) */

static void injector_stop(void) {
    if (ifd >= 0) close(ifd);
    if (pend_in >= 0) close(pend_in);
    if (pend_out >= 0) close(pend_out);
    if (ipid > 0) kill(ipid, SIGKILL);
    ifd = -1; ipid = -1; inj_lost = 0; pend_in = pend_out = -1; pend_got = 0; inj_wait = 0;
}

/* Start the injector from the installed APK (path in DIR/apk, written by the ensure script). It is not waited
 * for: until its READY line arrives (injector_poll) remapd runs listen-only, so no button is ever held back. */
static int make_injector(int src) {
    if (getenv("REMAPD_TEST_NO_INJECT")) return -1;   /* env: dev test of passthrough */
    char apk[512] = "";
    FILE *f = fopen(DIR_PATH "apk", "r");
    if (f) { if (fgets(apk, sizeof apk, f)) apk[strcspn(apk, "\r\n")] = 0; fclose(f); }
    if (!apk[0] || access(apk, R_OK) != 0) { alog("injector: no readable apk"); return -1; }
    int in[2], out[2];
    if (pipe(in) < 0) return -1;
    if (pipe(out) < 0) { close(in[0]); close(in[1]); return -1; }
    struct input_id id; memset(&id, 0, sizeof id); ioctl(src, EVIOCGID, &id);
    pid_t p = fork();
    if (p < 0) { close(in[0]); close(in[1]); close(out[0]); close(out[1]); return -1; }
    if (p == 0) {
        dup2(in[0], 0); dup2(out[1], 1);
        int nul = open("/dev/null", O_WRONLY); if (nul >= 0) dup2(nul, 2);
        for (int i = 3; i < 64; i++) close(i);
        char v[8], pr[8], ve[8];
        snprintf(v, sizeof v, "%04x", id.vendor); snprintf(pr, sizeof pr, "%04x", id.product); snprintf(ve, sizeof ve, "%04x", id.version);
        setenv("CLASSPATH", apk, 1);
        execl("/system/bin/app_process", "app_process", "/system/bin", "--nice-name=stickinj",
              "com.clean.stickman.daemon.Injector", v, pr, ve, remote_name, (char *)NULL);
        _exit(127);
    }
    close(in[0]); close(out[1]);
    fcntl(in[1], F_SETFD, FD_CLOEXEC); fcntl(out[0], F_SETFD, FD_CLOEXEC);
    fcntl(in[1], F_SETFL, fcntl(in[1], F_GETFL) | O_NONBLOCK);
    fcntl(out[0], F_SETFL, fcntl(out[0], F_GETFL) | O_NONBLOCK);
    pend_in = in[1]; pend_out = out[0]; ipid = p; pend_got = 0; pend_deadline = now_ms() + 90000;
    alog("key injector starting pid %d", p);
    return 0;
}


/* Is a button physically down right now? Asked from the device itself (EVIOCGKEY), not from our own bookkeeping:
 * a key-up lost to dropped events must not block the switch to the injector for ever, and a long real hold must
 * still block it. */
static int any_key_held(void) {
    unsigned char b[(KEY_MAX + 8) / 8]; memset(b, 0, sizeof b);
    if (rfd < 0 || ioctl(rfd, EVIOCGKEY(sizeof b), b) < 0) {
        for (int c = 0; c < MAXKEY; c++) if (key_t0[c]) return 1;
        return 0;
    }
    for (size_t i = 0; i < sizeof b; i++) if (b[i]) return 1;
    memset(key_t0, 0, sizeof key_t0);   /* nothing is down: forget any stale press */
    return 0;
}

static void injector_failed(const char *why) {
    inj_fails++;
    inj_retry_at = inj_fails <= 5 ? now_ms() + 5000LL * inj_fails : 0;
    alog("key injector %s (%d); %s", why, inj_fails, inj_retry_at ? "retrying soon" : "giving up until the remote reconnects");
}

/* 1 when the pending injector just became ready, -1 when it failed (stopped), 0 otherwise */
static int injector_poll(void) {
    if (pend_out < 0) return 0;
    ssize_t n = read(pend_out, pend_buf + pend_got, sizeof pend_buf - 1 - pend_got);
    if (n > 0) {
        pend_got += n; pend_buf[pend_got] = 0;
        if (strstr(pend_buf, "READY")) {
            close(pend_out); pend_out = -1;
            ifd = pend_in; pend_in = -1; inj_lost = 0;
            alog("key injector ready");
            return 1;
        }
        if (pend_got > sizeof pend_buf - 64) pend_got = 0;
        return 0;
    }
    if (n == 0 || (n < 0 && errno != EAGAIN) || now_ms() > pend_deadline || kill(ipid, 0) < 0) {
        alog("key injector did not start");
        injector_stop();
        return -1;
    }
    return 0;
}


/* ---- mouse pointer mode -------------------------------------------------------------------------------------
 * A virtual mouse the d-pad drives: Android draws its own pointer for any mouse device, so no overlay is needed.
 * Arrows move (accelerating while held), OK is the left button, Rewind / Fast-forward scroll. Every other button
 * keeps its job, including the one that toggles the mode. The device is made through /dev/uinput (shell may open it
 * from Android 10: Fire OS 8) or, where that is closed, /dev/uhid as a HID mouse (open to shell on Android 9:
 * Fire OS 7). It only exists while the mode is on, so the pointer disappears when the mode is left. Needs the grab:
 * in listen-only mode the arrows would also move the focus, so the mode is refused there. */
#define MOUSE_UINPUT 1
#define MOUSE_UHID 2
#define MOUSE_DRAWN 3   /* no virtual mouse possible (Fire OS 5-7 without uhid): the injector draws and taps */
#define MOUSE_CMD DIR_PATH "mouse.cmd"   /* "on" / "off" / "toggle", written by the app; consumed here */
static int mfd = -1, mkind;
static long long m_dir_at[4];            /* up, down, left, right: when the arrow went down, 0 = up */
static long long m_last, m_wheel_at, m_wake_at;
static int m_wheel, m_btn;
static double m_fx, m_fy;

static const unsigned char MOUSE_RD[] = {
    0x05, 0x01, 0x09, 0x02, 0xA1, 0x01, 0x09, 0x01, 0xA1, 0x00, 0x05, 0x09, 0x19, 0x01, 0x29, 0x03,
    0x15, 0x00, 0x25, 0x01, 0x95, 0x03, 0x75, 0x01, 0x81, 0x02, 0x95, 0x01, 0x75, 0x05, 0x81, 0x03,
    0x05, 0x01, 0x09, 0x30, 0x09, 0x31, 0x09, 0x38, 0x15, 0x81, 0x25, 0x7F, 0x75, 0x08, 0x95, 0x03,
    0x81, 0x06, 0xC0, 0xC0 };

static int mouse_open(void) {
    /* env / marker file: dev test of the HID route on a system that has both */
    int no_ui = getenv("REMAPD_TEST_NO_UINPUT") || getenv("REMAPD_TEST_MOUSE_UHID") || access(DIR_PATH "mouse.uhid", F_OK) == 0;
    int fd = no_ui ? -1 : open("/dev/uinput", O_WRONLY | O_CLOEXEC | O_NONBLOCK);
    if (fd >= 0) {
        ioctl(fd, UI_SET_EVBIT, EV_KEY); ioctl(fd, UI_SET_EVBIT, EV_REL); ioctl(fd, UI_SET_EVBIT, EV_SYN);
        ioctl(fd, UI_SET_KEYBIT, BTN_LEFT); ioctl(fd, UI_SET_KEYBIT, BTN_RIGHT); ioctl(fd, UI_SET_KEYBIT, BTN_MIDDLE);
        ioctl(fd, UI_SET_RELBIT, REL_X); ioctl(fd, UI_SET_RELBIT, REL_Y); ioctl(fd, UI_SET_RELBIT, REL_WHEEL);
        struct uinput_user_dev u; memset(&u, 0, sizeof u);
        snprintf(u.name, sizeof u.name, "Stickman Pointer");
        u.id.bustype = BUS_VIRTUAL; u.id.vendor = 0x1209; u.id.product = 0x5350; u.id.version = 1;
        if (write(fd, &u, sizeof u) == sizeof u && ioctl(fd, UI_DEV_CREATE) == 0) { mkind = MOUSE_UINPUT; return fd; }
        close(fd);
    }
    fd = open("/dev/uhid", O_RDWR | O_CLOEXEC | O_NONBLOCK);
    if (fd >= 0) {
        struct uhid_event e; memset(&e, 0, sizeof e);
        e.type = UHID_CREATE2;
        snprintf((char *)e.u.create2.name, sizeof e.u.create2.name, "Stickman Pointer");
        memcpy(e.u.create2.rd_data, MOUSE_RD, sizeof MOUSE_RD);
        e.u.create2.rd_size = sizeof MOUSE_RD;
        e.u.create2.bus = BUS_USB; e.u.create2.vendor = 0x1209; e.u.create2.product = 0x5350; e.u.create2.version = 1;
        if (write(fd, &e, sizeof e) == sizeof e) { mkind = MOUSE_UHID; return fd; }
        close(fd);
    }
    return -1;
}

/* a "P ..." line for the injector's drawn pointer; a write failure is a lost injector like any other */
static void mouse_drawn(const char *line) {
    if (ifd < 0) return;
    int n = (int)strlen(line);
    if (write(ifd, line, n) != n) inj_lost = 1;
}

static void mouse_send(int dx, int dy, int wheel) {
    if (mkind == MOUSE_DRAWN && mouse_on) {
        char b[64]; snprintf(b, sizeof b, "P %d %d %d %d\n", dx, dy, wheel, m_btn ? 1 : 0);
        mouse_drawn(b);
        return;
    }
    if (mfd < 0) return;
    if (dx > 127) dx = 127; if (dx < -127) dx = -127;
    if (dy > 127) dy = 127; if (dy < -127) dy = -127;
    if (mkind == MOUSE_UINPUT) {
        struct input_event ev[5]; int n = 0; memset(ev, 0, sizeof ev);
        ev[n].type = EV_KEY; ev[n].code = BTN_LEFT; ev[n].value = m_btn; n++;
        if (dx) { ev[n].type = EV_REL; ev[n].code = REL_X; ev[n].value = dx; n++; }
        if (dy) { ev[n].type = EV_REL; ev[n].code = REL_Y; ev[n].value = dy; n++; }
        if (wheel) { ev[n].type = EV_REL; ev[n].code = REL_WHEEL; ev[n].value = wheel; n++; }
        ev[n].type = EV_SYN; n++;
        if (write(mfd, ev, n * sizeof ev[0]) < 0) {}
    } else {
        struct uhid_event e; memset(&e, 0, sizeof e);
        e.type = UHID_INPUT2; e.u.input2.size = 4;
        e.u.input2.data[0] = m_btn ? 1 : 0;
        e.u.input2.data[1] = (unsigned char)(signed char)dx;
        e.u.input2.data[2] = (unsigned char)(signed char)dy;
        e.u.input2.data[3] = (unsigned char)(signed char)wheel;
        if (write(mfd, &e, sizeof e) < 0) {}
        struct uhid_event in; while (read(mfd, &in, sizeof in) > 0) {}   /* kernel -> us (start/open/close): not needed */
    }
}

static void mouse_set(int on) {
    on = !!on;
    if (on == mouse_on) return;
    if (on) {
        if (passthrough || rfd < 0) { alog("STATUS mouse unavailable: buttons cannot be held back in listen-only mode"); return; }
        mfd = mouse_open();
        if (mfd < 0 && ifd >= 0 && !getenv("REMAPD_TEST_NO_DRAWN")) {
            /* no virtual mouse for shell here (Fire OS 5-7): the injector draws the pointer and taps for it */
            mkind = MOUSE_DRAWN;
            mouse_drawn("P on\n");
        } else if (mfd < 0) { alog("STATUS mouse unavailable: %s", strerror(errno)); return; }
        memset(m_dir_at, 0, sizeof m_dir_at); m_wheel = m_btn = 0; m_fx = m_fy = 0;
        m_wake_at = now_ms() + 500;   /* Android shows the pointer on the first movement, once the device is registered */
        mouse_on = 1;
        release_all();                /* an arrow or OK still held belongs to the old mode */
        alog("STATUS mouse on (%s)", mkind == MOUSE_UINPUT ? "uinput" : mkind == MOUSE_UHID ? "uhid" : "drawn");
    } else {
        if (m_btn) { m_btn = 0; mouse_send(0, 0, 0); }
        if (mkind == MOUSE_DRAWN) {
            mouse_drawn("P off\n");
            mouse_on = 0; mkind = 0;
            alog("STATUS mouse off");
            return;
        }
        if (mkind == MOUSE_UINPUT) ioctl(mfd, UI_DEV_DESTROY);
        else { struct uhid_event e; memset(&e, 0, sizeof e); e.type = UHID_DESTROY; if (write(mfd, &e, sizeof e) < 0) {} }
        close(mfd); mfd = -1; mouse_on = 0;
        alog("STATUS mouse off");
    }
}

/* the app's switch: a one-line command file, consumed */
static void mouse_check_cmd(void) {
    FILE *f = fopen(MOUSE_CMD, "r");
    if (!f) return;
    char w[16] = ""; if (fscanf(f, "%15s", w) != 1) w[0] = 0;
    fclose(f); unlink(MOUSE_CMD);
    if (!strcmp(w, "on")) mouse_set(1);
    else if (!strcmp(w, "off")) mouse_set(0);
    else if (!strcmp(w, "toggle")) mouse_set(!mouse_on);
}

/* 1 when the key belongs to the pointer in mouse mode (and was handled), 0 to let it through */
static int mouse_key(int code, int value) {
    int d = code == KEY_UP ? 0 : code == KEY_DOWN ? 1 : code == KEY_LEFT ? 2 : code == KEY_RIGHT ? 3 : -1;
    long long t = now_ms();
    if (d >= 0) {
        if (value == 1) {
            if (!m_dir_at[0] && !m_dir_at[1] && !m_dir_at[2] && !m_dir_at[3]) m_last = t;
            m_dir_at[d] = t;
            mouse_send(d == 2 ? -3 : d == 3 ? 3 : 0, d == 0 ? -3 : d == 1 ? 3 : 0, 0);   /* a tap is a small, exact step */
        } else if (value == 0) m_dir_at[d] = 0;
        return 1;
    }
    if (code == KEY_KPENTER || code == KEY_ENTER || code == KEY_SELECT) {   /* OK: Fire TV remotes send KPENTER */
        if (value == 1 || value == 0) { m_btn = value; mouse_send(0, 0, 0); }
        return 1;
    }
    if (code == KEY_REWIND || code == KEY_FASTFORWARD) {
        int w = code == KEY_REWIND ? 1 : -1;
        if (value == 1) { m_wheel = w; m_wheel_at = t + 350; mouse_send(0, 0, w); }
        else if (value == 0 && m_wheel == w) m_wheel = 0;
        return 1;
    }
    return 0;
}

/* a toggle button's plain press in pointer mode (see emit): one click / step / notch, no hold behaviour */
static int mouse_pass(int code, int value) {
    int d = code == KEY_UP ? 0 : code == KEY_DOWN ? 1 : code == KEY_LEFT ? 2 : code == KEY_RIGHT ? 3 : -1;
    if (d >= 0) {
        if (value == 1) mouse_send(d == 2 ? -12 : d == 3 ? 12 : 0, d == 0 ? -12 : d == 1 ? 12 : 0, 0);
        return 1;
    }
    if (code == KEY_KPENTER || code == KEY_ENTER || code == KEY_SELECT) {
        if (value == 1 || value == 0) { m_btn = value; mouse_send(0, 0, 0); }
        return 1;
    }
    if (code == KEY_REWIND || code == KEY_FASTFORWARD) {
        if (value == 1) mouse_send(0, 0, code == KEY_REWIND ? 1 : -1);
        return 1;
    }
    return 0;
}

/* ms until the pointer needs the loop again, or -1 */
static int mouse_timeout(void) {
    if (!mouse_on) return -1;
    if (m_dir_at[0] || m_dir_at[1] || m_dir_at[2] || m_dir_at[3]) return 16;
    if (m_wheel || m_wake_at) return 40;
    return -1;
}

static void mouse_tick(void) {
    if (!mouse_on) return;
    long long t = now_ms();
    if (m_wake_at && t >= m_wake_at) { m_wake_at = 0; mouse_send(1, 0, 0); mouse_send(-1, 0, 0); }
    if (m_wheel && t >= m_wheel_at) { m_wheel_at = t + 110; mouse_send(0, 0, m_wheel); }
    double dt = (t - m_last) / 1000.0;
    if (dt <= 0) return;
    if (dt > 0.1) dt = 0.1;
    m_last = t;
    double vx = 0, vy = 0;
    for (int d = 0; d < 4; d++) {
        if (!m_dir_at[d]) continue;
        double held = (t - m_dir_at[d]) / 1000.0;
        if (held < 0.18) continue;                      /* the tap step above stands alone first */
        double k = (held - 0.18) / 1.0; if (k > 1) k = 1;
        double v = 140 + 1100 * k * k;                  /* counts per second: slow start for aiming, then fast */
        if (d == 0) vy -= v; else if (d == 1) vy += v; else if (d == 2) vx -= v; else vx += v;
    }
    m_fx += vx * dt; m_fy += vy * dt;
    int dx = (int)m_fx, dy = (int)m_fy;
    if (dx || dy) { m_fx -= dx; m_fy -= dy; mouse_send(dx, dy, 0); }
}

static void set_grab(int on) {
    if (passthrough) on = 0;   /* nothing to replay the keys through: never take the remote away from Android */
    if (rfd < 0 || on == grabbed) return;
    if (!on) release_all();
    if (ioctl(rfd, EVIOCGRAB, on ? 1 : 0) == 0) { grabbed = on; alog(on ? "remote grabbed" : "remote released"); }
    else alog("EVIOCGRAB(%d) failed: %s", on, strerror(errno));
}

static void handle(struct input_event *ev) {
    if (ev->type == EV_KEY && ev->code < MAXKEY) {
        int c = ev->code;
        if (ev->value == 1) { alog("KEY %d", c); key_t0[c] = now_ms(); }
        if (ev->value == 0 && key_t0[c]) { alog("UP %d after %lldms", c, now_ms() - key_t0[c]); key_t0[c] = 0; }
        if (c == PANIC_KEY) back_down_at = ev->value ? (ev->value == 1 ? now_ms() : back_down_at) : 0;
        if (listening) return;   /* logged above, nothing passed on */
        if (mouse_on && grabbed) {
            /* Back always leaves pointer mode (eaten, press to release), so there is a way out even when the toggle
             * sits on a button pointer mode itself uses; the toggle's own button keeps reaching its rules */
            if (c == PANIC_KEY && ev->value == 1) { alog("Back -> mouse pointer off"); mouse_set(0); mouse_eat_back = 1; return; }
            if (!mouse_toggle_key[c] && mouse_key(c, ev->value)) return;
        }
        if (c == PANIC_KEY && mouse_eat_back) { if (ev->value == 0) mouse_eat_back = 0; return; }
        if (grabbed || (passthrough && nmapped > 0)) {
            /* in passthrough Android already saw the key itself: PASS outputs go nowhere (no clone), actions run.
             * a lone PASS rides on this event's own SYN (zero-change passthrough); when several
             * outputs come at once (flush / late deadline) each PASS gets its own SYN */
            int n = lyr_on_key(L, c, ev->value, now_ms(), outs, NOUT);
            run_outs(n, n > 1);
        }
        return;
    }
    if (grabbed && !listening) emit(ev->type, ev->code, ev->value);
}

static void device_lost(const char *why) {
    memset(key_t0, 0, sizeof key_t0);   /* the presses of the lost device never get their UP */
    inj_wait = 0;
    alog("%s", why);
    release_all();
    mouse_set(0);
    close(rfd); rfd = -1; grabbed = 0; passthrough = 0; back_down_at = 0;
}

/* pid in PID_PATH if it names a live process whose comm is exactly "remapd" (and isn't us), else 0 */
static int other_instance(void) {
    FILE *f = fopen(pid_path, "r");
    if (!f) return 0;
    int pid = 0;
    if (fscanf(f, "%d", &pid) != 1) pid = 0;
    fclose(f);
    if (pid <= 0 || pid == (int)getpid()) return 0;
    char path[64], comm[32] = "";
    snprintf(path, sizeof path, "/proc/%d/comm", pid);
    f = fopen(path, "r");
    if (!f) return 0;
    if (!fgets(comm, sizeof comm, f)) comm[0] = 0;
    fclose(f);
    comm[strcspn(comm, "\n")] = 0;
    return strcmp(comm, "remapd") == 0 ? pid : 0;
}

int main(int argc, char **argv) {
    conf_path = argc > 1 ? argv[1] : DEF_CONF;
    if (argc > 2 && argv[2][0]) { grab_name = argv[2]; strict_name = 1; }
    if (argc > 3 && argv[3][0]) pid_path = argv[3];
    int other = other_instance();   /* before grabbing anything: only one process may own the remote */
    if (other) { alog("remapd already running pid %d", other); return 0; }
    signal(SIGCHLD, SIG_IGN); signal(SIGHUP, SIG_IGN); signal(SIGPIPE, SIG_IGN);
    unlink(DIR_PATH "released");   /* running again: a past Back-hold exit no longer counts */
    L = lyr_new(250, 600);
    if (!L) return 1;
    FILE *pf = fopen(pid_path, "w");
    if (pf) { fprintf(pf, "%d\n", getpid()); fclose(pf); }
    alog("remapd starting pid %d device \"%s\"", getpid(), grab_name);
    dino = inotify_init1(IN_NONBLOCK | IN_CLOEXEC);
    if (dino >= 0 && inotify_add_watch(dino, DIR_PATH, IN_CREATE | IN_DELETE | IN_MOVED_TO | IN_CLOSE_WRITE | IN_ATTRIB) < 0) { close(dino); dino = -1; }

    for (;;) {
        load_conf();
        if (rfd < 0) {
            rfd = open_remote();
            if (rfd < 0) {
                if (ino < 0) { ino = inotify_init1(IN_NONBLOCK | IN_CLOEXEC); if (ino >= 0) inotify_add_watch(ino, "/dev/input", IN_CREATE | IN_ATTRIB); }
                if (ino >= 0) {   /* wait for a new /dev/input node (or 1 s), then rescan immediately */
                    struct pollfd ip = { .fd = ino, .events = POLLIN }; poll(&ip, 1, 1000);
                    char drain[1024]; while (read(ino, drain, sizeof drain) > 0) {}
                    usleep(20000);
                } else sleep(1);
                continue;
            }
            grabbed = 0;
            if (ufd < 0) ufd = make_uinput(rfd);
            /* No virtual remote on this system: keep going without the grab. Buttons then do their normal job AND
             * their Stickman action (better than nothing working); the Status page says so. Expected on Fire OS
             * 5-7: AOSP only opens /dev/uinput to the shell user from Android 10 (ueventd "0660 uhid uhid"); before
             * that it is system:net_bt_stack / system:bluetooth. (Android 9 does open /dev/uhid to shell: a HID
             * clone there would give full remapping on Fire OS 7. Not done yet.) */
            /* Fire OS 5-7: replay keys through InputManager instead; a failed start retries like a lost injector */
            if (ufd < 0 && ifd < 0 && pend_out < 0 && !inj_retry_at && make_injector(rfd) < 0) injector_failed("could not be started");
            passthrough = ufd < 0 && ifd < 0;
            /* the injector takes a few seconds to start (much longer on a slow box): say so, not "limited", or
             * the ensure report catches the gap and the Status page claims remapping can't work here */
            if (passthrough && pend_out >= 0) set_state("starting %s", remote_name);
            else if (passthrough) set_state("passthrough %s: %s", remote_name, strerror(uinput_err));
            else set_state(ufd >= 0 ? "ok %s" : "inject %s", remote_name);
        }
        {
            int ip = injector_poll();
            if (ip > 0) inj_wait = 1;
            else if (ip < 0) { injector_failed("did not start"); set_state("passthrough %s: key injector did not start", remote_name); }
        }
        /* switch to the injector only with no button held: Android saw that press's DOWN itself and must also get
         * its UP, which a grab taken mid-press would swallow (the key would then stay down) */
        if (inj_wait && ifd >= 0 && !any_key_held()) {
            inj_wait = 0; inj_fails = 0;
            release_all();
            passthrough = 0;
            set_state("inject %s", remote_name);
        }
        if (rfd >= 0 && ufd < 0 && ifd < 0 && pend_out < 0 && inj_retry_at && now_ms() >= inj_retry_at) {
            inj_retry_at = 0;
            if (make_injector(rfd) == 0) set_state("starting %s", remote_name);
            else injector_failed("could not be started");
        }
        if (ifd >= 0 && (inj_lost || kill(ipid, 0) < 0)) {
            /* never hold the remote with nothing to replay its keys: let go and fall back to listen-only */
            alog("key injector gone: remote released");
            if (grabbed) { ioctl(rfd, EVIOCGRAB, 0); grabbed = 0; }
            injector_stop();
            passthrough = 1;
            injector_failed("stopped");
            set_state("passthrough %s: key injector stopped", remote_name);
        }
        mouse_check_cmd();
        if (mouse_on && passthrough) mouse_set(0);
        set_grab(nmapped > 0 || listening || mouse_on);

        /* timeout: nearest layer deadline / panic deadline, else 1 s (to notice config changes) */
        int timeout = 1000; long long t = now_ms();
        int active = grabbed || (passthrough && nmapped > 0);
        long long dl = active ? lyr_next_deadline(L) : -1;
        if (dl >= 0) { long long left = dl - t; if (left < timeout) timeout = left < 0 ? 0 : (int)left; }
        if (back_down_at) { long long left = back_down_at + PANIC_MS - t; if (left < timeout) timeout = left < 0 ? 0 : (int)left; }

        struct pollfd p[2] = { { .fd = rfd, .events = POLLIN }, { .fd = dino, .events = POLLIN } };
        if (pend_out >= 0 && timeout > 200) timeout = 200;   /* injector starting: look for its READY soon */
        { int mt = mouse_timeout(); if (mt >= 0 && mt < timeout) timeout = mt; }
        int r = poll(p, dino >= 0 ? 2 : 1, timeout);
        if (r > 0 && dino >= 0 && (p[1].revents & POLLIN)) { char drain[1024]; while (read(dino, drain, sizeof drain) > 0) {} }
        if (r > 0 && p[0].revents) {
            /* stickd may have switched the profile (or the app started Listen) while we waited: apply it to this key */
            load_conf();
            if (p[0].revents & (POLLERR | POLLHUP | POLLNVAL)) { device_lost("remote gone"); continue; }
            struct input_event evs[64];
            ssize_t n = read(rfd, evs, sizeof evs);
            if (n < 0 && errno != EAGAIN) { device_lost("read error"); continue; }
            for (int i = 0; i < n / (ssize_t)sizeof evs[0]; i++) handle(&evs[i]);
        }
        t = now_ms();
        mouse_tick();
        if (active) run_outs(lyr_on_tick(L, t, outs, NOUT), 1);
        if (back_down_at && t - back_down_at >= PANIC_MS) {
            alog("BACK held 10s -> exiting, remote released");
            mouse_set(0);
            release_all();
            if (rfd >= 0) ioctl(rfd, EVIOCGRAB, 0);
            unlink(pid_path);
            /* left on purpose: the watchdog (stickd's liveness answer) must not start remapd again by itself */
            { int f = open(DIR_PATH "released", O_WRONLY | O_CREAT | O_TRUNC, 0666); if (f >= 0) close(f); }
            return 0;
        }
    }
}
