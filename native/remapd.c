/*
 * remapd - Fire TV remote button remapper. Runs as the shell user (uid 2000),
 * started by the "Remote Remap" app through its VPN self-ADB bridge.
 *
 * shell is in groups `input` (rw /dev/input/eventN) and `net_bt_admin` (rw /dev/uinput), so:
 *   1. EVIOCGRAB the real "Amazon Fire TV Remote" -> Android stops seeing it directly
 *   2. create a uinput clone with the same vendor/product (same .kl keylayout applies)
 *   3. forward every event to the clone, EXCEPT remapped keys, which run their action.
 * Holding a remapped key >= HOLD_MS passes the ORIGINAL key through (long-press still works).
 * Safety: if remapd dies the kernel releases the grab; holding BACK for 10 s exits remapd.
 *
 * Config (re-read when its mtime changes), one rule per line:
 *   <linux keycode> start <package/activity>   - open an app (as shell: no 5 s switch block)
 *   <linux keycode> key <linux keycode>        - act as another remote button
 *   <linux keycode> none                       - disable the button
 *   <linux keycode> sh <shell command...>      - run a shell command
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
#include <sys/inotify.h>
#include <sys/ioctl.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/un.h>
#include <sys/wait.h>
#include <time.h>
#include <unistd.h>

#define REMOTE_NAME "Amazon Fire TV Remote"
#define VIRT_PHYS "remapd/virtual"
#define MAXKEY 768
#define HOLD_MS 700
#define PANIC_KEY KEY_BACK
#define PANIC_MS 10000

enum { A_NONE = 0, A_START, A_KEY, A_OFF, A_SH };
struct rule { int type; int key; char arg[256]; };

static struct rule rules[MAXKEY];
static int nrules;
static const char *conf_path;
static long long conf_mtime;
static int rfd = -1, ufd = -1, grabbed;
static long long down_at[MAXKEY];
static int passthru[MAXKEY];
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

static void load_conf(void) {
    struct stat st;
    if (stat(conf_path, &st) < 0) { if (conf_mtime) { memset(rules, 0, sizeof rules); nrules = 0; conf_mtime = 0; } return; }
    long long m = (long long)st.st_mtim.tv_sec * 1000000000LL + st.st_mtim.tv_nsec + st.st_size;
    if (m == conf_mtime) return;
    conf_mtime = m;
    memset(rules, 0, sizeof rules); nrules = 0;
    FILE *f = fopen(conf_path, "r"); if (!f) return;
    char line[512];
    while (fgets(line, sizeof line, f)) {
        line[strcspn(line, "\r\n")] = 0;
        int code; char verb[16]; int off = 0;
        if (sscanf(line, "%d %15s %n", &code, verb, &off) < 2 || code <= 0 || code >= MAXKEY) continue;
        struct rule *r = &rules[code];
        const char *arg = line + off;
        if (!strcmp(verb, "start")) { r->type = A_START; snprintf(r->arg, sizeof r->arg, "%s", arg); }
        else if (!strcmp(verb, "sh")) { r->type = A_SH; snprintf(r->arg, sizeof r->arg, "%s", arg); }
        else if (!strcmp(verb, "key")) { r->type = A_KEY; r->key = atoi(arg); if (r->key <= 0 || r->key >= MAXKEY) r->type = A_NONE; }
        else if (!strcmp(verb, "none")) r->type = A_OFF;
        if (r->type) nrules++;
    }
    fclose(f);
    alog("config loaded: %d rules", nrules);
}

static void emit(int type, int code, int value) {
    if (ufd < 0) return;
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

static void do_action(int code) {
    struct rule *r = &rules[code];
    char cmd[400];
    switch (r->type) {
    case A_START:
        snprintf(cmd, sizeof cmd, "am start -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -f 0x10200000 -n '%s'", r->arg);
        run_sh(cmd); alog("key %d -> start %s", code, r->arg); break;
    case A_SH: run_sh(r->arg); alog("key %d -> sh", code); break;
    case A_KEY: tap(r->key); alog("key %d -> key %d", code, r->key); break;
    default: alog("key %d -> disabled", code); break;
    }
}

static int is_virtual(int fd) {
    char phys[64] = {0};
    ioctl(fd, EVIOCGPHYS(sizeof phys - 1), phys);
    return strcmp(phys, VIRT_PHYS) == 0;
}

static int open_remote(void) {
    DIR *d = opendir("/dev/input"); if (!d) return -1;
    struct dirent *e; int found = -1;
    while ((e = readdir(d))) {
        if (strncmp(e->d_name, "event", 5)) continue;
        char p[64]; snprintf(p, sizeof p, "/dev/input/%s", e->d_name);
        int fd = open(p, O_RDONLY | O_CLOEXEC | O_NONBLOCK);
        if (fd < 0) { static int warned; if (warned++ < 8) alog("open %s: %s", p, strerror(errno)); continue; }
        char name[128] = {0}; ioctl(fd, EVIOCGNAME(sizeof name - 1), name);
        if (!strcmp(name, REMOTE_NAME) && !is_virtual(fd)) { found = fd; alog("remote at %s", p); break; }
        close(fd);
    }
    closedir(d);
    return found;
}

static int make_uinput(int src) {
    int fd = open("/dev/uinput", O_WRONLY | O_CLOEXEC | O_NONBLOCK);
    if (fd < 0) { alog("uinput open failed: %s", strerror(errno)); return -1; }
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
    snprintf(u.name, sizeof u.name, "%s", REMOTE_NAME);
    u.id = id;                          /* same bus/vendor/product -> Vendor_0171_Product_0427.kl */
    if (write(fd, &u, sizeof u) != sizeof u || ioctl(fd, UI_DEV_CREATE) < 0) {
        alog("uinput create failed: %s", strerror(errno)); close(fd); return -1;
    }
    alog("virtual remote created");
    return fd;
}

