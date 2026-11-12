package com.clean.stickman;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.clean.stickman.core.RamHist;
import com.clean.stickman.core.ResultText;
import com.clean.stickman.core.TaskSnapshot;
import com.clean.stickman.core.Units;

/**
 * The Tasks page's tile dialog: label, "in the background · running 42 min" (package underneath), RAM / CPU /
 * processes, a 60 s RAM trend and one line on what the buttons do, with End app (default focus) · Always kill ·
 * Protect · Open · Cancel. A details-only variant (system strip) has just Close. While open, {@link #update}
 * refreshes the numbers and the trend from each new snapshot.
 */
public class TaskDialog {
    /** What the page does for each button; the dialog closes before it is called. */
    public interface Actions {
        void end(String pkg);
        void alwaysKill(String pkg, boolean on);
        void protect(String pkg, boolean on);
        void open(String pkg);
    }

    /** Which buttons the dialog shows and how. */
    public static final class Options {
        boolean detailsOnly;
        /** Non-null: End app is disabled and the reason is shown ("protected", "playing", ...). */
        String endBlocked;
        /** Stickman spares this app by itself; End asks "Are you sure?" first. */
        boolean guarded;
        boolean alwaysKill, protectedPkg, canAlwaysKill = true, canProtect = true, canOpen = true;
    }

    final String pkg;
    final ThemedDialog d;
    final TextView sub, stats, trend;
    final Sparkline spark;
    final Context c;
    boolean open = true;

    TaskDialog(Context ctx, String label, String pkg, TaskSnapshot.App app, RamHist hist, Options o,
               final Actions act, Runnable onDismiss) {
        c = ctx;
        this.pkg = pkg;
        d = new ThemedDialog(c).title(label);
        LinearLayout v = Theme.column(c);
        sub = Theme.text(c, "", 15, Theme.MUTED);
        v.addView(sub);
        stats = Theme.text(c, "", 18, Theme.TEXT);
        stats.setPadding(0, Theme.dp(c, 10), 0, Theme.dp(c, 6));
        v.addView(stats);
        spark = new Sparkline(c);
        v.addView(spark, new LinearLayout.LayoutParams(-1, Theme.dp(c, 64)));
        trend = Theme.text(c, "", 13, Theme.MUTED);
        trend.setPadding(0, Theme.dp(c, 4), 0, 0);
        v.addView(trend);
        if (o.endBlocked != null && !o.detailsOnly) {
            TextView why = Theme.text(c, "Can't end: " + reasonText(o.endBlocked), 14, Theme.ACCENT);
            why.setPadding(0, Theme.dp(c, 6), 0, 0);
            v.addView(why);
        } else if (o.guarded && !o.detailsOnly) {
            TextView why = Theme.text(c, "Stickman never closes this one by itself (End all, auto-close and Boost skip it). "
                + "You can still end it here.", 14, Theme.MUTED);
            why.setPadding(0, Theme.dp(c, 6), 0, 0);
            v.addView(why);
        }
        if (!o.detailsOnly) {
            TextView help = Theme.text(c, "End: close it now  ·  Always kill ⚡: close it every time you leave it  ·  "
                + "Protect ★: never close it", 13, Theme.MUTED);
            help.setPadding(0, Theme.dp(c, 8), 0, 0);
            v.addView(help);
        }
        d.view(v);
        update(app, hist);

        if (o.detailsOnly) {
            d.button("Close", null);
        } else {
            int focus = 0;
            Button end = btn("End app", () -> act.end(pkg));
            if (o.endBlocked != null) { disable(end); focus = -1; }
            Button ak = btn(o.alwaysKill ? "Stop always killing" : "Always kill", () -> act.alwaysKill(pkg, !o.alwaysKill));
            if (!o.canAlwaysKill) disable(ak);
            Button pr = btn(o.protectedPkg ? "Unprotect" : "Protect", () -> act.protect(pkg, !o.protectedPkg));
            if (!o.canProtect) disable(pr);
            Button op = btn("Open", () -> act.open(pkg));
            if (!o.canOpen) disable(op);
            d.cancel("Cancel");
            // End blocked: Cancel is the safe default
            d.focusButton(focus >= 0 ? 0 : d.btns.size() - 1);
            for (Button b : d.btns) { b.setTextSize(14); b.setPadding(Theme.dp(c, 6), Theme.dp(c, 8), Theme.dp(c, 6), Theme.dp(c, 8)); }
        }
        d.onDismiss(() -> { open = false; if (onDismiss != null) onDismiss.run(); });
        d.show();
    }

