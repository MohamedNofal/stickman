package com.clean.stickman;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * Stickman's own dialog: dark PANEL box with a WOOD outline, TEXT colours and the ACCENT focus ring on every
 * list row and action button. Replaces the stock (grey/teal on Fire OS) AlertDialog everywhere.
 * Kept inside a title-safe area (90% of the screen height, centred). DOWN from the body always lands on the
 * default action button, never on whichever button happens to be geometrically nearest.
 *
 * Usage: new ThemedDialog(a).title(..).message(..).items(..) / .view(..) / .multi(..)
 *        .button("Save", r).cancel("Cancel").focusButton(0).show();
 * Action buttons are laid out in the order added, left to right, spanning the body's width.
 */
public class ThemedDialog {
    public interface Pick { void picked(int i); }

    final Context c;
    final Dialog d;
    final LinearLayout box, body, buttons;
    final ScrollView scroll;
    TextView titleView;
    final List<Button> btns = new ArrayList<>();
    final List<Button> rows = new ArrayList<>();
    View firstFocus;
    int defaultBtn = -1;
    boolean wide;

    public ThemedDialog(Context ctx) {
        c = ctx;
        d = new Dialog(c, android.R.style.Theme_DeviceDefault_Dialog_NoActionBar);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        final int maxH = Math.round(c.getResources().getDisplayMetrics().heightPixels * 0.9f);
        box = new LinearLayout(c) {
            @Override protected void onMeasure(int w, int h) {
                int size = MeasureSpec.getSize(h);
                if (MeasureSpec.getMode(h) == MeasureSpec.UNSPECIFIED || size > maxH) h = MeasureSpec.makeMeasureSpec(maxH, MeasureSpec.AT_MOST);
                super.onMeasure(w, h);
            }

            @Override public View focusSearch(View focused, int dir) {
                View n = super.focusSearch(focused, dir);
                // entering the button row from the body: the default action, not the nearest button
                if (dir == View.FOCUS_DOWN && n != null && n.getParent() == buttons && focused != null
                        && !MainActivity.isIn(focused, buttons) && defaultButton() != null) return defaultButton();
                return n;
            }
        };
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(Theme.rect(c, Theme.PANEL, Theme.WOOD, 2));
        int p = Theme.dp(c, 24);
        box.setPadding(p, Theme.dp(c, 20), p, Theme.dp(c, 16));

        body = Theme.column(c);
        scroll = new ScrollView(c);
        scroll.addView(body);
        scroll.setFocusable(false);
        // wrap_content + weight: shrinks only when the dialog would be taller than the safe area
        box.addView(scroll, new LinearLayout.LayoutParams(-1, -2, 1f));

        buttons = Theme.row(c);
        buttons.setPadding(0, Theme.dp(c, 12), 0, 0);
        box.addView(buttons, new LinearLayout.LayoutParams(-1, -2));
        d.setContentView(box);
    }

    public ThemedDialog title(String s) {
        titleView = Theme.text(c, s, 22, Theme.TEXT);
        titleView.setTypeface(Typeface.DEFAULT_BOLD);
        titleView.setPadding(0, 0, 0, Theme.dp(c, 10));
        box.addView(titleView, 0);
        return this;
    }

    public ThemedDialog message(String s) {
        TextView t = Theme.text(c, s, 16, Theme.TEXT);
        t.setLineSpacing(Theme.dp(c, 2), 1f);
        t.setPadding(0, 0, 0, Theme.dp(c, 8));
        body.addView(t);
        return this;
    }

    /** Any custom content (e.g. a text field or the button editor's rows). */
    public ThemedDialog view(View v) {
        body.addView(v, new LinearLayout.LayoutParams(-1, -2));
        wide = true;
        return this;
    }

    /** Wider box (for the button editor). */
    public ThemedDialog wide() { wide = true; return this; }

