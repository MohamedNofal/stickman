package com.clean.stickman;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Dark wood/orange look shared by every page. Framework views only. */
public final class Theme {
    private Theme() {}

    public static final int BG = 0xFF14110E;
    public static final int PANEL = 0xFF1F1A15;
    public static final int WOOD = 0xFFC68A4E;
    public static final int ACCENT = 0xFFFF9A3C;
    public static final int TEXT = 0xFFF3EDE6;
    public static final int MUTED = 0xFFA39A90;

    public static int dp(Context c, float v) { return Math.round(v * c.getResources().getDisplayMetrics().density); }

    private static GradientDrawable rect(Context c, int fill, int stroke, float strokeDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(c, 8));
        if (strokeDp > 0) g.setStroke(dp(c, strokeDp), stroke);
        return g;
    }

    public static TextView title(Context c, String s) {
        TextView t = text(c, s, 24, TEXT);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(0, 0, 0, dp(c, 12));
        return t;
    }

    public static TextView text(Context c, String s, float sp, int color) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        return t;
    }

    /** Focusable button; focus draws an ACCENT outline, "selected" (current choice) a WOOD fill. */
    public static Button button(Context c, String s) {
        Button b = new Button(c);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextSize(16);
        b.setTextColor(TEXT);
        b.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        b.setPadding(dp(c, 16), dp(c, 8), dp(c, 16), dp(c, 8));
        b.setFocusable(true);
        b.setStateListAnimator(null);
        StateListDrawable sl = new StateListDrawable();
        int sel = 0xFF5A3D22;
        sl.addState(new int[] { android.R.attr.state_focused, android.R.attr.state_selected }, rect(c, sel, ACCENT, 3));
        sl.addState(new int[] { android.R.attr.state_focused }, rect(c, 0xFF2C241C, ACCENT, 3));
        sl.addState(new int[] { android.R.attr.state_pressed }, rect(c, 0xFF2C241C, ACCENT, 3));
        sl.addState(new int[] { android.R.attr.state_selected }, rect(c, sel, WOOD, 1));
        sl.addState(new int[] {}, rect(c, PANEL, 0xFF3A3028, 1));
        b.setBackground(sl);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.setMargins(0, dp(c, 4), dp(c, 8), dp(c, 4));
        b.setLayoutParams(lp);
        return b;
    }

    public static LinearLayout card(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setBackground(rect(c, PANEL, 0xFF3A3028, 1));
        int p = dp(c, 16);
        l.setPadding(p, dp(c, 12), p, dp(c, 12));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, 0, 0, dp(c, 12));
        l.setLayoutParams(lp);
        return l;
    }

    /** Card heading in WOOD. */
    public static TextView heading(Context c, String s) {
        TextView t = text(c, s, 14, WOOD);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    public static LinearLayout row(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    public static LinearLayout column(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    public static ScrollView scroll(Context c, View child) {
        ScrollView s = new ScrollView(c);
        s.setFillViewport(true);
        s.addView(child);
        return s;
    }
}
