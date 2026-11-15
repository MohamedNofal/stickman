/* Host test of remote_pick.h (tests/run.sh builds every native/test_*.c with host gcc). */
#include <stdio.h>
#include "remote_pick.h"

static int fails;
#define CHECK(cond, what) do { if (!(cond)) { printf("FAIL remote_pick: %s\n", what); fails++; } } while (0)

static int sc(const char *name, int exact, int bus, int vendor, int product, int dpad, int bh) {
    struct rp_dev d = { name, exact, bus, vendor, product, dpad, bh };
    return rp_score(&d);
}

int main(void) {
    /* the devices of a real Fire TV Stick 4K Max (getevent, 2026-11-15) and of the Cube in issue #5 */
    int remote = sc("Amazon Fire TV Remote", 1, 0x05, 0x0171, 0x0427, 1, 1);
    int other_name = sc("AR", 0, 0x05, 0x0171, 0x0419, 1, 1);            /* another Amazon remote model */
    int amzkeyboard = sc("amzkeyboard", 0, 0x05, 0, 0, 1, 1);            /* Amazon's software keyboard: every key */
    int kcmouse = sc("kcmouse", 0, 0x05, 0, 0, 0, 0);
    int hdmipower = sc("hdmipower", 0, 0, 0, 0, 0, 0);
    int cec = sc("cec_input", 0, 0x19, 0x0001, 0x0001, 1, 1);
    int bt_keyboard = sc("Logitech K380", 0, 0x05, 0x046d, 0xb342, 1, 1);
    int ir = sc("mtk-ir", 0, 0x19, 0x0001, 0x0001, 1, 1);
    int emu_keyboard = sc("qwerty2", 0, 0x19, 0, 0, 1, 1);

    CHECK(remote == RP_KNOWN, "the known name is the known remote");
    CHECK(other_name >= RP_AMAZON_BT && other_name < RP_KNOWN, "an Amazon Bluetooth remote of another name");
    CHECK(amzkeyboard > 0 && amzkeyboard < RP_ADOPT_IDLE, "amzkeyboard is only watched");
    CHECK(emu_keyboard > 0 && emu_keyboard < RP_ADOPT_IDLE, "a software keyboard is only watched");
    CHECK(kcmouse == 0 && hdmipower == 0, "no remote buttons: not a remote");
    CHECK(cec == 0, "HDMI-CEC is never taken");
    CHECK(bt_keyboard >= RP_OTHER_BT && bt_keyboard < RP_AMAZON_BT, "someone else's Bluetooth device");
    CHECK(ir >= RP_PLAIN && ir < RP_OTHER_BT, "other hardware");
    CHECK(sc("Amazon Fire TV Remote", 0, 0x05, 0x0171, 0x0427, 1, 1) < RP_KNOWN, "name bonuses never reach the known tier");
    CHECK(sc("Amazon Fire TV Remote Keyboard Thing", 0, 0x19, 0, 0, 1, 1) < RP_ADOPT_IDLE, "name bonuses never lift a software device out of the watch tier");

    /* issue #5: the remote is away (the Cube sleeps), amzkeyboard is there */
    CHECK(!rp_take_idle(-1, amzkeyboard), "nothing held: amzkeyboard is not taken as a stand-in");
    CHECK(rp_take_idle(-1, remote), "nothing held: the remote is taken at once when it returns");
    CHECK(rp_take_idle(-1, other_name) && rp_take_idle(-1, ir), "nothing held: hardware guesses are taken at once");
    /* an older remapd, or a guess, holds the wrong device when the remote returns */
    CHECK(rp_take_idle(amzkeyboard, remote), "a better device replaces a held guess");
    CHECK(rp_take_idle(ir, other_name), "a Bluetooth remote replaces an infrared guess");
    CHECK(!rp_take_idle(remote, other_name) && !rp_take_idle(remote, remote), "nothing replaces the known remote unasked");
    CHECK(!rp_take_idle(other_name, other_name), "an equal device does not replace the held one");

    /* following the presses */
    CHECK(rp_follow(-1, amzkeyboard, 0), "nothing held: presses on a watched device make it the remote (the phone app)");
    CHECK(rp_follow(amzkeyboard, other_name, 0), "presses on the real remote win it back from a stand-in");
    CHECK(rp_follow(other_name, amzkeyboard, 0), "a guess is left when buttons are pressed elsewhere");
    CHECK(!rp_follow(remote, amzkeyboard, 0), "the known remote is never left for a guess");
    CHECK(!rp_follow(remote, remote, 0), "nor for a second remote of the same kind");
    CHECK(!rp_follow(other_name, other_name, 1), "never to another node of the same device");
    CHECK(!rp_follow(-1, 0, 0), "not to a device that is not remote-like");

    /* which presses count */
    CHECK(rp_remote_key(103) && rp_remote_key(108) && rp_remote_key(105) && rp_remote_key(106), "arrows");
    CHECK(rp_remote_key(96) && rp_remote_key(28), "OK (KPENTER on Fire TV remotes, ENTER on others)");
    CHECK(rp_remote_key(158) && rp_remote_key(172) && rp_remote_key(139) && rp_remote_key(1), "Back, Home, Menu, Esc");
    CHECK(rp_remote_key(164) && rp_remote_key(744), "media and app buttons");
    CHECK(!rp_remote_key(16) && !rp_remote_key(57) && !rp_remote_key(2) && !rp_remote_key(42), "typing says nothing: q, space, 1, shift");
    CHECK(!rp_remote_key(0) && !rp_remote_key(-1), "no key");

    if (!fails) printf("remote_pick: OK\n");
    return fails ? 1 : 0;
}
