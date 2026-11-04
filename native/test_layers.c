/* Host tests for layers.c (tap/double/hold state machine + remap.conf v2 line parser). */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include "layers.h"

static int failures;
#define CHECK(cond) do { if (!(cond)) { fprintf(stderr, "FAIL %s:%d: %s\n", __func__, __LINE__, #cond); failures++; return; } } while (0)
#define OUTS 64

static lyr_keycfg cfg(int tap, int dbl, int hold, int tap_only) {
    lyr_keycfg c = { tap, dbl, hold, tap_only }; return c;
}
static int is_pass(lyr_out o, int code, int value) { return o.kind == OUT_PASS && o.code == code && o.value == value; }
static int is_fire(lyr_out o, int code, int layer) { return o.kind == OUT_FIRE && o.code == code && o.layer == layer; }

static void test_unmapped_passes_immediately(void) {
    lyr_state *s = lyr_new(250, 600); lyr_out o[OUTS];
    int n = lyr_on_key(s, 103, 1, 0, o, OUTS);
    CHECK(n == 1 && is_pass(o[0], 103, 1));
    CHECK(lyr_next_deadline(s) == -1);           /* nothing pending -> no delay anywhere */
    n = lyr_on_key(s, 103, 2, 0, o, OUTS);
    CHECK(n == 1 && is_pass(o[0], 103, 2));
    n = lyr_on_key(s, 103, 0, 0, o, OUTS);
    CHECK(n == 1 && is_pass(o[0], 103, 0));
    printf("PASS test_unmapped_passes_immediately\n");
}

static void test_tap_fires_on_up(void) {
    lyr_state *s = lyr_new(250, 600); lyr_out o[OUTS];
    lyr_set_cfg(s, 96, cfg(1, 0, 0, 0));
    CHECK(lyr_on_key(s, 96, 1, 0, o, OUTS) == 0);
    int n = lyr_on_key(s, 96, 0, 100, o, OUTS);
    CHECK(n == 1 && is_fire(o[0], 96, 0));
    CHECK(lyr_next_deadline(s) == -1);
    printf("PASS test_tap_fires_on_up\n");
}

static void test_hold_fires_at_threshold(void) {
    lyr_state *s = lyr_new(250, 600); lyr_out o[OUTS];
    lyr_set_cfg(s, 96, cfg(0, 0, 1, 0));
    CHECK(lyr_on_key(s, 96, 1, 0, o, OUTS) == 0);
    CHECK(lyr_next_deadline(s) == 600);
    CHECK(lyr_on_tick(s, 599, o, OUTS) == 0);
    int n = lyr_on_tick(s, 600, o, OUTS);
    CHECK(n == 1 && is_fire(o[0], 96, 2));
    CHECK(lyr_on_key(s, 96, 2, 650, o, OUTS) == 0);
    CHECK(lyr_on_key(s, 96, 0, 900, o, OUTS) == 0);
    CHECK(lyr_next_deadline(s) == -1);
    printf("PASS test_hold_fires_at_threshold\n");
}

static void test_hold_orig_passes_through(void) {
    lyr_state *s = lyr_new(250, 600); lyr_out o[OUTS];
    lyr_set_cfg(s, 96, cfg(1, 0, 0, 0));
    CHECK(lyr_on_key(s, 96, 1, 0, o, OUTS) == 0);
    int n = lyr_on_tick(s, 600, o, OUTS);
    CHECK(n == 1 && is_pass(o[0], 96, 1));
    n = lyr_on_key(s, 96, 2, 700, o, OUTS);
    CHECK(n == 1 && is_pass(o[0], 96, 2));
    n = lyr_on_key(s, 96, 0, 800, o, OUTS);
    CHECK(n == 1 && is_pass(o[0], 96, 0));
    printf("PASS test_hold_orig_passes_through\n");
}

static void test_double_fires(void) {
    lyr_state *s = lyr_new(250, 600); lyr_out o[OUTS];
    lyr_set_cfg(s, 168, cfg(0, 1, 0, 0));
    CHECK(lyr_on_key(s, 168, 1, 0, o, OUTS) == 0);
    CHECK(lyr_on_key(s, 168, 0, 50, o, OUTS) == 0);
    CHECK(lyr_next_deadline(s) == 300);
    CHECK(lyr_on_key(s, 168, 1, 200, o, OUTS) == 0);
    int n = lyr_on_key(s, 168, 0, 260, o, OUTS);
    CHECK(n == 1 && is_fire(o[0], 168, 1));
    CHECK(lyr_next_deadline(s) == -1);
    CHECK(lyr_on_tick(s, 5000, o, OUTS) == 0);
    printf("PASS test_double_fires\n");
}

