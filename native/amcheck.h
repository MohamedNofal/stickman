/* am_start_failed: does `am start` output report that the activity could not be started? (remapd M5:
 * a "start" rule whose app was uninstalled falls back to the original key instead of a dead button.) */
#ifndef AMCHECK_H
#define AMCHECK_H
#include <string.h>

/* True when any line of out begins with "Error" (am prints "Error: Activity class {..} does not exist.",
 * "Error type 3", "Error: Activity not started, unable to resolve Intent ..."). Warnings do not count. */
static inline int am_start_failed(const char *out) {
    if (!out) return 0;
    const char *l = out;
    while (*l) {
        while (*l == ' ' || *l == '\t' || *l == '\r') l++;
        if (!strncmp(l, "Error", 5)) return 1;
        const char *nl = strchr(l, '\n');
        if (!nl) break;
        l = nl + 1;
    }
    return 0;
}
#endif
