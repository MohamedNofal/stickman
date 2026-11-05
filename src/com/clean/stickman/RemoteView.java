package com.clean.stickman;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;

import com.clean.stickman.core.RemoteIcons;
import com.clean.stickman.core.RemoteLayout;
import com.clean.stickman.core.RemotePresets;

/**
 * Picture of the chosen Fire TV remote (a RemotePresets preset). D-pad moves a focus ring spatially (RemoteLayout.next), OK
 * picks the button. Badge dots mark keys with rules in the current profile; a hint pill under the focused
 * button says what its tap does. flash() pulses a button (listen mode).
 */
public class RemoteView extends View {
    public interface Listener {
        void onPick(RemoteLayout.Btn b);
        void onFocusButton(RemoteLayout.Btn b);
    }

    /** Supplies the per-button state the view shows. */
    public interface Info {
        boolean hasRules(RemoteLayout.Btn b);
        String hint(RemoteLayout.Btn b);
    }

    static final int BTN = 0xFF2C241C, BTN_EDGE = 0xFF4A3D30, RING = 0xFF2E261E, OK_FILL = 0xFF3A3028,
        OK_EDGE = 0xFF55473A, FOCUS_FILL = 0xFF4A321C, BODY_TOP = 0xFF2A231C, ALEXA = 0xFF7FD3F7, HINT_BG = 0xF0100D0A,
        HOME_2026 = 0xFFD9661F;

    Listener listener;
    Info info;
    RemotePresets.Preset preset = RemotePresets.get(RemotePresets.DEFAULT);
    RemoteLayout.Btn[] bs = preset.buttons;
    /** Index into bs. */
    int focus = RemoteLayout.indexOf(bs, 96);
    boolean listening, okDown;

