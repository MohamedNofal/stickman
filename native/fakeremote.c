/*
 * fakeremote - dev-only on-device test remote (NOT packaged in the APK).
 * Creates a uinput device "Stickman Test Remote" (bus BT, 0171:0427, so the Fire TV remote's .kl applies),
 * then reads commands from stdin, one per line (see fakeremote_parse.h):
 *   down <code> | up <code> | tap <code> [hold_ms=80] | sleep <ms> | quit
 * Every key event written is printed as "SENT <code> <value> <CLOCK_MONOTONIC ms>".
 * Also prints "CLOCK mono=<ms> real=<ms>" at start (lets the host align getevent's timestamps) and
 * "READY" once the device exists. Keys still down are released and the device destroyed on quit/EOF/signal.
 * Pair with "remapd <conf> 'Stickman Test Remote'" (tools/devtest.sh).
 */
#define _GNU_SOURCE
#include <fcntl.h>
#include <linux/input.h>
#include <linux/uinput.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <time.h>
#include <unistd.h>
#include "fakeremote_parse.h"

#define NAME "Stickman Test Remote"

/* core/Keys.ALL */
static const int KEYS_ALL[] = { 116, 217, 103, 108, 105, 106, 96, 158, 172, 139, 168, 164, 208,
                                115, 114, 113, 748, 249, 362, 402, 403, 745, 744, 747, 746 };

static int ufd = -1;
static unsigned char down[FR_MAXCODE + 1];
static volatile sig_atomic_t stop;

static long long ms_of(clockid_t id) {
    struct timespec ts; clock_gettime(id, &ts);
    return (long long)ts.tv_sec * 1000 + ts.tv_nsec / 1000000;
}

static void ev(int type, int code, int value) {
    struct input_event e; memset(&e, 0, sizeof e);
    e.type = type; e.code = code; e.value = value;
    if (write(ufd, &e, sizeof e) != sizeof e) perror("write");
}

static void key(int code, int value) {
    ev(EV_KEY, code, value); ev(EV_SYN, SYN_REPORT, 0);
    down[code] = value != 0;
    printf("SENT %d %d %lld\n", code, value, ms_of(CLOCK_MONOTONIC));
    fflush(stdout);
}

static void sleep_ms(int ms) {
    struct timespec ts = { ms / 1000, (ms % 1000) * 1000000L };
    while (nanosleep(&ts, &ts) < 0 && !stop) {}
}

static int is_btn(int k) {   /* BTN_* ranges: keep them off so Android doesn't treat this as a joystick/mouse */
    return (k >= 0x100 && k <= 0x15f) || (k >= 0x220 && k <= 0x223) || (k >= 0x2c0 && k <= 0x2e7);
}

static void on_sig(int s) { (void)s; stop = 1; }

int main(void) {
    signal(SIGINT, on_sig); signal(SIGTERM, on_sig); signal(SIGHUP, on_sig); signal(SIGPIPE, SIG_IGN);
    ufd = open("/dev/uinput", O_WRONLY | O_CLOEXEC);
    if (ufd < 0) { perror("open /dev/uinput"); return 1; }
    ioctl(ufd, UI_SET_EVBIT, EV_KEY); ioctl(ufd, UI_SET_EVBIT, EV_SYN);
    for (int k = 1; k <= FR_MAXCODE; k++) if (!is_btn(k)) ioctl(ufd, UI_SET_KEYBIT, k);
    for (unsigned i = 0; i < sizeof KEYS_ALL / sizeof KEYS_ALL[0]; i++) ioctl(ufd, UI_SET_KEYBIT, KEYS_ALL[i]);
    ioctl(ufd, UI_SET_PHYS, "stickman/fakeremote");
    struct uinput_user_dev u; memset(&u, 0, sizeof u);
    snprintf(u.name, sizeof u.name, "%s", NAME);
    u.id.bustype = BUS_BLUETOOTH; u.id.vendor = 0x0171; u.id.product = 0x0427; u.id.version = 1;
    if (write(ufd, &u, sizeof u) != sizeof u || ioctl(ufd, UI_DEV_CREATE) < 0) { perror("uinput create"); return 1; }
    printf("CLOCK mono=%lld real=%lld\nREADY\n", ms_of(CLOCK_MONOTONIC), ms_of(CLOCK_REALTIME));
    fflush(stdout);

    char line[512]; int lineno = 0;
    while (!stop && fgets(line, sizeof line, stdin)) {
        lineno++;
        fr_cmd c;
        if (!fr_parse_line(line, &c)) { fprintf(stderr, "line %d: bad command: %s", lineno, line); continue; }
        if (c.op == FR_QUIT) break;
        switch (c.op) {
        case FR_DOWN: key(c.code, 1); break;
        case FR_UP: key(c.code, 0); break;
        case FR_TAP: key(c.code, 1); sleep_ms(c.ms); key(c.code, 0); break;
        case FR_SLEEP: sleep_ms(c.ms); break;
        }
    }
    for (int k = 1; k <= FR_MAXCODE; k++) if (down[k]) key(k, 0);
    ioctl(ufd, UI_DEV_DESTROY);
    close(ufd);
    printf("BYE\n");
    return 0;
}
