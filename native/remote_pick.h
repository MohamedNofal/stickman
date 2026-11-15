/* Which input device is the remote (pure rules; shared by remapd.c and its host test).
 *
 * A Fire TV has more key devices than the remote, and some of them look like one: Amazon's own software keyboard
 * for the phone app ("amzkeyboard" on a Fire TV Cube: arrows, Back, Home and every letter), HDMI-CEC, a keyboard
 * the owner paired. The remote itself comes and goes: it disconnects when the Fire TV sleeps and returns as a new
 * device on the next press. remapd once took the best-looking device that was there at that moment and kept it,
 * so with the remote asleep it latched onto "amzkeyboard" and never looked again: remapping was dead after every
 * sleep until Stickman was switched off and on (GitHub issue #5).
 *
 * The rules now:
 *   1. Every remote-like device gets a score in tiers: the known remote name, an Amazon Bluetooth device, another
 *      Bluetooth device, other hardware, and software-made devices (vendor and product 0), which are only watched.
 *   2. With nothing held, the best device of the hardware tiers is taken at once (so the first press is remapped).
 *   3. A held device that is not the known remote is given up for a better one as soon as that turns up.
 *   4. Whatever the scores say, the device buttons are actually pressed on is the remote: presses arriving on
 *      another remote-like device make remapd follow them there. Only the known remote is never left that way.
 * Rule 4 is what makes a wrong guess harmless: it costs one unremapped press, not the feature.
 */
#ifndef REMOTE_PICK_H
#define REMOTE_PICK_H
#include <ctype.h>
#include <string.h>

#define RP_KNOWN 1000      /* the name every current Fire TV remote reports: taken at once, never left for a guess */
#define RP_AMAZON_BT 400   /* an Amazon Bluetooth device with remote buttons: a remote of another name or model */
#define RP_OTHER_BT 200    /* someone else's Bluetooth remote (or keyboard) */
#define RP_PLAIN 20        /* other hardware with remote buttons, e.g. an infrared receiver */
#define RP_WATCH 5         /* made by software: watched, and taken only when buttons are pressed on it */
#define RP_ADOPT_IDLE RP_PLAIN   /* lowest score taken without waiting for a press */

#define RP_BUS_BLUETOOTH 0x05
#define RP_VENDOR_AMAZON 0x0171

struct rp_dev {
    const char *name;
    int exact;               /* the name is the one remapd looks for */
    int bus, vendor, product;
    int dpad;                /* has all four arrows */
    int back_home;           /* has Back or Home */
};

static inline int rp_has_word(const char *name, const char *w) {
    char low[128]; size_t i = 0;
    for (; name && name[i] && i < sizeof low - 1; i++) low[i] = (char)tolower((unsigned char)name[i]);
    low[i] = 0;
    return strstr(low, w) != NULL;
}

/* How much a device looks like the remote; 0 = not at all. The real remote reports a whole keyboard's keys, so
 * "has letters" says nothing; what tells Amazon's software devices apart is that they have no vendor or product
 * (seen on a Fire TV Stick 4K Max: kcmouse and amazon_touch are 0000:0000, the remote is 0171:0427). */
static inline int rp_score(const struct rp_dev *d) {
    int s;
    if (d->exact) return RP_KNOWN;
    if (!d->dpad || !d->back_home) return 0;
    if (rp_has_word(d->name, "cec")) return 0;       /* the TV's own remote over HDMI: never ours to take */
    if (d->vendor == 0 && d->product == 0) s = RP_WATCH;
    else if (d->bus == RP_BUS_BLUETOOTH) s = d->vendor == RP_VENDOR_AMAZON ? RP_AMAZON_BT : RP_OTHER_BT;
    else s = RP_PLAIN;
    if (rp_has_word(d->name, "remote")) s += 3;      /* small: order within a tier, never across tiers */
    if (rp_has_word(d->name, "amazon")) s += 3;
    if (rp_has_word(d->name, "fire tv")) s += 2;
    return s;
}

/* Take cand without waiting for a press? held < 0 = nothing is held. */
static inline int rp_take_idle(int held, int cand) { return cand >= RP_ADOPT_IDLE && cand > held; }

/* Buttons are being pressed on another remote-like device: follow them there? Not away from the known remote, and
 * not to another node of the same device (a remote can show up as two nodes; hopping between them would lose a
 * press each time). held < 0 = nothing is held. */
static inline int rp_follow(int held, int cand, int same_device) {
    if (cand <= 0 || same_device) return 0;
    return held < RP_KNOWN;
}

/* A press that shows a device is being used as a remote (arrows, OK, Back, Home, menu, media, app buttons), as
 * opposed to typing: letters, digits, punctuation and modifiers (Linux codes 2..58, Enter aside) say nothing. */
static inline int rp_remote_key(int code) {
    if (code <= 0) return 0;
    if (code == 28) return 1;                        /* KEY_ENTER: OK on some remotes */
    return code < 2 || code > 58;
}
#endif