    final float dp;
    final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), stroke = new Paint(Paint.ANTI_ALIAS_FLAG),
        text = new Paint(Paint.ANTI_ALIAS_FLAG);
    final Path path = new Path();
    final RectF rect = new RectF();
    LinearGradient bodyShader;
    float bx, by, bw, bh;

    static final long FLASH_MS = 900, BREATH_MS = 1400;
    int flashIdx = -1;
    float flashT = 1f;
    long flashStart, listenStart;
    float listenT;

    public RemoteView(Context c) {
        super(c);
        dp = c.getResources().getDisplayMetrics().density;
        setFocusable(true);
        setFocusableInTouchMode(true);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        stroke.setStrokeJoin(Paint.Join.ROUND);
        text.setTextAlign(Paint.Align.CENTER);
        setContentDescription("Remote");
    }

    public void setListener(Listener l) { listener = l; }
    public void setInfo(Info i) { info = i; invalidate(); }
    public RemoteLayout.Btn focused() { return bs[focus]; }
    public RemoteLayout.Btn[] buttons() { return bs; }

    /** Show another preset (or the same one with new learned codes / names); focus stays on the same slot if it can. */
    public void setRemote(RemotePresets.Preset p, RemoteLayout.Btn[] buttons) {
        String slot = bs[focus].slot;
        preset = p;
        bs = buttons;
        int i = RemotePresets.slotIndex(bs, slot);
        focus = i >= 0 ? i : RemoteLayout.indexOf(bs, 96);
        flashIdx = -1;
        place();
        if (listener != null) listener.onFocusButton(bs[focus]);
        invalidate();
    }

    void setFocusIndex(int i) {
        if (i < 0 || i >= bs.length || i == focus) return;
        focus = i;
        if (listener != null) listener.onFocusButton(bs[i]);
        invalidate();
    }

    /** True when D-pad direction dir (RemoteLayout.UP..RIGHT) moves focus inside the remote. */
    public boolean canMove(int dir) { return RemoteLayout.next(bs, focus, dir) != focus; }

    /** Highlight pulse on the button sending code (and focus it). Clock-driven, so it shows with animations off. */
    public void flash(int code) {
        int i = RemoteLayout.indexOf(bs, code);
        if (i < 0) return;
        setFocusIndex(i);
        flashIdx = i;
        flashStart = SystemClock.uptimeMillis();
        invalidate();
    }

    /** Listening: the rim breathes in ACCENT. */
    public void setListening(boolean on) {
        if (on == listening) return;
        listening = on;
        listenStart = SystemClock.uptimeMillis();
        invalidate();
    }

    /** Advances the flash / listen animations; true while one is running. */
    boolean stepAnimations() {
        long now = SystemClock.uptimeMillis();
        boolean more = false;
        if (flashIdx >= 0) {
            long e = now - flashStart;
            if (e >= 2 * FLASH_MS) flashIdx = -1;
            else { flashT = (e % FLASH_MS) / (float) FLASH_MS; more = true; }
        }
        if (listening) {
            float ph = ((now - listenStart) % BREATH_MS) / (float) BREATH_MS;
            listenT = ph < 0.5f ? ph * 2 : (1 - ph) * 2;
            more = true;
        }
        return more;
    }

    @Override protected void onFocusChanged(boolean gain, int dir, android.graphics.Rect prev) {
        super.onFocusChanged(gain, dir, prev);
        invalidate();
    }

    /**
     * Body on the left, a reserved strip on its right for the hint pill (so the hint never covers a button).
     * Computed on size / preset changes only, never in onDraw.
     */
    void place() {
        if (getWidth() <= 0 || getHeight() <= 0) { bw = 0; return; }
        float[] r = RemoteLayout.body(getWidth(), getHeight(), dp, preset.aspect);
        bx = r[0]; by = r[1]; bw = r[2]; bh = r[3];
        bodyShader = new LinearGradient(0, by, 0, by + bh, BODY_TOP, Theme.PANEL, Shader.TileMode.CLAMP);
    }

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) { place(); }

    // ---- input ----
    @Override public boolean onKeyDown(int k, KeyEvent e) {
        int dir;
        switch (k) {
            case KeyEvent.KEYCODE_DPAD_UP: dir = RemoteLayout.UP; break;
            case KeyEvent.KEYCODE_DPAD_DOWN: dir = RemoteLayout.DOWN; break;
            case KeyEvent.KEYCODE_DPAD_LEFT: dir = RemoteLayout.LEFT; break;
            case KeyEvent.KEYCODE_DPAD_RIGHT: dir = RemoteLayout.RIGHT; break;
            case KeyEvent.KEYCODE_DPAD_CENTER: case KeyEvent.KEYCODE_ENTER: case KeyEvent.KEYCODE_NUMPAD_ENTER:
                okDown = true;
                return true;   // pick on key up, so the release doesn't land in the editor dialog
            default: return super.onKeyDown(k, e);
        }
        int n = RemoteLayout.next(bs, focus, dir);
        if (n == focus) return false;   // edge: let the framework move focus out of the remote
        setFocusIndex(n);
        return true;
    }

    @Override public boolean onKeyUp(int k, KeyEvent e) {
        if (k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER || k == KeyEvent.KEYCODE_NUMPAD_ENTER) {
            boolean pick = okDown && !e.isCanceled();
            okDown = false;
            if (pick && listener != null) listener.onPick(bs[focus]);
            return true;
        }
        return super.onKeyUp(k, e);
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        if (e.getAction() == MotionEvent.ACTION_UP) {
            int hit = -1;
            float best = Float.MAX_VALUE;
            for (int i = 0; i < bs.length; i++) {
                RemoteLayout.Btn b = bs[i];
                float d = (float) Math.hypot(e.getX() - x(b), e.getY() - y(b));
                if (d < b.r * bw * 1.3f && d < best) { best = d; hit = i; }
            }
            if (hit >= 0) {
                requestFocus();
                setFocusIndex(hit);
                if (listener != null) listener.onPick(bs[hit]);
            }
        }
        return true;
    }

    // ---- drawing ----
    float x(RemoteLayout.Btn b) { return bx + b.cx * bw; }
    float y(RemoteLayout.Btn b) { return by + b.cy * bh; }

    static boolean inRing(int c) { return c == 103 || c == 108 || c == 105 || c == 106; }
    static boolean inRocker(int c) { return c == 115 || c == 114 || c == 402 || c == 403; }

    void shape(RemoteLayout.Btn b, float grow) {
        float x = x(b), y = y(b);
        if (b.round) {
            float r = b.r * bw + grow;
            rect.set(x - r, y - r, x + r, y + r);
        } else {
            float hw = b.hw() * bw + grow, hh = b.hh() * bw + grow;
            rect.set(x - hw, y - hh, x + hw, y + hh);
        }
    }

    void drawShape(Canvas c, RemoteLayout.Btn b, Paint p) {
        if (b.round) c.drawOval(rect, p);
        else c.drawRoundRect(rect, rect.height() / 2, rect.height() / 2, p);
    }

    void poly(Canvas c, float[] p, float x, float y, float r, boolean close, Paint paint) {
        path.reset();
        path.moveTo(x + p[0] * r, y + p[1] * r);
        for (int i = 2; i < p.length; i += 2) path.lineTo(x + p[i] * r, y + p[i + 1] * r);
        if (close) path.close();
        c.drawPath(path, paint);
    }

    @Override protected void onDraw(Canvas c) {
        if (bw <= 1 || bodyShader == null) return;
        if (stepAnimations()) postInvalidateOnAnimation();
        float corner = bw * preset.corner;

        // shadow, body, rim
        fill.setShader(null);
        fill.setColor(0x6E000000);
        rect.set(bx + 3 * dp, by + 5 * dp, bx + bw + 3 * dp, by + bh + 5 * dp);
        c.drawRoundRect(rect, corner, corner, fill);
        fill.setShader(bodyShader);
        rect.set(bx, by, bx + bw, by + bh);
        c.drawRoundRect(rect, corner, corner, fill);
        fill.setShader(null);
        stroke.setStrokeWidth(2.5f * dp);
        stroke.setColor(listening ? blend(Theme.WOOD, Theme.ACCENT, listenT) : Theme.WOOD);
        c.drawRoundRect(rect, corner, corner, stroke);
        if (listening) {
            stroke.setStrokeWidth(6 * dp);
            stroke.setColor((Math.round(0x60 * listenT) << 24) | (Theme.ACCENT & 0xFFFFFF));
            rect.inset(-4 * dp, -4 * dp);
            c.drawRoundRect(rect, corner + 4 * dp, corner + 4 * dp, stroke);
        }

        // D-pad ring
        float rcx = bx + 0.5f * bw, rcy = by + preset.ringY * bh, rr = preset.ringR * bw;
        fill.setColor(RING);
        c.drawCircle(rcx, rcy, rr, fill);
        stroke.setStrokeWidth(1.2f * dp);
        stroke.setColor(BTN_EDGE);
        c.drawCircle(rcx, rcy, rr, stroke);

        // volume / channel rockers
        text.setTypeface(Typeface.DEFAULT_BOLD);
        text.setTextSize(bw * 0.04f);
        for (int i = 0; i < preset.rockers.length; i++) {
            float[] k = preset.rockers[i];
            float x = bx + k[0] * bw, t = by + k[1] * bh, b = by + k[2] * bh, w = RemoteLayout.ROCKER_W * bw;
            rect.set(x - w, t - w, x + w, b + w);
            fill.setColor(BODY_TOP);
            c.drawRoundRect(rect, w, w, fill);
            stroke.setColor(BTN_EDGE);
            c.drawRoundRect(rect, w, w, stroke);
            text.setColor(Theme.MUTED);
            c.drawText(preset.rockerLabels[i], x, (t + b) / 2 - (text.ascent() + text.descent()) / 2, text);
        }

        boolean hasFocus = isFocused();
        for (int i = 0; i < bs.length; i++) drawButton(c, bs[i], i == focus, hasFocus);
        if (flashIdx >= 0 && flashIdx < bs.length) drawFlash(c);
        drawHint(c, hasFocus);
    }

    void drawButton(Canvas c, RemoteLayout.Btn b, boolean f, boolean hasFocus) {
        float x = x(b), y = y(b), r = b.r * bw;
        boolean plain = !inRing(b.code) && !inRocker(b.code);
        int btnFill = b.isApp() ? RemoteIcons.brandFill(b.name, BTN)
            : b.code == 172 && preset.homeAccent ? HOME_2026 : BTN;
        shape(b, 0);
        if (b.code == 96) {
            fill.setColor(f ? FOCUS_FILL : OK_FILL);
            drawShape(c, b, fill);
            stroke.setStrokeWidth(1 * dp);
            stroke.setColor(OK_EDGE);
            drawShape(c, b, stroke);
        } else if (plain || f) {
            fill.setColor(f && b.round ? FOCUS_FILL : btnFill);
            drawShape(c, b, fill);
            stroke.setStrokeWidth(1 * dp);
            stroke.setColor(BTN_EDGE);
            drawShape(c, b, stroke);
        }

        float[][] st = RemoteIcons.strokes(b), fl = RemoteIcons.fills(b);
        // Alexa ring in blue; unlearned buttons dimmed
        int ink = b.code == 217 && b.glyph == null ? ALEXA : b.code == 0 ? Theme.MUTED : Theme.TEXT;
        if (st != null) {
            stroke.setStrokeWidth(RemoteIcons.STROKE * r);
            stroke.setColor(ink);
            for (float[] p : st) poly(c, p, x, y, r, false, stroke);
        }
        if (fl != null) {
            fill.setColor(ink);
            for (float[] p : fl) poly(c, p, x, y, r, true, fill);
        }
        if (st == null && fl == null) {
            boolean bold = b.isApp() && RemoteIcons.brandBold(b.name);
            String label = b.isApp() ? RemoteIcons.brandText(b.name) : b.label;
            text.setTypeface(bold ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
            float ts = b.round ? r * (label.length() <= 2 ? 0.85f : 0.55f) : b.hh() * bw * 0.85f;
            text.setTextSize(ts);
            float maxW = b.round ? 1.5f * r : 1.7f * b.hw() * bw;
            float tw = text.measureText(label);
            if (tw > maxW) text.setTextSize(ts * maxW / tw);
            text.setColor(b.isApp() ? RemoteIcons.brandInk(b.name, Theme.TEXT) : b.code == 96 || b.code == 0 ? Theme.MUTED : Theme.TEXT);
            c.drawText(label, x, y - (text.ascent() + text.descent()) / 2, text);
        }

        if (f) {
            shape(b, RemoteLayout.FOCUS_OFFSET_DP * dp);
            stroke.setStrokeWidth((hasFocus ? RemoteLayout.FOCUS_STROKE_DP : 2) * dp);
            stroke.setColor(hasFocus ? Theme.ACCENT : Theme.WOOD);
            drawShape(c, b, stroke);
        }

        if (info != null && info.hasRules(b)) {
            float dx, dy;
            if (b.round) { dx = r * 0.7071f; dy = -r * 0.7071f; }
            else { dx = b.hw() * bw - 3 * dp; dy = -b.hh() * bw + 2 * dp; }
            float br = 4.5f * dp;
            fill.setColor(Theme.BG);
            c.drawCircle(x + dx, y + dy, br + 1.5f * dp, fill);
            fill.setColor(Theme.ACCENT);
            c.drawCircle(x + dx, y + dy, br, fill);
        }
    }

    void drawFlash(Canvas c) {
        RemoteLayout.Btn b = bs[flashIdx];
        float t = flashT;
        int a = Math.round(255 * (1 - t));
        shape(b, 0);
        fill.setColor((Math.round(0x90 * (1 - t)) << 24) | (Theme.ACCENT & 0xFFFFFF));
        drawShape(c, b, fill);
        shape(b, 4 * dp + t * 18 * dp);
        stroke.setStrokeWidth(4 * dp * (1 - t) + 1 * dp);
        stroke.setColor((a << 24) | (Theme.ACCENT & 0xFFFFFF));
        drawShape(c, b, stroke);
    }

    /** Short tap-action pill in the strip right of the body, level with the focused button, pointing at it. */
    void drawHint(Canvas c, boolean hasFocus) {
        if (info == null) return;
        RemoteLayout.Btn b = bs[focus];
        String h = info.hint(b);
        if (h == null || h.isEmpty()) return;
        text.setTypeface(Typeface.DEFAULT);
        text.setTextSize(RemoteLayout.HINT_TEXT_SP * getResources().getDisplayMetrics().scaledDensity);
        float maxText = getWidth() - (bx + bw) - (RemoteLayout.HINT_GAP_DP + RemoteLayout.HINT_MARGIN_DP + 24) * dp;
        if (maxText < 20 * dp) return;
        h = ellipsize(h, maxText);
        float[] r = RemoteLayout.hintRect(b, bx, by, bw, bh, getWidth(), getHeight(), text.measureText(h), dp);
        rect.set(r[0], r[1], r[2], r[3]);
        float ph = rect.height();
        int edge = hasFocus ? Theme.ACCENT : Theme.WOOD;
        // pointer from the pill's left edge towards the rim, at the button's height
        float pw = RemoteLayout.HINT_POINTER_DP * dp;
        float py = Math.max(rect.top + pw, Math.min(rect.bottom - pw, y(b)));
        path.reset();
        path.moveTo(rect.left + 0.5f, py - pw * 0.8f);
        path.lineTo(rect.left - pw, py);
        path.lineTo(rect.left + 0.5f, py + pw * 0.8f);
        path.close();
        fill.setColor(edge);
        c.drawPath(path, fill);
        fill.setColor(HINT_BG);
        c.drawRoundRect(rect, ph / 2, ph / 2, fill);
        stroke.setStrokeWidth(1.5f * dp);
        stroke.setColor(edge);
        c.drawRoundRect(rect, ph / 2, ph / 2, stroke);
        text.setColor(Theme.TEXT);
        c.drawText(h, rect.centerX(), rect.centerY() - (text.ascent() + text.descent()) / 2, text);
    }

    String ellipsize(String s, float max) {
        if (text.measureText(s) <= max) return s;
        while (s.length() > 1 && text.measureText(s + "…") > max) s = s.substring(0, s.length() - 1);
        return s + "…";
    }

    static int blend(int a, int b, float t) {
        int r = Math.round(((a >> 16) & 0xFF) * (1 - t) + ((b >> 16) & 0xFF) * t);
        int g = Math.round(((a >> 8) & 0xFF) * (1 - t) + ((b >> 8) & 0xFF) * t);
        int bl = Math.round((a & 0xFF) * (1 - t) + (b & 0xFF) * t);
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
    }
}
