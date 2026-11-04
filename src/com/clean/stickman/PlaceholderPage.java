package com.clean.stickman;

import android.view.View;
import android.widget.LinearLayout;

/** Stand-in for sections a later build fills in (Remote, Speed, Sleep, Privacy, Debloat, Shortcuts). */
public class PlaceholderPage extends Page {
    private final String title;

    public PlaceholderPage(String title) { this.title = title; }

    @Override public String title() { return title; }

    @Override public View build(MainActivity a) {
        LinearLayout l = Theme.column(a);
        l.addView(Theme.title(a, title));
        l.addView(Theme.text(a, "Coming in this build.", 16, Theme.MUTED));
        return l;
    }
}
