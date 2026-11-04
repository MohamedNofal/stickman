/* layers: per-key tap / double / hold state machine + remap.conf v2 line parser. No syscalls.
 *
 * Per-key states (the state, not the current cfg, decides what an UP/repeat does, so a config
 * change mid-press can never leave a passed-through DOWN without its UP):
 *   IDLE   nothing in flight
 *   PASS   a DOWN was emitted to the clone (unmapped key, or hold-orig); repeats + UP pass
 *   WAIT1  mapped key down, swallowed; hold deadline = t + hold_ms (none for tap_only keys)
 *   HELD   hold rule fired; swallow repeats + UP
 *   WIN    first press released, double layer exists; window deadline = t + double_ms
 *   WAIT2  second press down inside the window; its UP fires the double rule
 * Expired deadlines are processed at the start of every on_key too, so a late event (poll woke
 * late) is still ordered correctly after the timeout it implies.
 */
#include <stdlib.h>
#include <string.h>
#include "layers.h"

enum { S_IDLE = 0, S_PASS, S_WAIT1, S_HELD, S_WIN, S_WAIT2 };

struct lyr_state {
    int double_ms, hold_ms;
    lyr_keycfg cfg[LYR_MAXKEY];
    unsigned char st[LYR_MAXKEY];
    long long t[LYR_MAXKEY];
};

lyr_state *lyr_new(int double_ms, int hold_ms) {
    lyr_state *s = calloc(1, sizeof *s);
    if (s) lyr_set_timing(s, double_ms, hold_ms);
    return s;
}

void lyr_set_timing(lyr_state *s, int double_ms, int hold_ms) {
    s->double_ms = double_ms > 0 ? double_ms : 250;
    s->hold_ms = hold_ms > 0 ? hold_ms : 600;
}

void lyr_set_cfg(lyr_state *s, int code, lyr_keycfg cfg) {
    if (code > 0 && code < LYR_MAXKEY) s->cfg[code] = cfg;
}

static int mapped(const lyr_keycfg *c) { return c->has_tap || c->has_double || c->has_hold; }

static int push(lyr_out *out, int *n, int max, lyr_kind k, int code, int value, int layer) {
    if (*n >= max) return 0;
    out[*n].kind = k; out[*n].code = code; out[*n].value = value; out[*n].layer = layer;
    (*n)++;
    return 1;
}

/* fire every deadline <= now; a state only changes if its output fit in the buffer */
static void expire(lyr_state *s, long long now, lyr_out *out, int *n, int max) {
    for (int c = 1; c < LYR_MAXKEY; c++) {
        if (s->st[c] == S_WAIT1 && !s->cfg[c].tap_only && now >= s->t[c] + s->hold_ms) {
            if (s->cfg[c].has_hold) { if (push(out, n, max, OUT_FIRE, c, 0, LYR_HOLD)) s->st[c] = S_HELD; }
            else if (push(out, n, max, OUT_PASS, c, 1, 0)) s->st[c] = S_PASS;
        } else if (s->st[c] == S_WIN && now >= s->t[c] + s->double_ms) {
            if (push(out, n, max, OUT_FIRE, c, 0, LYR_TAP)) s->st[c] = S_IDLE;
        }
    }
}

int lyr_on_tick(lyr_state *s, long long now_ms, lyr_out *out, int max) {
    int n = 0;
    expire(s, now_ms, out, &n, max);
    return n;
}

int lyr_on_key(lyr_state *s, int c, int value, long long now, lyr_out *out, int max) {
    int n = 0;
    if (c <= 0 || c >= LYR_MAXKEY) { push(out, &n, max, OUT_PASS, c, value, 0); return n; }
    expire(s, now, out, &n, max);
    unsigned char *st = &s->st[c];
    if (value == 1) {
        /* any DOWN of another key ends a pending double window as a tap, first */
        for (int k = 1; k < LYR_MAXKEY; k++)
            if (k != c && s->st[k] == S_WIN && push(out, &n, max, OUT_FIRE, k, 0, LYR_TAP)) s->st[k] = S_IDLE;
        switch (*st) {
        case S_IDLE:
            if (mapped(&s->cfg[c])) { *st = S_WAIT1; s->t[c] = now; }
            else if (push(out, &n, max, OUT_PASS, c, 1, 0)) *st = S_PASS;
            break;
        case S_WIN: *st = S_WAIT2; s->t[c] = now; break;
        case S_PASS: push(out, &n, max, OUT_PASS, c, 1, 0); break;   /* duplicate DOWN */
        default: break;                                              /* WAIT1/HELD/WAIT2: swallow */
        }
    } else if (value == 0) {
        switch (*st) {
        case S_PASS: if (push(out, &n, max, OUT_PASS, c, 0, 0)) *st = S_IDLE; break;
        case S_IDLE: push(out, &n, max, OUT_PASS, c, 0, 0); break;   /* UP whose DOWN we never saw */
        case S_WAIT1:
            if (s->cfg[c].has_double) { *st = S_WIN; s->t[c] = now; }
            else if (push(out, &n, max, OUT_FIRE, c, 0, LYR_TAP)) *st = S_IDLE;
            break;
        case S_WAIT2: if (push(out, &n, max, OUT_FIRE, c, 0, LYR_DOUBLE)) *st = S_IDLE; break;
        case S_HELD: *st = S_IDLE; break;
        default: break;                                              /* WIN: stray UP */
        }
    } else {
        /* repeat (2) or anything else: pass only when the clone has (or never needed) the DOWN */
        if (*st == S_PASS || *st == S_IDLE) push(out, &n, max, OUT_PASS, c, value, 0);
    }
    return n;
}

