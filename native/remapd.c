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

static void parse_conf(void) {
    ncrules = 0; nprofs = 0; cfg_double_ms = 250; cfg_hold_ms = 600;
    prof_index("default", 1);
    FILE *f = fopen(conf_path, "r"); if (!f) return;
    char *line = NULL; size_t cap = 0; int cur = 0;
    while (getline(&line, &cap, f) >= 0) {
        rmp_rule r;
        if (!rmp_parse_line(line, &r)) continue;
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

/* listen file present and touched within LISTEN_MAX_S -> swallow every button; on entry drop anything the clone
 * or the layer state machine still holds (e.g. the OK press that started Listen) so no key sticks or fires later */
static void check_listen(void) {
    struct stat st;
    int on = stat(LISTEN_PATH, &st) == 0 && time(NULL) - st.st_mtime < LISTEN_MAX_S;
    if (on == listening) return;
    if (on) release_all();
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

/* key injector (Fire OS 5-7: no /dev/uinput for shell): a child app_process that injects through InputManager */
static int ifd = -1, inj_lost;
static pid_t ipid = -1;

static void emit(int type, int code, int value) {
    if (ufd < 0) {
        /* the injector takes key down/up only and makes its own auto-repeat (the dispatcher repeats real devices only) */
        if (ifd >= 0 && type == EV_KEY && value != 2) {
            char b[32]; int n = snprintf(b, sizeof b, "%d %d\n", code, value);
            if (write(ifd, b, n) != n && errno != EAGAIN) inj_lost = 1;
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
    } else if (!strncmp(a, "act ", 4)) { act_spool(arg); alog("key %d %s -> act %s", code, ln, arg); }
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
static int passthrough;         /* no virtual remote (uinput refused): buttons cannot be swallowed, actions still run */

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

static void injector_stop(void) {
    if (ifd >= 0) close(ifd);
    if (ipid > 0) kill(ipid, SIGKILL);
    ifd = -1; ipid = -1; inj_lost = 0;
}

/* Start the injector from the installed APK (path in DIR/apk, written by the ensure script) and wait for its
 * READY line. Returns 0 when keys can be replayed through it. */
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
    char buf[512]; size_t got = 0; int ready = 0; long long end = now_ms() + 15000;
    while (!ready && now_ms() < end) {
        struct pollfd pp = { .fd = out[0], .events = POLLIN };
        if (poll(&pp, 1, 500) <= 0) continue;
        ssize_t n = read(out[0], buf + got, sizeof buf - 1 - got);
        if (n <= 0) break;
        got += n; buf[got] = 0;
        if (strstr(buf, "READY")) ready = 1;
        if (got > sizeof buf - 64) got = 0;
    }
    close(out[0]);
    if (!ready) { alog("injector did not start"); close(in[1]); kill(p, SIGKILL); return -1; }
    fcntl(in[1], F_SETFD, FD_CLOEXEC);
    fcntl(in[1], F_SETFL, fcntl(in[1], F_GETFL) | O_NONBLOCK);
    ifd = in[1]; ipid = p; inj_lost = 0;
    alog("key injector started pid %d", p);
    return 0;
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
    alog("%s", why);
    release_all();
    close(rfd); rfd = -1; grabbed = 0; passthrough = 0;
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
            if (ufd < 0 && ifd < 0) make_injector(rfd);   /* Fire OS 5-7: replay keys through InputManager instead */
            passthrough = ufd < 0 && ifd < 0;
            if (passthrough) set_state("passthrough %s: %s", remote_name, strerror(uinput_err));
            else set_state(ufd >= 0 ? "ok %s" : "inject %s", remote_name);
        }
        if (ifd >= 0 && (inj_lost || kill(ipid, 0) < 0)) {
            /* never hold the remote with nothing to replay its keys: let go and fall back to listen-only */
            alog("key injector gone: remote released");
            if (grabbed) { ioctl(rfd, EVIOCGRAB, 0); grabbed = 0; }
            injector_stop();
            passthrough = 1;
            set_state("passthrough %s: key injector stopped", remote_name);
        }
        set_grab(nmapped > 0 || listening);

        /* timeout: nearest layer deadline / panic deadline, else 1 s (to notice config changes) */
        int timeout = 1000; long long t = now_ms();
        int active = grabbed || (passthrough && nmapped > 0);
        long long dl = active ? lyr_next_deadline(L) : -1;
        if (dl >= 0) { long long left = dl - t; if (left < timeout) timeout = left < 0 ? 0 : (int)left; }
        if (back_down_at) { long long left = back_down_at + PANIC_MS - t; if (left < timeout) timeout = left < 0 ? 0 : (int)left; }

        struct pollfd p[2] = { { .fd = rfd, .events = POLLIN }, { .fd = dino, .events = POLLIN } };
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
        if (active) run_outs(lyr_on_tick(L, t, outs, NOUT), 1);
        if (back_down_at && t - back_down_at >= PANIC_MS) {
            alog("BACK held 10s -> exiting, remote released");
            release_all();
            if (rfd >= 0) ioctl(rfd, EVIOCGRAB, 0);
            unlink(pid_path);
            return 0;
        }
    }
}
