package com.clean.stickman.core;

/**
 * What listen mode does with a heard key (pure, host-tested). learnSlot is the unlearned button being taught,
 * or null for plain listening.
 */
public final class ListenDecision {
    private ListenDecision() {}

    public static final int BACK = 158;
    /** Keep listening (nothing usable heard). */
    public static final int IGNORE = 0;
    /** Back: stop listening. */
    public static final int CANCEL = 1;
    /** Key not on the drawing while teaching: it becomes learnSlot's code. */
    public static final int ASSIGN = 2;
    /** (3 was "offer an unlearned slot"; a heard key is now always usable as itself, see EXTRA.) */
    /** Key on the drawing, plain listening: open its editor. */
    public static final int PICK = 4;
    /** Key on the drawing while teaching: it can't be this button; say so. */
    public static final int ALREADY = 5;
    /** Key not on the drawing, plain listening: open the key's own editor (any id works, whatever the remote). */
    public static final int EXTRA = 6;

    public static int decide(int code, RemoteLayout.Btn[] bs, String learnSlot) {
        if (code <= 0) return IGNORE;
        if (code == BACK) return CANCEL;
        boolean drawn = RemoteLayout.indexOf(bs, code) >= 0;
        if (learnSlot != null) {
            if (drawn) return ALREADY;
            int i = RemotePresets.slotIndex(bs, learnSlot);
            return i >= 0 && bs[i].code == 0 ? ASSIGN : IGNORE;
        }
        if (drawn) return PICK;
        return EXTRA;   // the drawing is only a picture: whatever the remote sends is a button the owner can change
    }
}
