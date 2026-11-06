package com.clean.stickman;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;

import com.clean.stickman.core.Brand;
import com.clean.stickman.core.CollapseState;
import com.clean.stickman.core.TaskSnapshot;
import com.clean.stickman.core.TileNav;
import com.clean.stickman.core.Treemap;
import com.clean.stickman.core.Units;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Tasks page memory map: one tile per (non-system) app, sized by RAM (or CPU / equal for Name), in its brand colour.
 * Squarified (core.Treemap) with hysteresis: rects only change when the app set changes or a weight moves > 5 %, and
 * then tween over 400 ms (clock-driven, so it runs with system animations off); new tiles fade in. D-pad moves a focus
 * ring between tiles (core.TileNav), OK picks on key-up. All text and geometry is computed in setSnapshot / onSizeChanged,
 * so onDraw does not allocate. Call every setter on the main thread.
 */
public class TaskMapView extends View {
    public enum Sort { RAM, CPU, NAME }

    public interface OnPick { void pick(String key); }

    /** stickd's aggregate of small system packages (state sys; procs = package count). */
    public static final String SYS_OTHER = "_sys_other";
    public static final String MORE = Treemap.MORE;
    static final double RELAYOUT = 0.05;
    /**
     * Sort by CPU: tiles are sized by a 10 s moving average (core.CpuAvg) and re-laid out only past a 10 % share
     * change; measured on the stick, raw per-sample CPU with 5 % re-laid the map out 27 times a minute.
     */
    static final double RELAYOUT_CPU = 0.10;
    final com.clean.stickman.core.CpuAvg cpuAvg = new com.clean.stickman.core.CpuAvg();
    static final long TWEEN_MS = 400;
    static final int MORE_FILL = 0xFF3A3028, PILL_BG = 0x73000000, BADGE_BG = 0xB3000000;

    /** Focus glow: stroke widths (dp) and alphas, outermost first. */
    static final float[] GLOW_W = { 9, 6.5f };
    static final int[] GLOW_A = { 0x22, 0x44 };

    public OnPick onPick;

    /** One drawn tile; text/geometry relative to the tile's top-left, computed for its target size. */
    static final class Tile {
        String key;
        int color, ink;
        float fx, fy, fw, fh;   // tween start
        float tx, ty, tw, th;   // target
        float x, y, w, h;       // current
        boolean fadeIn;
        boolean showText;
        String name, sub, pill, badge;
        float nameSize, subSize, pillSize, badgeSize;
        float pad, nameY, subY, pillL, pillT, pillR, pillB, badgeCx, badgeCy, badgeR;
    }