    /** Full-width row button in the body; OK runs r (the dialog stays open). */
    public Button row(String label, View.OnClickListener l) {
        Button b = Theme.button(c, label);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, Theme.dp(c, 3), 0, Theme.dp(c, 3));
        b.setOnClickListener(l);
        body.addView(b, lp);
        rows.add(b);
        return b;
    }

    /** A list: choosing a row closes the dialog and reports its index. checked (-1 = none) is marked and focused. */
    public ThemedDialog items(String[] labels, int checked, final Pick pick) {
        for (int i = 0; i < labels.length; i++) {
            final int idx = i;
            Button b = row((i == checked ? "✓  " : "") + labels[i], v -> { d.dismiss(); pick.picked(idx); });
            if (i == checked) { b.setSelected(true); firstFocus = b; }
        }
        return this;
    }

    public ThemedDialog items(String[] labels, Pick pick) { return items(labels, -1, pick); }

    /** Multi-choice list: OK toggles on[i] (shown as ✓ and the WOOD "selected" fill). */
    public ThemedDialog multi(final String[] labels, final boolean[] on) {
        for (int i = 0; i < labels.length; i++) {
            final int idx = i;
            final Button[] self = new Button[1];
            self[0] = row("", v -> { on[idx] = !on[idx]; mark(self[0], labels[idx], on[idx]); });
            mark(self[0], labels[i], on[i]);
        }
        return this;
    }

    static void mark(Button b, String label, boolean on) {
        b.setText((on ? "✓  " : "      ") + label);
        b.setSelected(on);
    }

    /** Action button that runs r (may be null) and closes the dialog. */
    public ThemedDialog button(String label, final Runnable r) {
        addButton(label, v -> { d.dismiss(); if (r != null) r.run(); });
        return this;
    }

    /** Action button that does NOT close the dialog (r decides, e.g. after validating input). */
    public Button buttonKeep(String label, View.OnClickListener l) { return addButton(label, l); }

    public ThemedDialog cancel(String label) { return button(label, null); }

    Button addButton(String label, View.OnClickListener l) {
        Button b = Theme.button(c, label);
        b.setGravity(Gravity.CENTER);
        b.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1f);
        int gap = Theme.dp(c, 10);
        if (!btns.isEmpty()) ((LinearLayout.LayoutParams) btns.get(btns.size() - 1).getLayoutParams()).rightMargin = gap;
        lp.setMargins(0, 0, 0, 0);
        buttons.addView(b, lp);
        btns.add(b);
        return b;
    }

    /** Which action button gets focus when entering the button row (and first, if nothing else asks for it). */
    public ThemedDialog focusButton(int i) { defaultBtn = i; return this; }

    /** Focus v when the dialog opens. */
    public ThemedDialog focus(View v) { firstFocus = v; return this; }

    Button defaultButton() {
        if (btns.isEmpty()) return null;
        return btns.get(defaultBtn >= 0 && defaultBtn < btns.size() ? defaultBtn : 0);
    }

    public ThemedDialog onDismiss(final Runnable r) {
        d.setOnDismissListener(x -> r.run());
        return this;
    }

    public void dismiss() { d.dismiss(); }

    public ThemedDialog show() {
        if (btns.isEmpty() && rows.isEmpty()) cancel("OK");
        Window w = d.getWindow();
        if (w != null) w.setBackgroundDrawable(new ColorDrawable(0));
        d.show();
        if (w != null) {
            int sw = c.getResources().getDisplayMetrics().widthPixels;
            int width = Math.min(sw * 9 / 10, Theme.dp(c, wide ? 620 : 520));
            w.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT);
            w.setGravity(Gravity.CENTER);
            w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            w.setDimAmount(0.6f);
        }
        View f = firstFocus;
        if (f == null && defaultBtn >= 0) f = defaultButton();
        if (f == null && !rows.isEmpty()) f = rows.get(0);
        if (f == null) f = defaultButton();
        if (f != null) {
            final View ff = f;
            ff.requestFocus();
            ff.post(() -> { ff.requestFocus(); ff.requestRectangleOnScreen(new android.graphics.Rect(0, 0, ff.getWidth(), ff.getHeight()), true); });
        }
        return this;
    }

    /** One-button information dialog. */
    public static void message(Context c, String title, String msg) {
        new ThemedDialog(c).title(title).message(msg).button("OK", null).show();
    }

    /** Warning/confirm: [action] [Cancel], with focus on Cancel so OK can't fire the risky action by accident. */
    public static void confirm(Context c, String title, String msg, String action, Runnable r) {
        new ThemedDialog(c).title(title).message(msg).button(action, r).cancel("Cancel").focusButton(1).show();
    }
}
