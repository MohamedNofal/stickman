package com.clean.stickman;

import android.widget.Button;
import android.widget.LinearLayout;

import com.clean.stickman.core.Actions;
import com.clean.stickman.core.Catalog;
import com.clean.stickman.core.Commands;

/** Grid of one-press tiles into Amazon's hidden settings screens, plus Screen off. */
public class ShortcutsPage extends ApplyPage {
    static final int COLS = 3;

    @Override public String title() { return "Shortcuts"; }

    @Override public android.view.View build(MainActivity act) {
        LinearLayout l = start(act);
        LinearLayout grid = card(l, "Amazon settings screens", "Jump straight to Amazon's settings screens, including hidden ones. "
            + "Any of them can live on a remote button too: Remote → the button → Press → A Stickman action → Open Amazon screen.");
        int n = Catalog.SHORTCUTS.length + 1;
        LinearLayout row = null;
        for (int i = 0; i < n; i++) {
            if (i % COLS == 0) { row = Theme.row(a); grid.addView(row, new LinearLayout.LayoutParams(-1, -2)); }
            final boolean off = i == n - 1;
            final String label = off ? "Screen off" : Catalog.SHORTCUTS[i][0];
            final String target = off ? null : Catalog.SHORTCUTS[i][1];
            Button b = Theme.button(a, label);
            b.setGravity(android.view.Gravity.CENTER);
            b.setMinHeight(Theme.dp(a, 64));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1f);
            lp.setMargins(i % COLS == 0 ? 0 : Theme.dp(a, 5), Theme.dp(a, 5), i % COLS == COLS - 1 ? 0 : Theme.dp(a, 5), Theme.dp(a, 5));
            b.setOnClickListener(v -> {
                if (!stickdOn()) return;
                if (off) send(Commands.act(Actions.SCREEN_OFF, null), "Screen off", "Turning the screen off…");
                else send(Commands.act(Actions.OPEN_SCREEN, target), label, "Opening " + label + "…");
            });
            row.addView(b, lp);
        }
        // pad the last row so tiles keep their width
        while (row != null && row.getChildCount() < COLS) row.addView(new android.view.View(a), new LinearLayout.LayoutParams(0, 1, 1f));
        return Theme.scroll(a, l);
    }

    @Override String describe(String kind, String out) {
        return "Screen off".equals(kind) ? "Screen off sent. Press any button to bring it back." : "Opened " + kind + ".";
    }
}
