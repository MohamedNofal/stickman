// Dev tool (not shipped): tests/run.sh, then javac -cp build/hosttest -d OUT tools/RemotePreview.java; java -cp build/hosttest:OUT RemotePreview out.png [code] [hint]
import com.clean.stickman.core.Keys;
import com.clean.stickman.core.RemoteIcons;
import com.clean.stickman.core.RemoteLayout;

import java.awt.*;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.io.File;

/**
 * Host-side preview of RemoteView's drawing (same geometry + glyph data, Java2D instead of Canvas).
 * Usage: java -cp build/hosttest:tools RemotePreview out.png [focusCode]
 */
public class RemotePreview {
    static final int BG = 0xFF14110E, PANEL = 0xFF1F1A15, WOOD = 0xFFC68A4E, ACCENT = 0xFFFF9A3C, TEXT = 0xFFF3EDE6, MUTED = 0xFFA39A90;

    public static void main(String[] args) throws Exception {
        int focus = args.length > 1 ? Integer.parseInt(args[1]) : 744;
        int W = 760, H = 1000;          // ~ the RemoteView at 1920x1080 / density 320 (2 px per dp)
        float dp = 2f;
        BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(new Color(BG, true)); g.fillRect(0, 0, W, H);

        float pad = 10 * dp;
        float bh = H - 2 * pad, bw = bh / RemoteLayout.ASPECT;
        float bx = (W - bw) / 2, by = pad;
        float corner = bw * RemoteLayout.CORNER;
        // shadow + body + rim
        g.setColor(new Color(0, 0, 0, 110));
        g.fill(new RoundRectangle2D.Float(bx + 3 * dp, by + 5 * dp, bw, bh, corner * 2, corner * 2));
        g.setPaint(new GradientPaint(0, by, new Color(0xFF2A231C, true), 0, by + bh, new Color(PANEL, true)));
        g.fill(new RoundRectangle2D.Float(bx, by, bw, bh, corner * 2, corner * 2));
        g.setColor(new Color(WOOD, true)); g.setStroke(new BasicStroke(2.5f * dp));
        g.draw(new RoundRectangle2D.Float(bx, by, bw, bh, corner * 2, corner * 2));

        // D-pad ring
        float rcx = bx + 0.5f * bw, rcy = by + RemoteLayout.RING_Y * bh, rr = RemoteLayout.RING_R * bw;
        g.setColor(new Color(0xFF2E261E, true)); g.fill(new Ellipse2D.Float(rcx - rr, rcy - rr, 2 * rr, 2 * rr));
        g.setColor(new Color(0xFF4A3D30, true)); g.setStroke(new BasicStroke(1.2f * dp));
        g.draw(new Ellipse2D.Float(rcx - rr, rcy - rr, 2 * rr, 2 * rr));
        // rockers
        for (float[] k : RemoteLayout.ROCKERS) {
            float x = bx + k[0] * bw, t = by + k[1] * bh, b = by + k[2] * bh, w = 0.105f * bw;
            RoundRectangle2D.Float rk = new RoundRectangle2D.Float(x - w, t - w, 2 * w, b - t + 2 * w, 2 * w, 2 * w);
            g.setColor(new Color(0xFF2A231C, true)); g.fill(rk);
            g.setColor(new Color(0xFF4A3D30, true)); g.draw(rk);
            String cap = RemoteLayout.ROCKER_LABELS[k == RemoteLayout.ROCKERS[0] ? 0 : 1];
            g.setFont(new Font("SansSerif", Font.BOLD, Math.round(bw * 0.04f))); g.setColor(new Color(MUTED, true));
            FontMetrics cf = g.getFontMetrics();
            g.drawString(cap, x - cf.stringWidth(cap) / 2f, (t + b) / 2 + (cf.getAscent() - cf.getDescent()) / 2f);
        }
        for (RemoteLayout.Btn btn : RemoteLayout.BUTTONS) {
            float x = bx + btn.cx * bw, y = by + btn.cy * bh, r = btn.r * bw;
            boolean f = btn.code == focus;
            boolean inRing = btn.code == 103 || btn.code == 108 || btn.code == 105 || btn.code == 106;
            boolean inRocker = btn.code == 115 || btn.code == 114 || btn.code == 402 || btn.code == 403;
            Shape s = btn.round ? new Ellipse2D.Float(x - r, y - r, 2 * r, 2 * r)
                : new RoundRectangle2D.Float(x - btn.hw() * bw, y - btn.hh() * bw, 2 * btn.hw() * bw, 2 * btn.hh() * bw, 2 * btn.hh() * bw, 2 * btn.hh() * bw);
            int fill = appFill(btn.code);
            if (!inRing && !inRocker || f) {
                g.setColor(new Color(f && btn.round ? 0xFF4A321C : fill, true)); g.fill(s);
                g.setColor(new Color(0xFF4A3D30, true)); g.setStroke(new BasicStroke(1 * dp)); g.draw(s);
            }
            if (btn.code == 96) { g.setColor(new Color(0xFF3A3028, true)); g.fill(s); g.setColor(new Color(0xFF55473A, true)); g.draw(s); }
            int ink = btn.code == 217 ? 0xFF7FD3F7 : TEXT;
            g.setColor(new Color(ink, true));
            g.setStroke(new BasicStroke(RemoteIcons.STROKE * r, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            float[][] st = RemoteIcons.strokes(btn.code), fl = RemoteIcons.fills(btn.code);
            if (st != null) for (float[] p : st) g.draw(path(p, x, y, r, false));
            if (fl != null) for (float[] p : fl) g.fill(path(p, x, y, r, true));
            if (st == null && fl == null) {
                int tc = appInk(btn.code);
                float ts = btn.round ? r * 0.55f : btn.hh() * bw * 0.85f;
                Font font = new Font("SansSerif", btn.code == 744 || btn.code == 746 ? Font.BOLD : Font.PLAIN, Math.round(ts));
                g.setFont(font); g.setColor(new Color(tc, true));
                FontMetrics fm = g.getFontMetrics();
                float maxW = (btn.round ? 1.5f * r : 1.7f * btn.hw() * bw);
                if (fm.stringWidth(btn.label) > maxW) { g.setFont(font.deriveFont(ts * maxW / fm.stringWidth(btn.label))); fm = g.getFontMetrics(); }
                g.drawString(btn.label, x - fm.stringWidth(btn.label) / 2f, y + (fm.getAscent() - fm.getDescent()) / 2f);
            }
            if (f) {
                g.setColor(new Color(ACCENT, true)); g.setStroke(new BasicStroke(3 * dp));
                float o = 4 * dp;
                if (btn.round) g.draw(new Ellipse2D.Float(x - r - o, y - r - o, 2 * (r + o), 2 * (r + o)));
                else { float hw = btn.hw() * bw + o, hh = btn.hh() * bw + o; g.draw(new RoundRectangle2D.Float(x - hw, y - hh, 2 * hw, 2 * hh, 2 * hh, 2 * hh)); }
            }
            // badge for a few keys
            if (btn.code == 172 || btn.code == 744 || btn.code == 164) {
                float ang = (float) Math.toRadians(-45);
                float dx = btn.round ? (float) Math.cos(ang) * r : btn.hw() * bw - 2 * dp, dy = btn.round ? (float) Math.sin(ang) * r : -btn.hh() * bw + 2 * dp;
                float br = 4.5f * dp;
                g.setColor(new Color(BG, true)); g.fill(new Ellipse2D.Float(x + dx - br - 1.5f * dp, y + dy - br - 1.5f * dp, 2 * br + 3 * dp, 2 * br + 3 * dp));
                g.setColor(new Color(ACCENT, true)); g.fill(new Ellipse2D.Float(x + dx - br, y + dy - br, 2 * br, 2 * br));
            }
        }
        // hint callout under focused
        RemoteLayout.Btn fb = RemoteLayout.find(focus);
        String hint = args.length > 2 ? args[2] : "Open Kodi";
        g.setFont(new Font("SansSerif", Font.PLAIN, Math.round(13 * dp)));
        FontMetrics fm = g.getFontMetrics();
        float tw = fm.stringWidth(hint), ph = 26 * dp, pw = tw + 24 * dp;
        float fx = bx + fb.cx * bw, fy = by + fb.cy * bh + fb.hh() * bw + 10 * dp;
        if (fy + ph > H - 2 * dp) fy = by + fb.cy * bh - fb.hh() * bw - 10 * dp - ph;
        float px = Math.max(4 * dp, Math.min(W - pw - 4 * dp, fx - pw / 2));
        RoundRectangle2D.Float pill = new RoundRectangle2D.Float(px, fy, pw, ph, ph, ph);
        g.setColor(new Color(0xF0100D0A, true)); g.fill(pill);
        g.setColor(new Color(ACCENT, true)); g.setStroke(new BasicStroke(1.5f * dp)); g.draw(pill);
        g.setColor(new Color(TEXT, true));
        g.drawString(hint, px + 12 * dp, fy + ph / 2 + (fm.getAscent() - fm.getDescent()) / 2f);

        javax.imageio.ImageIO.write(img, "png", new File(args[0]));
    }

    static int appFill(int c) {
        switch (c) { case 745: return 0xFF1B5E8C; case 744: return 0xFF0E0B09; case 747: return 0xFF173A44; case 746: return 0xFF2C241C; default: return 0xFF2C241C; }
    }
    static int appInk(int c) {
        switch (c) { case 745: return 0xFFFFFFFF; case 744: return 0xFFE50914; case 747: return 0xFF4FE3F0; default: return TEXT; }
    }

    static Path2D.Float path(float[] p, float x, float y, float r, boolean close) {
        Path2D.Float pa = new Path2D.Float();
        pa.moveTo(x + p[0] * r, y + p[1] * r);
        for (int i = 2; i < p.length; i += 2) pa.lineTo(x + p[i] * r, y + p[i + 1] * r);
        if (close) pa.closePath();
        return pa;
    }
}