static void test_double_window_times_out_to_tap(void) {
    lyr_state *s = lyr_new(250, 600); lyr_out o[OUTS];
    lyr_set_cfg(s, 168, cfg(0, 1, 0, 0));
    lyr_on_key(s, 168, 1, 0, o, OUTS);
    CHECK(lyr_on_key(s, 168, 0, 50, o, OUTS) == 0);
    CHECK(lyr_on_tick(s, 299, o, OUTS) == 0);
    int n = lyr_on_tick(s, 300, o, OUTS);
    CHECK(n == 1 && is_fire(o[0], 168, 0));
    CHECK(lyr_next_deadline(s) == -1);
    printf("PASS test_double_window_times_out_to_tap\n");
}

static void test_other_key_flushes_pending(void) {
    lyr_state *s = lyr_new(250, 600); lyr_out o[OUTS];
    lyr_set_cfg(s, 168, cfg(0, 1, 0, 0));
    lyr_on_key(s, 168, 1, 0, o, OUTS);
    lyr_on_key(s, 168, 0, 50, o, OUTS);
    int n = lyr_on_key(s, 103, 1, 100, o, OUTS);
    CHECK(n == 2 && is_fire(o[0], 168, 0) && is_pass(o[1], 103, 1));
    CHECK(lyr_next_deadline(s) == -1);
    printf("PASS test_other_key_flushes_pending\n");
}

static void test_tap_only_key_never_holds(void) {
    lyr_state *s = lyr_new(250, 600); lyr_out o[OUTS];
    lyr_set_cfg(s, 745, cfg(1, 0, 0, 1));
    CHECK(lyr_on_key(s, 745, 1, 0, o, OUTS) == 0);
    CHECK(lyr_next_deadline(s) == -1);           /* no hold timer for app buttons */
    int n = lyr_on_key(s, 745, 0, 0, o, OUTS);
    CHECK(n == 1 && is_fire(o[0], 745, 0));
    /* even if the UP is late, a tap-only key still taps */
    CHECK(lyr_on_key(s, 745, 1, 1000, o, OUTS) == 0);
    CHECK(lyr_on_tick(s, 5000, o, OUTS) == 0);
    n = lyr_on_key(s, 745, 0, 5000, o, OUTS);
    CHECK(n == 1 && is_fire(o[0], 745, 0));
    printf("PASS test_tap_only_key_never_holds\n");
}

static void test_device_lost_resets(void) {
    lyr_state *s = lyr_new(250, 600); lyr_out o[OUTS];
    lyr_set_cfg(s, 96, cfg(1, 0, 0, 0));
    lyr_on_key(s, 96, 1, 0, o, OUTS);
    int n = lyr_on_tick(s, 600, o, OUTS);
    CHECK(n == 1 && is_pass(o[0], 96, 1));
    n = lyr_reset(s, o, OUTS);
    CHECK(n == 1 && is_pass(o[0], 96, 0));
    CHECK(lyr_reset(s, o, OUTS) == 0);
    /* a later DOWN starts fresh: swallowed, then taps */
    CHECK(lyr_on_key(s, 96, 1, 1000, o, OUTS) == 0);
    n = lyr_on_key(s, 96, 0, 1100, o, OUTS);
    CHECK(n == 1 && is_fire(o[0], 96, 0));
    /* unmapped key held during loss is released too; pending windows are dropped */
    lyr_set_cfg(s, 168, cfg(0, 1, 0, 0));
    lyr_on_key(s, 103, 1, 2000, o, OUTS);
    lyr_on_key(s, 168, 1, 2010, o, OUTS);
    lyr_on_key(s, 168, 0, 2020, o, OUTS);
    n = lyr_reset(s, o, OUTS);
    CHECK(n == 1 && is_pass(o[0], 103, 0));
    CHECK(lyr_next_deadline(s) == -1);
    printf("PASS test_device_lost_resets\n");
}

static void test_cfg_change_mid_press_no_stuck(void) {
    lyr_state *s = lyr_new(250, 600); lyr_out o[OUTS];
    /* unmapped DOWN passed, then key becomes mapped: its UP must still pass */
    lyr_on_key(s, 103, 1, 0, o, OUTS);
    lyr_set_cfg(s, 103, cfg(1, 0, 0, 0));
    int n = lyr_on_key(s, 103, 0, 10, o, OUTS);
    CHECK(n == 1 && is_pass(o[0], 103, 0));
    /* hold-orig passing, then key becomes unmapped: UP still passes */
    lyr_on_key(s, 103, 1, 100, o, OUTS);
    lyr_on_tick(s, 700, o, OUTS);
    lyr_set_cfg(s, 103, cfg(0, 0, 0, 0));
    n = lyr_on_key(s, 103, 0, 800, o, OUTS);
    CHECK(n == 1 && is_pass(o[0], 103, 0));
    printf("PASS test_cfg_change_mid_press_no_stuck\n");
}