long long lyr_next_deadline(lyr_state *s) {
    long long best = -1;
    for (int c = 1; c < LYR_MAXKEY; c++) {
        long long d;
        if (s->st[c] == S_WAIT1 && !s->cfg[c].tap_only) d = s->t[c] + s->hold_ms;
        else if (s->st[c] == S_WIN) d = s->t[c] + s->double_ms;
        else continue;
        if (best < 0 || d < best) best = d;
    }
    return best;
}

int lyr_reset(lyr_state *s, lyr_out *out, int max) {
    int n = 0;
    for (int c = 1; c < LYR_MAXKEY; c++) {
        if (s->st[c] == S_PASS) { if (push(out, &n, max, OUT_PASS, c, 0, 0)) s->st[c] = S_IDLE; }
        else s->st[c] = S_IDLE;       /* swallowed presses / pending windows are dropped */
    }
    return n;
}

/* ---------------- remap.conf v2 line parser (mirrors core/RemapModel.parse) ---------------- */

static int ws(char ch) { return ch == ' ' || ch == '\t' || ch == '\n' || ch == '\r' || ch == '\v' || ch == '\f'; }

/* Integer.parseInt semantics on a whole token: optional sign then digits only */
static int parse_int(const char *p, int len, int *v) {
    int i = 0, neg = 0; long long x = 0;
    if (len > 0 && (p[0] == '+' || p[0] == '-')) { neg = p[0] == '-'; i = 1; }
    if (i >= len || len - i > 10) return 0;
    for (; i < len; i++) { if (p[i] < '0' || p[i] > '9') return 0; x = x * 10 + (p[i] - '0'); }
    if (neg) x = -x;
    if (x > 2147483647LL || x < -2147483648LL) return 0;
    *v = (int)x;
    return 1;
}

static int parse_code(const char *p, int len) {
    int v;
    return parse_int(p, len, &v) && v >= 1 && v <= 767 ? v : 0;
}

/* next whitespace-separated token from *pp: returns start, sets *len, advances past trailing ws */
static const char *tok(const char **pp, const char *end, int *len) {
    const char *p = *pp;
    while (p < end && ws(*p)) p++;
    const char *b = p;
    while (p < end && !ws(*p)) p++;
    *len = (int)(p - b);
    while (p < end && ws(*p)) p++;
    *pp = p;
    return b;
}

/* validAction: verb + trimmed rest; copies the (already trimmed) action into dst */
static int valid_action(const char *a, const char *end, char *dst, int dstsz) {
    const char *p = a; int vl;
    const char *v = tok(&p, end, &vl);
    int rl = (int)(end - p);                  /* rest, already trimmed both sides */
#define IS(s) (vl == (int)strlen(s) && !memcmp(v, s, vl))
    int ok;
    if (IS("start") || IS("act") || IS("sh")) ok = rl > 0;
    else if (IS("key")) ok = parse_code(p, rl) != 0;
    else if (IS("none") || IS("orig")) ok = rl == 0;
    else ok = 0;
#undef IS
    if (!ok || (int)(end - a) >= dstsz) return 0;   /* too long: reject rather than truncate a command */
    memcpy(dst, a, end - a); dst[end - a] = 0;
    return 1;
}

int rmp_parse_line(const char *line, rmp_rule *out) {
    if (!line) return 0;
    const char *b = line, *e = line + strlen(line);
    while (b < e && (unsigned char)*b <= ' ') b++;                 /* String.trim() */
    while (e > b && (unsigned char)e[-1] <= ' ') e--;
    if (b == e || *b == '#') return 0;
    memset(out, 0, sizeof *out);

    const char *p = b; int l0, l1, l2;
    const char *t0 = tok(&p, e, &l0);
    if (l0 == 3 && !memcmp(t0, "set", 3)) {
        const char *k = tok(&p, e, &l1);
        const char *v = tok(&p, e, &l2);
        int val;
        if (l1 == 0 || l2 == 0 || p != e || !parse_int(v, l2, &val) || val <= 0) return 0;
        if (!((l1 == 9 && !memcmp(k, "double_ms", 9)) || (l1 == 7 && !memcmp(k, "hold_ms", 7)))) return 0;
        out->kind = RMP_SET; memcpy(out->name, k, l1); out->value = val;
        return 1;
    }
    if (l0 == 7 && !memcmp(t0, "profile", 7)) {
        const char *nm = tok(&p, e, &l1);
        if (l1 == 0 || p != e || l1 >= (int)sizeof out->name) return 0;
        out->kind = RMP_PROFILE; memcpy(out->name, nm, l1);
        return 1;
    }
    int code = parse_code(t0, l0);
    if (!code || p == e) return 0;
    const char *after_code = p;
    const char *t1 = tok(&p, e, &l1);
    int layer = -1;
    if (l1 == 3 && !memcmp(t1, "tap", 3)) layer = LYR_TAP;
    else if (l1 == 6 && !memcmp(t1, "double", 6)) layer = LYR_DOUBLE;
    else if (l1 == 4 && !memcmp(t1, "hold", 4)) layer = LYR_HOLD;
    const char *act = layer >= 0 ? p : after_code;      /* v1: "<code> <action>" is a tap rule */
    if (layer >= 0 && act == e) return 0;
    if (!valid_action(act, e, out->action, sizeof out->action)) return 0;
    out->kind = RMP_RULE; out->code = code; out->layer = layer >= 0 ? layer : LYR_TAP;
    return 1;
}
