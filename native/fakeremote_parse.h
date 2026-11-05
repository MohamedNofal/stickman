/* fakeremote stdin command parser (header-only: shared by fakeremote.c and its host test).
 * Lines: "down <code>" | "up <code>" | "tap <code> [hold_ms]" (default 80) | "sleep <ms>" | "quit".
 * Blank lines and "#" comments (also trailing) parse as FR_NOP. Anything else is rejected (returns 0). */
#ifndef FAKEREMOTE_PARSE_H
#define FAKEREMOTE_PARSE_H
#include <string.h>

enum { FR_NOP, FR_DOWN, FR_UP, FR_TAP, FR_SLEEP, FR_QUIT };
typedef struct { int op, code, ms; } fr_cmd;

#define FR_MAXCODE 767
#define FR_MAXMS 600000
#define FR_TAP_MS 80

/* non-negative decimal of at most 9 digits; *p advanced past it; -1 if none */
static inline long fr_num(const char **p) {
    const char *s = *p; long v = 0; int n = 0;
    while (*s >= '0' && *s <= '9') { if (++n > 9) return -1; v = v * 10 + (*s++ - '0'); }
    if (!n) return -1;
    *p = s; return v;
}

static inline int fr_parse_line(const char *line, fr_cmd *c) {
    if (!line || !c) return 0;
    char buf[256]; size_t len = strcspn(line, "#\r\n");
    if (len >= sizeof buf) return 0;
    memcpy(buf, line, len); buf[len] = 0;
    const char *tok[4]; int nt = 0;
    for (char *s = buf; *s;) {
        while (*s == ' ' || *s == '\t') *s++ = 0;
        if (!*s) break;
        if (nt == 4) return 0;
        tok[nt++] = s;
        while (*s && *s != ' ' && *s != '\t') s++;
    }
    c->op = FR_NOP; c->code = 0; c->ms = 0;
    if (nt == 0) return 1;
    long a[2] = { -1, -1 };
    for (int i = 1; i < nt && i < 3; i++) {
        const char *p = tok[i]; a[i - 1] = fr_num(&p);
        if (a[i - 1] < 0 || *p) return 0;
    }
    const char *w = tok[0];
    if (!strcmp(w, "quit")) { if (nt != 1) return 0; c->op = FR_QUIT; return 1; }
    if (!strcmp(w, "sleep")) {
        if (nt != 2 || a[0] > FR_MAXMS) return 0;
        c->op = FR_SLEEP; c->ms = (int)a[0]; return 1;
    }
    int op = !strcmp(w, "down") ? FR_DOWN : !strcmp(w, "up") ? FR_UP : !strcmp(w, "tap") ? FR_TAP : -1;
    if (op < 0 || nt < 2 || a[0] < 1 || a[0] > FR_MAXCODE) return 0;
    if (op != FR_TAP && nt != 2) return 0;
    if (op == FR_TAP && nt > 3) return 0;
    if (op == FR_TAP && nt == 3 && a[1] > FR_MAXMS) return 0;
    c->op = op; c->code = (int)a[0];
    c->ms = op == FR_TAP ? (nt == 3 ? (int)a[1] : FR_TAP_MS) : 0;
    return 1;
}
#endif