static void set_grab(int on) {
    if (rfd < 0 || on == grabbed) return;
    if (ioctl(rfd, EVIOCGRAB, on ? 1 : 0) == 0) { grabbed = on; alog(on ? "remote grabbed" : "remote released"); }
    else alog("EVIOCGRAB(%d) failed: %s", on, strerror(errno));
}

static void handle(struct input_event *ev) {
    if (ev->type == EV_KEY && ev->code < MAXKEY) {
        int c = ev->code;
        if (ev->value == 1) { alog("KEY %d", c); key_t0[c] = now_ms(); }
        if (ev->value == 0 && key_t0[c]) { alog("UP %d after %lldms", c, now_ms() - key_t0[c]); key_t0[c] = 0; }
        if (c == PANIC_KEY) back_down_at = ev->value ? (ev->value == 1 ? now_ms() : back_down_at) : 0;
        if (grabbed && rules[c].type) {
            if (ev->value == 1) { down_at[c] = now_ms(); passthru[c] = 0; return; }
            if (ev->value == 2) { if (passthru[c]) emit(EV_KEY, c, 2); return; }
            if (ev->value == 0) {
                if (passthru[c]) { emit(EV_KEY, c, 0); emit(EV_SYN, 0, 0); }
                else if (down_at[c]) do_action(c);
                down_at[c] = 0; passthru[c] = 0; return;
            }
        }
    }
    if (grabbed) emit(ev->type, ev->code, ev->value);
}

int main(int argc, char **argv) {
    conf_path = argc > 1 ? argv[1] : "/data/local/tmp/remap/remap.conf";
    signal(SIGCHLD, SIG_IGN); signal(SIGHUP, SIG_IGN); signal(SIGPIPE, SIG_IGN);
    FILE *pf = fopen("/data/local/tmp/remap/remapd.pid", "w");
    if (pf) { fprintf(pf, "%d\n", getpid()); fclose(pf); }
    alog("remapd starting pid %d", getpid());

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
            if (ufd < 0) { close(rfd); rfd = -1; sleep(5); continue; }
        }
        set_grab(nrules > 0);

        /* timeout: nearest hold deadline, else 1 s (to notice config changes) */
        int timeout = 1000; long long t = now_ms();
        for (int c = 1; c < MAXKEY; c++)
            if (down_at[c] && !passthru[c]) { long long left = down_at[c] + HOLD_MS - t; if (left < timeout) timeout = left < 0 ? 0 : (int)left; }
        if (back_down_at) { long long left = back_down_at + PANIC_MS - t; if (left < timeout) timeout = left < 0 ? 0 : (int)left; }

        struct pollfd p = { .fd = rfd, .events = POLLIN };
        int r = poll(&p, 1, timeout);
        if (r > 0) {
            if (p.revents & (POLLERR | POLLHUP | POLLNVAL)) { alog("remote gone"); close(rfd); rfd = -1; grabbed = 0; continue; }
            struct input_event evs[64];
            ssize_t n = read(rfd, evs, sizeof evs);
            if (n < 0 && errno != EAGAIN) { alog("read error %s", strerror(errno)); close(rfd); rfd = -1; grabbed = 0; continue; }
            for (int i = 0; i < n / (ssize_t)sizeof evs[0]; i++) handle(&evs[i]);
        }
        t = now_ms();
        for (int c = 1; c < MAXKEY; c++)       /* long hold on a remapped key -> original key */
            if (down_at[c] && !passthru[c] && t - down_at[c] >= HOLD_MS) {
                passthru[c] = 1; emit(EV_KEY, c, 1); emit(EV_SYN, 0, 0); alog("key %d held -> original", c);
            }
        if (back_down_at && t - back_down_at >= PANIC_MS) {
            alog("BACK held 10s -> exiting, remote released");
            if (rfd >= 0) ioctl(rfd, EVIOCGRAB, 0);
            unlink("/data/local/tmp/remap/remapd.pid");
            return 0;
        }
    }
}
