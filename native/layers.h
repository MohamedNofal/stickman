/* layers: per-key tap / double / hold state machine + remap.conf v2 line parser.
 * Pure logic, no syscalls: the caller feeds key events and clock ticks and acts on the outputs. */
#ifndef LAYERS_H
#define LAYERS_H

#define LYR_MAXKEY 768

typedef struct { int has_tap, has_double, has_hold, tap_only; } lyr_keycfg;
typedef enum { OUT_PASS, OUT_FIRE } lyr_kind;           /* PASS: emit original (code,value); FIRE: run rule */
typedef struct { lyr_kind kind; int code; int value; int layer; } lyr_out; /* layer: 0 tap,1 double,2 hold */
enum { LYR_TAP = 0, LYR_DOUBLE = 1, LYR_HOLD = 2 };
typedef struct lyr_state lyr_state;                      /* opaque, sized for 768 codes */

lyr_state *lyr_new(int double_ms, int hold_ms);
void lyr_set_timing(lyr_state *, int double_ms, int hold_ms);
void lyr_set_cfg(lyr_state *, int code, lyr_keycfg cfg);  /* all-zero cfg = unmapped */
int  lyr_on_key(lyr_state *, int code, int value, long long now_ms, lyr_out *out, int max); /* returns n outputs */
int  lyr_on_tick(lyr_state *, long long now_ms, lyr_out *out, int max);
long long lyr_next_deadline(lyr_state *);                 /* -1 if none */
int  lyr_reset(lyr_state *, lyr_out *out, int max);       /* device lost: release any passed-through DOWN */

/* remap.conf v2 line parser (shared by remapd's loader and tests) */
typedef enum { RMP_RULE, RMP_PROFILE, RMP_SET } rmp_kind;
typedef struct { rmp_kind kind; int code; int layer; char action[256]; char name[128]; int value; } rmp_rule;
int  rmp_parse_line(const char *line, rmp_rule *out);    /* 1 = valid (fills out), 0 = ignore */

#endif
