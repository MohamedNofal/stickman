/* Host tests for fakeremote's stdin command parser (fr_parse_line). */
#include <stdio.h>
#include "fakeremote_parse.h"

static int failures;
#define CHECK(cond) do { if (!(cond)) { fprintf(stderr, "FAIL %s:%d: %s\n", __func__, __LINE__, #cond); failures++; return; } } while (0)

static void test_valid_lines(void) {
    fr_cmd c;
    CHECK(fr_parse_line("down 108\n", &c) && c.op == FR_DOWN && c.code == 108);
    CHECK(fr_parse_line("up 108", &c) && c.op == FR_UP && c.code == 108);
    CHECK(fr_parse_line("tap 168", &c) && c.op == FR_TAP && c.code == 168 && c.ms == 80);
    CHECK(fr_parse_line("  tap 172 800 \r\n", &c) && c.op == FR_TAP && c.code == 172 && c.ms == 800);
    CHECK(fr_parse_line("tap 744 0", &c) && c.op == FR_TAP && c.code == 744 && c.ms == 0);
    CHECK(fr_parse_line("sleep 120", &c) && c.op == FR_SLEEP && c.ms == 120);
    CHECK(fr_parse_line("quit", &c) && c.op == FR_QUIT);
    CHECK(fr_parse_line("", &c) && c.op == FR_NOP);
    CHECK(fr_parse_line("   \n", &c) && c.op == FR_NOP);
    CHECK(fr_parse_line("# comment tap 5", &c) && c.op == FR_NOP);
    CHECK(fr_parse_line("tap 108 # trailing comment", &c) && c.op == FR_TAP && c.ms == 80);
    CHECK(fr_parse_line("down 1", &c) && c.code == 1);
    CHECK(fr_parse_line("down 767", &c) && c.code == 767);
    printf("PASS test_valid_lines\n");
}

static void test_garbage_rejected(void) {
    fr_cmd c;
    const char *bad[] = {
        "jump 5", "down", "down x", "down 108x", "down -1", "down 0", "down 768", "down 99999999999",
        "up", "tap", "tap 108 -5", "tap 108 abc", "tap 108 80 9", "sleep", "sleep -1", "sleep 1.5",
        "quit now", "DOWN 108", "down  108  extra", "tap 108 600001", "sleep 600001", "downs 108",
    };
    for (unsigned i = 0; i < sizeof bad / sizeof bad[0]; i++) {
        if (fr_parse_line(bad[i], &c)) { fprintf(stderr, "FAIL accepted garbage: '%s'\n", bad[i]); failures++; return; }
    }
    CHECK(!fr_parse_line(NULL, &c));
    printf("PASS test_garbage_rejected\n");
}

int main(void) {
    test_valid_lines();
    test_garbage_rejected();
    if (failures) { fprintf(stderr, "%d failure(s)\n", failures); return 1; }
    return 0;
}
