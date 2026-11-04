package com.clean.stickman;

import android.view.View;

/** One sidebar section. build() is called once; the view is kept while the activity lives. */
public abstract class Page {
    public abstract String title();
    public abstract View build(MainActivity a);
    public void onShow() {}
    public void onHide() {}
    /** Called every ~400 ms while shown. */
    public void onTick() {}
    /** Every bridge result (all pages get all results; filter by cmd). */
    public void onResult(String cmd, String output, boolean ok) {}
}