    Button btn(String label, final Runnable r) {
        final ThemedDialog dd = d;
        return d.addButton(label, x -> { dd.dismiss(); r.run(); });
    }

    static void disable(Button b) {
        b.setEnabled(false);
        b.setFocusable(false);
    }

    static String reasonText(String why) { return ResultText.reason(why); }

    /** New numbers for this dialog's package (app null = gone from the snapshot). */
    void update(TaskSnapshot.App app, RamHist hist) {
        if (app == null) {
            sub.setText("Not running any more\n" + pkg);
        } else {
            String st = ResultText.state(app.state);
            sub.setText(Character.toUpperCase(st.charAt(0)) + st.substring(1) + " · running " + ResultText.duration(app.upSec) + "\n" + pkg);
            boolean agg = TaskMapView.SYS_OTHER.equals(app.pkg);
            stats.setText("RAM " + Units.mb(app.rssKb) + "   ·   CPU " + app.cpuPct + " %   ·   "
                + app.procs + (agg ? (app.procs == 1 ? " package" : " packages") : (app.procs == 1 ? " process" : " processes")));
        }
        spark.set(hist);
        if (hist == null || hist.size() < 2) trend.setText("RAM over the last 60 s: collecting…");
        else trend.setText("RAM over the last " + hist.spanSec() + " s: " + Units.mb(hist.get(0)) + " → " + Units.mb(hist.get(hist.size() - 1))
            + "   (low " + Units.mb(hist.min()) + ", high " + Units.mb(hist.max()) + ")");
    }

    /** Tiny RAM trend line; all drawing state preallocated, onDraw does not allocate. */
    static final class Sparkline extends View {
        final float[] vals = new float[RamHist.SIZE], ago = new float[RamHist.SIZE];
        int n;
        /** The trend's x axis: the newest sample at the right edge, this many ms back at the left. */
        static final float WINDOW_MS = (RamHist.SIZE - 1) * 2000f;
        final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG), area = new Paint(Paint.ANTI_ALIAS_FLAG), base = new Paint();
        final Path lp = new Path(), ap = new Path();
        final float dp;

        Sparkline(Context c) {
            super(c);
            dp = c.getResources().getDisplayMetrics().density;
            line.setStyle(Paint.Style.STROKE);
            line.setStrokeWidth(2.5f * dp);
            line.setStrokeJoin(Paint.Join.ROUND);
            line.setColor(Theme.ACCENT);
            area.setStyle(Paint.Style.FILL);
            area.setColor((Theme.ACCENT & 0xFFFFFF) | 0x33000000);
            base.setColor(0xFF3A3028);
            base.setStrokeWidth(dp);
            setFocusable(false);
        }

        void set(RamHist h) {
            n = h == null ? 0 : h.size();
            for (int i = 0; i < n; i++) { vals[i] = h.get(i); ago[i] = Math.min(WINDOW_MS, h.agoMs(i)); }
            invalidate();
        }

        @Override protected void onDraw(Canvas cv) {
            float w = getWidth(), h = getHeight(), pad = 3 * dp;
            cv.drawLine(0, h - dp / 2, w, h - dp / 2, base);
            if (n < 2) return;
            float lo = vals[0], hi = vals[0];
            for (int i = 1; i < n; i++) { lo = Math.min(lo, vals[i]); hi = Math.max(hi, vals[i]); }
            // flat-ish lines stay in the middle instead of amplifying noise: at least a 10 % band
            float span = Math.max(hi - lo, Math.max(hi * 0.1f, 1));
            float mid = (hi + lo) / 2, bottom = mid - span / 2;
            float perMs = (w - 2 * pad) / WINDOW_MS;   // placed by real sample time, newest at the right edge
            lp.reset();
            ap.reset();
            for (int i = 0; i < n; i++) {
                float x = w - pad - ago[i] * perMs;
                float y = pad + (h - 2 * pad) * (1 - (vals[i] - bottom) / span);
                if (i == 0) { lp.moveTo(x, y); ap.moveTo(x, h); ap.lineTo(x, y); }
                else { lp.lineTo(x, y); ap.lineTo(x, y); }
            }
            ap.lineTo(w - pad - ago[n - 1] * perMs, h);
            ap.close();
            cv.drawPath(ap, area);
            cv.drawPath(lp, line);
        }
    }

    /** Small helper for the label/MB lines of strip tiles. */
    static TextView line(Context c, String s, float sp, int color) {
        TextView t = Theme.text(c, s, sp, color);
        t.setSingleLine(true);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        t.setGravity(Gravity.CENTER_HORIZONTAL);
        return t;
    }
}