static void test_late_event_runs_expired_deadline_first(void) {
    lyr_state *s = lyr_new(250, 600); lyr_out o[OUTS];
    lyr_set_cfg(s, 96, cfg(1, 0, 0, 0));
    lyr_on_key(s, 96, 1, 0, o, OUTS);
    /* tick never ran; UP arrives after hold_ms -> hold-orig DOWN then UP */
    int n = lyr_on_key(s, 96, 0, 650, o, OUTS);
    CHECK(n == 2 && is_pass(o[0], 96, 1) && is_pass(o[1], 96, 0));
    /* double window expired without a tick; new DOWN of same key = tap, then fresh press */
    lyr_set_cfg(s, 168, cfg(1, 1, 0, 0));
    lyr_on_key(s, 168, 1, 1000, o, OUTS);
    lyr_on_key(s, 168, 0, 1050, o, OUTS);
    n = lyr_on_key(s, 168, 1, 1400, o, OUTS);
    CHECK(n == 1 && is_fire(o[0], 168, 0));
    n = lyr_on_key(s, 168, 0, 1450, o, OUTS);
    CHECK(n == 0 && lyr_next_deadline(s) == 1700);
    printf("PASS test_late_event_runs_expired_deadline_first\n");
}

static void test_parse_garbage(void) {
    rmp_rule r;
    const char *bad[] = {
        "", "   ", "# comment", "garbage", "0 tap none", "768 tap none", "-3 tap none", "12x tap none",
        "103", "103 foo none", "103 tap", "103 tap key 0", "103 tap key 768", "103 tap key x",
        "103 tap none extra", "103 tap orig x", "103 tap start", "103 hold act", "103 sh",
        "103 bogus thing", "set double_ms x", "set double_ms 0", "set foo 5", "set hold_ms",
        "profile", "profile a b", 0 };
    for (int i = 0; bad[i]; i++) {
        if (rmp_parse_line(bad[i], &r) != 0) { fprintf(stderr, "FAIL accepted garbage: '%s'\n", bad[i]); failures++; return; }
    }
    CHECK(rmp_parse_line("103 tap start com.a/.Main", &r) == 1);
    CHECK(r.kind == RMP_RULE && r.code == 103 && r.layer == 0 && !strcmp(r.action, "start com.a/.Main"));
    CHECK(rmp_parse_line("  168 double act sleep_timer_set 30 \r\n", &r) == 1);
    CHECK(r.kind == RMP_RULE && r.code == 168 && r.layer == 1 && !strcmp(r.action, "act sleep_timer_set 30"));
    CHECK(rmp_parse_line("96 hold key 172", &r) == 1 && r.layer == 2 && !strcmp(r.action, "key 172"));
    CHECK(rmp_parse_line("767 tap none", &r) == 1 && r.code == 767 && !strcmp(r.action, "none"));
    CHECK(rmp_parse_line("1 hold orig", &r) == 1 && r.code == 1 && !strcmp(r.action, "orig"));
    CHECK(rmp_parse_line("96 tap sh input  keyevent 3", &r) == 1 && !strcmp(r.action, "sh input  keyevent 3"));
    /* v1 lines -> tap */
    CHECK(rmp_parse_line("745 start org.x/.A", &r) == 1);
    CHECK(r.kind == RMP_RULE && r.code == 745 && r.layer == 0 && !strcmp(r.action, "start org.x/.A"));
    CHECK(rmp_parse_line("217 none", &r) == 1 && r.layer == 0 && !strcmp(r.action, "none"));
    CHECK(rmp_parse_line("217 key 96", &r) == 1 && r.layer == 0 && !strcmp(r.action, "key 96"));
    CHECK(rmp_parse_line("set double_ms 300", &r) == 1);
    CHECK(r.kind == RMP_SET && !strcmp(r.name, "double_ms") && r.value == 300);
    CHECK(rmp_parse_line("set hold_ms 800", &r) == 1 && r.kind == RMP_SET && !strcmp(r.name, "hold_ms") && r.value == 800);
    CHECK(rmp_parse_line("profile com.netflix.ninja", &r) == 1);
    CHECK(r.kind == RMP_PROFILE && !strcmp(r.name, "com.netflix.ninja"));
    printf("PASS test_parse_garbage\n");
}

int main(void) {
    test_unmapped_passes_immediately();
    test_tap_fires_on_up();
    test_hold_fires_at_threshold();
    test_hold_orig_passes_through();
    test_double_fires();
    test_double_window_times_out_to_tap();
    test_other_key_flushes_pending();
    test_tap_only_key_never_holds();
    test_device_lost_resets();
    test_cfg_change_mid_press_no_stuck();
    test_late_event_runs_expired_deadline_first();
    test_parse_garbage();
    if (failures) { fprintf(stderr, "%d FAILURE(S)\n", failures); return 1; }
    return 0;
}