    final float dp;
    final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), shade = new Paint(Paint.ANTI_ALIAS_FLAG),
        stroke = new Paint(Paint.ANTI_ALIAS_FLAG), text = new Paint(Paint.ANTI_ALIAS_FLAG);
    final RectF rect = new RectF();
    final Matrix shadeMatrix = new Matrix();
    final float corner, gap, inset;

    ArrayList<Tile> tiles = new ArrayList<>();
    /** Target rects, same order as tiles (TileNav input). */
    List<Treemap.Rect> rects = new ArrayList<>();
    /** Order of the last layout's keys, kept across samples so tiles don't swap places. */
    List<String> order = new ArrayList<>();
    Sort sort = Sort.RAM, laidSort;
    TaskSnapshot snap;
    final List<TaskSnapshot.App> sys = new ArrayList<>();
    final List<TaskSnapshot.App> hidden = new ArrayList<>();
    final Map<String, TaskSnapshot.App> apps = new HashMap<>();
    Map<String, String> labels = new HashMap<>();
    Set<String> protectedPkgs = new HashSet<>(), alwaysKill = new HashSet<>();

    int focus = -1;
    String focusKey;
    /**
     * The key the user last chose (D-pad / touch). Survives that app folding into "+N more" (focus shows on MORE
     * meanwhile) so focus returns to it when it grows back out.
     */
    String wantKey;
    /** Last "+N more" membership, for the sticky CollapseState hysteresis. */
    Set<String> prevHidden = new HashSet<>();
    boolean okDown, paused;
    /** Shown centred while there are no tiles (waiting, paused, off...), so an empty map says why. */
    String emptyText;
    long tweenStart;
    boolean tweening;
    float tweenT = 1f;

    public TaskMapView(Context c) {
        super(c);
        dp = c.getResources().getDisplayMetrics().density;
        corner = 6 * dp;
        gap = 4 * dp;
        inset = 9 * dp;   // room for the focus glow at the map's edges
        setFocusable(true);
        setFocusableInTouchMode(true);
        stroke.setStyle(Paint.Style.STROKE);
        // one shared darkening ramp over 0..1, scaled to each tile with a local matrix
        shade.setShader(new LinearGradient(0, 0, 0, 1, 0x00000000, 0x40000000, Shader.TileMode.CLAMP));
        setContentDescription("Memory map");
    }

    // ---- data ----

    /** Display name of pkg: the page's label, "Other system" for the aggregate, else the last package segment. */
    public String labelOf(String pkg) {
        if (SYS_OTHER.equals(pkg)) return "Other system";
        String l = labels.get(pkg);
        if (l != null && !l.isEmpty()) return l;
        int d = pkg.lastIndexOf('.');
        String s = d >= 0 && d < pkg.length() - 1 ? pkg.substring(d + 1) : pkg;
        return s.isEmpty() ? pkg : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    public void setLabels(Map<String, String> l) {
        labels = l == null ? new HashMap<String, String>() : new HashMap<>(l);
        refreshText();
    }

    public void setMarks(Set<String> protectedPkgs, Set<String> alwaysKill) {
        this.protectedPkgs = protectedPkgs == null ? new HashSet<String>() : new HashSet<>(protectedPkgs);
        this.alwaysKill = alwaysKill == null ? new HashSet<String>() : new HashSet<>(alwaysKill);
        refreshText();
    }

    /** Paused: finish any running tween now; later snapshots (e.g. a sort change) apply without animating. */
    public void setPaused(boolean p) {
        paused = p;
        if (p && tweening) { finishTween(); invalidate(); }
    }

    /** Text drawn in the middle while the map has no tiles; null for none. */
    public void setEmptyText(String s) {
        if (s == null ? emptyText == null : s.equals(emptyText)) return;
        emptyText = s;
        if (tiles.isEmpty()) invalidate();
    }

    public TaskSnapshot snapshot() { return snap; }
    /** System apps (state sys), largest first; not in the map. */
    public List<TaskSnapshot.App> systemApps() { return new ArrayList<>(sys); }
    /** Apps folded into the "+N more" tile, largest first. */
    public List<TaskSnapshot.App> hiddenApps() { return new ArrayList<>(hidden); }
    /** The snapshot's app for a tile key, or null (MORE / unknown). */
    public TaskSnapshot.App app(String key) { return key == null ? null : apps.get(key); }

    public void setSnapshot(TaskSnapshot s, Sort sortBy) {
        if (sortBy == null) sortBy = Sort.RAM;
        snap = s;
        sort = sortBy;
        cpuAvg.add(s);   // every snapshot (whatever the sort), so Sort by CPU starts with a full window
        sys.clear();
        apps.clear();
        List<TaskSnapshot.App> user = new ArrayList<>();
        if (s != null) {
            for (TaskSnapshot.App a : s.apps) {
                apps.put(a.pkg, a);
                if ("sys".equals(a.state)) sys.add(a); else user.add(a);
            }
        }
        Collections.sort(sys, TaskSnapshot.App.BY_RSS);
        layoutTiles(user, getWidth() > 0 && getHeight() > 0 && !paused);
    }

    double weight(TaskSnapshot.App a) {
        switch (sort) {
            case CPU: return cpuAvg.avg(a.pkg, a.cpuPct) + 1;   // smoothed; idle apps still get a (small) tile
            case NAME: return 1;
            default: return Math.max(1, a.rssKb);
        }
    }

    Comparator<TaskSnapshot.App> comparator() {
        if (sort == Sort.NAME) return new Comparator<TaskSnapshot.App>() {
            public int compare(TaskSnapshot.App a, TaskSnapshot.App b) {
                int c = labelOf(a.pkg).compareToIgnoreCase(labelOf(b.pkg));
                return c != 0 ? c : a.pkg.compareTo(b.pkg);
            }
        };
        if (sort == Sort.CPU) return new Comparator<TaskSnapshot.App>() {
            public int compare(TaskSnapshot.App a, TaskSnapshot.App b) {
                if (a.cpuPct != b.cpuPct) return a.cpuPct > b.cpuPct ? -1 : 1;
                return TaskSnapshot.App.BY_RSS.compare(a, b);
            }
        };
        return TaskSnapshot.App.BY_RSS;
    }

    /** Re-layouts since the view was made (the "tasks relayout" log line counts the same). */
    int relayouts;

    boolean needsRelayout(List<String> keys, List<Double> weights) {
        return Treemap.needsRelayout(rects, keys, weights, sort == Sort.CPU ? RELAYOUT_CPU : RELAYOUT);
    }

    /** Collapse + (maybe) re-layout; rebuilds tiles matched by key, tweening from where they are drawn now. */
    void layoutTiles(List<TaskSnapshot.App> user, boolean animate) {
        // order: previous layout order for known apps, new apps appended in sort order (Name: always alphabetical)
        Collections.sort(user, comparator());
        List<TaskSnapshot.App> ordered = new ArrayList<>(user.size());
        if (sort == laidSort && sort != Sort.NAME) {
            Map<String, TaskSnapshot.App> byPkg = new HashMap<>();
            for (TaskSnapshot.App a : user) byPkg.put(a.pkg, a);
            for (String k : order) { TaskSnapshot.App a = byPkg.remove(k); if (a != null) ordered.add(a); }
            for (TaskSnapshot.App a : user) if (byPkg.containsKey(a.pkg)) ordered.add(a);
        } else {
            ordered.addAll(user);
        }
        boolean sortChanged = sort != laidSort;
        laidSort = sort;

        List<String> keys = new ArrayList<>(ordered.size());
        List<Double> weights = new ArrayList<>(ordered.size());
        for (TaskSnapshot.App a : ordered) { keys.add(a.pkg); weights.add(weight(a)); }
        if (sortChanged) prevHidden = new HashSet<>();   // weights mean something else now
        // keep-top-10 first, then sticky membership (3.5 % / 4.5 %) so an app hovering near 4 % doesn't flap
        Treemap.Collapsed col = CollapseState.next(prevHidden, keys, weights, CollapseState.LO, CollapseState.HI);
        prevHidden = new HashSet<>(col.hidden);
        hidden.clear();
        for (String k : col.hidden) hidden.add(apps.get(k));
        Collections.sort(hidden, TaskSnapshot.App.BY_RSS);
        order = new ArrayList<>(keys);   // full order, hidden ones included, so they keep their place if they grow

        float mw = getWidth() - 2 * inset, mh = getHeight() - 2 * inset;
        boolean grid = sort == Sort.NAME;   // alphabetical rows: equal cells in reading order
        boolean relayout = sortChanged || mw <= 0 || mh <= 0
            || (grid ? Treemap.gridChanged(rects, col.keys) : needsRelayout(col.keys, col.weights));
        if (relayout) {
            relayouts++;
            android.util.Log.i("Stickman", "tasks relayout sort=" + sort + " n=" + col.keys.size());
            List<Treemap.Rect> next = mw <= 0 || mh <= 0 ? new ArrayList<Treemap.Rect>()
                : grid ? Treemap.grid(col.keys, inset, inset, mw, mh)
                : Treemap.layout(col.keys, col.weights, inset, inset, mw, mh);
            Map<String, Tile> old = new HashMap<>();
            for (Tile t : tiles) old.put(t.key, t);
            ArrayList<Tile> nt = new ArrayList<>(next.size());
            for (Treemap.Rect r : next) {
                Tile t = old.get(r.key);
                boolean fresh = t == null;
                if (fresh) t = new Tile();
                t.key = r.key;
                float h2 = gap / 2;
                t.tx = (float) r.x + h2; t.ty = (float) r.y + h2;
                t.tw = Math.max(0, (float) r.w - gap); t.th = Math.max(0, (float) r.h - gap);
                if (fresh || !animate) { t.fx = t.tx; t.fy = t.ty; t.fw = t.tw; t.fh = t.th; }
                else { t.fx = t.x; t.fy = t.y; t.fw = t.w; t.fh = t.h; }
                t.fadeIn = fresh && animate;
                nt.add(t);
            }
            tiles = nt;
            rects = next;
            if (animate && !nt.isEmpty()) {
                tweenStart = SystemClock.uptimeMillis();
                tweening = true;
                tweenT = 0;
                postInvalidateOnAnimation();
            } else {
                finishTween();
            }
            refocus();
        }
        refreshText();
    }

    /**
     * Focus follows wantKey: its tile if shown; the "+N more" tile while it is folded in there (wantKey kept, so focus
     * returns when it grows back out); if it vanished, the tile nearest the old focus, which becomes the new wantKey.
     */
    void refocus() {
        if (tiles.isEmpty()) { focus = -1; return; }
        String want = wantKey;
        boolean folded = false;
        if (want != null && !want.equals(MORE)) {
            for (TaskSnapshot.App a : hidden) if (a.pkg.equals(want)) { want = MORE; folded = true; break; }
        }
        int idx = -1;
        for (int i = 0; i < tiles.size(); i++) if (tiles.get(i).key.equals(want)) { idx = i; break; }
        if (idx < 0 && focus >= 0 && want != null) {
            // nearest to where the old focus was drawn
            float ox = lastFocusCx, oy = lastFocusCy;
            double best = Double.MAX_VALUE;
            for (int i = 0; i < rects.size(); i++) {
                double d = Math.hypot(rects.get(i).cx() - ox, rects.get(i).cy() - oy);
                if (d < best) { best = d; idx = i; }
            }
        }
        if (idx < 0) idx = 0;
        focus = idx;
        focusKey = tiles.get(idx).key;
        if (!(folded && MORE.equals(focusKey))) wantKey = focusKey;   // folded: remember the app, not MORE
        lastFocusCx = (float) rects.get(idx).cx();
        lastFocusCy = (float) rects.get(idx).cy();
    }

    float lastFocusCx, lastFocusCy;

    void finishTween() {
        tweening = false;
        tweenT = 1;
        for (Tile t : tiles) { t.x = t.tx; t.y = t.ty; t.w = t.tw; t.h = t.th; t.fadeIn = false; }
    }

    /** Recomputes every tile's strings, sizes and offsets for its target size (not called from onDraw). */
    void refreshText() {
        for (Tile t : tiles) fitText(t);
        invalidate();
    }

    void fitText(Tile t) {
        TaskSnapshot.App a = apps.get(t.key);
        boolean more = MORE.equals(t.key);
        t.color = more ? MORE_FILL : Brand.color(t.key);
        t.ink = more ? Theme.TEXT : Brand.ink(t.color);
        float w = t.tw, h = t.th, min = Math.min(w, h);
        t.showText = w >= 48 * dp && h >= 48 * dp;
        t.name = t.sub = t.pill = t.badge = null;
        if (!t.showText) return;

        t.pad = clamp(min / 10, 8 * dp, 16 * dp);
        t.nameSize = clamp(min / 6, 13 * dp, 30 * dp);
        t.subSize = Math.max(11 * dp, t.nameSize * 0.72f);
        t.pillSize = Math.max(10 * dp, t.nameSize * 0.58f);
        t.badgeSize = Math.max(11 * dp, t.nameSize * 0.7f);

        long rss = 0;
        String nm;
        if (more) {
            for (TaskSnapshot.App h2 : hidden) rss += h2.rssKb;
            nm = "+" + hidden.size() + " more";
        } else {
            rss = a == null ? 0 : a.rssKb;
            nm = labelOf(t.key);
        }
        String mbText = Units.mb(rss);
        String sub = sort == Sort.CPU && a != null ? a.cpuPct + "% CPU · " + mbText : mbText;

        // badge (top-right) reserves room on the name line
        String badge = more ? null : protectedPkgs.contains(t.key) ? "★" : alwaysKill.contains(t.key) ? "⚡" : null;
        float nameMax = w - 2 * t.pad;
        if (badge != null) {
            t.badge = badge;
            t.badgeR = t.badgeSize * 0.8f;
            t.badgeCx = w - t.pad - t.badgeR + 2 * dp;
            t.badgeCy = t.pad + t.badgeR - 2 * dp;
            nameMax -= 2 * t.badgeR + 4 * dp;
        }

        text.setTypeface(Typeface.DEFAULT_BOLD);
        text.setTextSize(t.nameSize);
        Paint.FontMetrics fm = text.getFontMetrics();
        float y = t.pad - fm.ascent;
        if (y + fm.descent > h - t.pad + 2 * dp || nameMax < 16 * dp) { t.showText = false; t.badge = null; return; }
        t.name = ellipsize(nm, nameMax);
        t.nameY = y;
        y += fm.descent;

        text.setTypeface(Typeface.DEFAULT);
        text.setTextSize(t.subSize);
        fm = text.getFontMetrics();
        float subY = y + 2 * dp - fm.ascent;
        if (subY + fm.descent <= h - t.pad + 2 * dp) {
            t.sub = ellipsize(sub, w - 2 * t.pad);
            t.subY = subY;
            y = subY + fm.descent;
        } else return;

        String pill = a == null ? null : "fg".equals(a.state) ? "in front" : "play".equals(a.state) ? "▶ playing" : null;
        if (pill != null) {
            text.setTextSize(t.pillSize);
            fm = text.getFontMetrics();
            float ph = (fm.descent - fm.ascent) + 6 * dp, padX = ph / 2;
            float top = y + 6 * dp;
            float maxW = w - 2 * t.pad;
            if (top + ph <= h - t.pad + 2 * dp && maxW > 2 * padX + 16 * dp) {
                t.pill = ellipsize(pill, maxW - 2 * padX);
                t.pillL = t.pad;
                t.pillT = top;
                t.pillR = t.pad + text.measureText(t.pill) + 2 * padX;
                t.pillB = top + ph;
            }
        }
    }

    String ellipsize(String s, float max) {
        if (text.measureText(s) <= max) return s;
        while (s.length() > 1 && text.measureText(s + "…") > max) s = s.substring(0, s.length() - 1);
        return s + "…";
    }

    static float clamp(float v, float lo, float hi) { return Math.max(lo, Math.min(hi, v)); }

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        if (snap == null) return;
        List<TaskSnapshot.App> user = new ArrayList<>();
        for (TaskSnapshot.App a : snap.apps) if (!"sys".equals(a.state)) user.add(a);
        rects = new ArrayList<>();   // force a fresh layout for the new size
        layoutTiles(user, false);
    }

    // ---- focus / input ----

    public int focusedIndex() { return focus; }
    public String focusedKey() { return focus >= 0 && focus < tiles.size() ? tiles.get(focus).key : null; }

    /** True when D-pad direction dir (TileNav.UP..RIGHT) moves focus to another tile. */
    public boolean canMove(int dir) { return focus >= 0 && TileNav.next(rects, focus, dir) != focus; }

    /** Moves focus in direction dir; false at an edge (or empty map) so the Activity can move focus out. */
    public boolean move(int dir) {
        if (focus < 0) return false;
        int n = TileNav.next(rects, focus, dir);
        if (n == focus) return false;
        setFocusIndex(n);
        return true;
    }

    void setFocusIndex(int i) {
        if (i < 0 || i >= tiles.size()) return;
        focus = i;
        focusKey = tiles.get(i).key;
        wantKey = focusKey;
        lastFocusCx = (float) rects.get(i).cx();
        lastFocusCy = (float) rects.get(i).cy();
        invalidate();
    }

    @Override protected void onFocusChanged(boolean gain, int dir, android.graphics.Rect prev) {
        super.onFocusChanged(gain, dir, prev);
        invalidate();
    }

    @Override public boolean onKeyDown(int k, KeyEvent e) {
        int dir;
        switch (k) {
            case KeyEvent.KEYCODE_DPAD_UP: dir = TileNav.UP; break;
            case KeyEvent.KEYCODE_DPAD_DOWN: dir = TileNav.DOWN; break;
            case KeyEvent.KEYCODE_DPAD_LEFT: dir = TileNav.LEFT; break;
            case KeyEvent.KEYCODE_DPAD_RIGHT: dir = TileNav.RIGHT; break;
            case KeyEvent.KEYCODE_DPAD_CENTER: case KeyEvent.KEYCODE_ENTER: case KeyEvent.KEYCODE_NUMPAD_ENTER:
                okDown = true;
                return true;   // pick on key up, so the release doesn't land in the dialog
            default: return super.onKeyDown(k, e);
        }
        return move(dir);
    }

    @Override public boolean onKeyUp(int k, KeyEvent e) {
        if (k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER || k == KeyEvent.KEYCODE_NUMPAD_ENTER) {
            boolean pick = okDown && !e.isCanceled();
            okDown = false;
            String key = focusedKey();
            if (pick && key != null && onPick != null) onPick.pick(key);
            return true;
        }
        return super.onKeyUp(k, e);
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        if (e.getAction() == MotionEvent.ACTION_UP) {
            for (int i = 0; i < tiles.size(); i++) {
                Tile t = tiles.get(i);
                if (e.getX() >= t.x && e.getX() < t.x + t.w && e.getY() >= t.y && e.getY() < t.y + t.h) {
                    requestFocus();
                    setFocusIndex(i);
                    if (onPick != null) onPick.pick(t.key);
                    break;
                }
            }
        }
        return true;
    }

    // ---- drawing ----

    /** Advances the tween; true while it runs. */
    boolean step() {
        if (!tweening) return false;
        float p = Math.min(1f, (SystemClock.uptimeMillis() - tweenStart) / (float) TWEEN_MS);
        float q = 1 - p;
        float ease = 1 - q * q * q;   // ease-out cubic
        tweenT = p;
        for (int i = 0, n = tiles.size(); i < n; i++) {
            Tile t = tiles.get(i);
            t.x = t.fx + (t.tx - t.fx) * ease;
            t.y = t.fy + (t.ty - t.fy) * ease;
            t.w = t.fw + (t.tw - t.fw) * ease;
            t.h = t.fh + (t.th - t.fh) * ease;
        }
        if (p >= 1) { finishTween(); return false; }
        return true;
    }

    @Override protected void onDraw(Canvas c) {
        if (step()) postInvalidateOnAnimation();
        if (tiles.isEmpty()) { drawEmpty(c); return; }
        boolean hasFocus = isFocused();
        for (int i = 0, n = tiles.size(); i < n; i++) drawTile(c, tiles.get(i));
        if (focus >= 0 && focus < tiles.size()) drawFocus(c, tiles.get(focus), hasFocus);
    }

    /** The empty-map message, centred; the shared text paint is reset by every tile draw anyway. */
    void drawEmpty(Canvas c) {
        if (emptyText == null) return;
        text.setTypeface(Typeface.DEFAULT);
        text.setTextSize(16 * dp);
        text.setColor(Theme.MUTED);
        text.setTextAlign(Paint.Align.CENTER);
        c.drawText(emptyText, getWidth() / 2f, getHeight() / 2f - (text.ascent() + text.descent()) / 2, text);
    }

    static int withAlpha(int color, int a) { return (Math.round(((color >>> 24) & 0xFF) * a / 255f) << 24) | (color & 0xFFFFFF); }

    void drawTile(Canvas c, Tile t) {
        if (t.w <= 0 || t.h <= 0) return;
        int a = t.fadeIn ? Math.round(255 * tweenT) : 255;
        rect.set(t.x, t.y, t.x + t.w, t.y + t.h);
        fill.setColor(withAlpha(t.color, a));
        c.drawRoundRect(rect, corner, corner, fill);
        shadeMatrix.setScale(1, t.h);
        shadeMatrix.postTranslate(0, t.y);
        shade.getShader().setLocalMatrix(shadeMatrix);
        shade.setAlpha(a);
        c.drawRoundRect(rect, corner, corner, shade);
        if (!t.showText || t.name == null) return;

        int save = c.save();
        c.clipRect(rect);
        c.translate(t.x, t.y);
        text.setTextAlign(Paint.Align.LEFT);
        text.setTypeface(Typeface.DEFAULT_BOLD);
        text.setTextSize(t.nameSize);
        text.setColor(withAlpha(t.ink, a));
        c.drawText(t.name, t.pad, t.nameY, text);
        if (t.sub != null) {
            text.setTypeface(Typeface.DEFAULT);
            text.setTextSize(t.subSize);
            text.setColor(withAlpha(t.ink, a * 0xD9 / 255));
            c.drawText(t.sub, t.pad, t.subY, text);
        }
        if (t.pill != null) {
            rect.set(t.pillL, t.pillT, t.pillR, t.pillB);
            float r = rect.height() / 2;
            fill.setColor(withAlpha(PILL_BG, a));
            c.drawRoundRect(rect, r, r, fill);
            text.setTypeface(Typeface.DEFAULT_BOLD);
            text.setTextSize(t.pillSize);
            text.setColor(withAlpha(Theme.TEXT, a));
            text.setTextAlign(Paint.Align.CENTER);
            c.drawText(t.pill, rect.centerX(), rect.centerY() - (text.ascent() + text.descent()) / 2, text);
        }
        if (t.badge != null) {
            fill.setColor(withAlpha(BADGE_BG, a));
            c.drawCircle(t.badgeCx, t.badgeCy, t.badgeR, fill);
            text.setTypeface(Typeface.DEFAULT_BOLD);
            text.setTextSize(t.badgeSize);
            text.setColor(withAlpha(t.badge.equals("★") ? Theme.ACCENT : 0xFFFFD54F, a));
            text.setTextAlign(Paint.Align.CENTER);
            c.drawText(t.badge, t.badgeCx, t.badgeCy - (text.ascent() + text.descent()) / 2, text);
        }
        c.restoreToCount(save);
    }

    /** 3 dp ACCENT ring just outside the tile with a soft outer glow (stacked translucent strokes; no blur filter). */
    void drawFocus(Canvas c, Tile t, boolean hasFocus) {
        if (t.w <= 0 || t.h <= 0) return;
        int ringColor = hasFocus ? Theme.ACCENT : Theme.WOOD;
        if (hasFocus) {
            for (int i = 0; i < GLOW_W.length; i++) {
                float o = 1.5f * dp + GLOW_W[i] * dp / 2;
                rect.set(t.x - o, t.y - o, t.x + t.w + o, t.y + t.h + o);
                stroke.setStrokeWidth(GLOW_W[i] * dp);
                stroke.setColor((GLOW_A[i] << 24) | (Theme.ACCENT & 0xFFFFFF));
                c.drawRoundRect(rect, corner + o, corner + o, stroke);
            }
        }
        float sw = (hasFocus ? 3 : 2) * dp, o = sw / 2;
        rect.set(t.x - o, t.y - o, t.x + t.w + o, t.y + t.h + o);
        stroke.setStrokeWidth(sw);
        stroke.setColor(ringColor);
        c.drawRoundRect(rect, corner + o, corner + o, stroke);
    }
}
