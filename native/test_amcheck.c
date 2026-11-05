/* Host tests for am_start_failed (remapd's "start" rule fallback). */
#include <stdio.h>
#include "amcheck.h"

static int failures;
#define CHECK(cond) do { if (!(cond)) { fprintf(stderr, "FAIL %s:%d: %s\n", __func__, __LINE__, #cond); failures++; return; } } while (0)

static void test_failures(void) {
    CHECK(am_start_failed("Starting: Intent { act=android.intent.action.MAIN cmp=com.wolf.firelauncher/.X }\n"
                          "Error type 3\nError: Activity class {com.wolf.firelauncher/.X} does not exist.\n"));
    CHECK(am_start_failed("Error: Activity not started, unable to resolve Intent { flg=0x10200000 }\n"));
    CHECK(am_start_failed("Starting: Intent {}\r\n  Error: x\n"));
    printf("PASS test_failures\n");
}

static void test_success(void) {
    CHECK(!am_start_failed("Starting: Intent { act=android.intent.action.MAIN cmp=org.videolan.vlc/.StartActivity }\n"));
    CHECK(!am_start_failed("Starting: Intent {}\nWarning: Activity not started, its current task has been brought to the front\n"));
    CHECK(!am_start_failed(""));
    CHECK(!am_start_failed(NULL));
    CHECK(!am_start_failed("Starting: Intent { cmp=a/.NoErrorHere }"));
    printf("PASS test_success\n");
}

int main(void) {
    test_failures();
    test_success();
    if (failures) { fprintf(stderr, "%d failure(s)\n", failures); return 1; }
    return 0;
}
