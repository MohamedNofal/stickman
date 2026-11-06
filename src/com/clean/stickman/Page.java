package com.clean.stickman;

import android.view.KeyEvent;
import android.view.View;

/** One sidebar section. build() is called once; the view is kept while the activity lives. */
public abstract class Page {
    public abstract String title();
    /** One short line under the title saying what the page is for (null = none). ApplyPage shows it. */
    public String subtitle() { return null; }
    public abstract View build(MainActivity a);
    public void onShow() {}
    public void onHide() {}
    /** Called every ~400 ms while shown. */
    public void onTick() {}
    /** Sees every key event first while shown; return true to swallow it. */
    public boolean onKey(KeyEvent e) { return false; }
    /** Every bridge result (all pages get all results; filter by cmd). */
    public void onResult(String cmd, String output, boolean ok) {}
}
