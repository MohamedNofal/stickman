package com.clean.stickman.core;

import java.util.ArrayList;
import java.util.List;

/**
 * Button glyphs as pure geometry so the app and a host-side preview draw exactly the same thing.
 * Coordinates are relative to the button centre in units of its radius r (y grows downwards).
 * strokes(): open polylines {x0,y0,x1,y1,...}; fills(): closed polygons. Codes with neither get their label as text.
 */
public final class RemoteIcons {
    private RemoteIcons() {}

    /** Stroke width as a fraction of r. */
    public static final float STROKE = 0.11f;

    private static float[] arc(float cx, float cy, float rad, float fromDeg, float toDeg) {
        int n = Math.max(2, (int) Math.ceil(Math.abs(toDeg - fromDeg) / 10f) + 1);
        float[] p = new float[n * 2];
        for (int i = 0; i < n; i++) {
            double a = Math.toRadians(fromDeg + (toDeg - fromDeg) * i / (n - 1));
            p[2 * i] = cx + (float) (rad * Math.cos(a));
            p[2 * i + 1] = cy + (float) (rad * Math.sin(a));
        }
        return p;
    }

    private static float[] cat(float[]... parts) {
        int n = 0;
        for (float[] p : parts) n += p.length;
        float[] r = new float[n];
        int o = 0;
        for (float[] p : parts) { System.arraycopy(p, 0, r, o, p.length); o += p.length; }
        return r;
    }

    private static float[] rot(float[] p, int quarterTurns) {
        float[] r = p.clone();
        for (int t = 0; t < quarterTurns; t++)
            for (int i = 0; i < r.length; i += 2) { float x = r[i]; r[i] = -r[i + 1]; r[i + 1] = x; }
        return r;
    }

    private static float[] gear() {
        int teeth = 8;
        List<Float> pts = new ArrayList<>();
        for (int i = 0; i <= teeth * 4; i++) {
            double a = Math.toRadians(-90 + i * 360.0 / (teeth * 4));
            float rr = (i % 4 == 0 || i % 4 == 1) ? 0.44f : 0.31f;
            pts.add((float) (rr * Math.cos(a)));
            pts.add((float) (rr * Math.sin(a)));
        }
        float[] r = new float[pts.size()];
        for (int i = 0; i < r.length; i++) r[i] = pts.get(i);
        return r;
    }

    static final float[] CHEVRON_UP = { -0.30f, 0.14f, 0f, -0.16f, 0.30f, 0.14f };

    public static float[][] strokes(int code) {
        switch (code) {
            case 116: return new float[][] { arc(0, 0.04f, 0.40f, -55, 235), { 0, -0.50f, 0, -0.04f } };
            case 217: return new float[][] {
                cat(arc(0, -0.30f, 0.14f, 180, 360), arc(0, -0.06f, 0.14f, 0, 180), new float[] { -0.14f, -0.30f }),
                arc(0, -0.10f, 0.28f, 10, 170),
                { 0, 0.18f, 0, 0.36f }, { -0.16f, 0.36f, 0.16f, 0.36f } };
            case 103: return new float[][] { CHEVRON_UP };
            case 106: return new float[][] { rot(CHEVRON_UP, 1) };
            case 108: return new float[][] { rot(CHEVRON_UP, 2) };
            case 105: return new float[][] { rot(CHEVRON_UP, 3) };
            case 158: return new float[][] {
                cat(new float[] { -0.34f, -0.14f, 0.12f, -0.14f }, arc(0.12f, 0.06f, 0.20f, -90, 90), new float[] { -0.12f, 0.26f }),
                { -0.17f, -0.31f, -0.34f, -0.14f, -0.17f, 0.03f } };
            case 172: return new float[][] {
                { -0.40f, 0.02f, 0, -0.36f, 0.40f, 0.02f },
                { -0.27f, -0.10f, -0.27f, 0.36f, 0.27f, 0.36f, 0.27f, -0.10f } };
            case 139: return new float[][] { { -0.32f, -0.22f, 0.32f, -0.22f }, { -0.32f, 0, 0.32f, 0 }, { -0.32f, 0.22f, 0.32f, 0.22f } };
            case 115: return new float[][] { { -0.30f, 0, 0.30f, 0 }, { 0, -0.30f, 0, 0.30f } };
            case 114: return new float[][] { { -0.30f, 0, 0.30f, 0 } };
            case 402: return new float[][] { { -0.28f, 0.12f, 0, -0.14f, 0.28f, 0.12f } };
            case 403: return new float[][] { { -0.28f, -0.12f, 0, 0.14f, 0.28f, -0.12f } };
            case 113: return new float[][] { { 0.10f, -0.14f, 0.38f, 0.14f }, { 0.10f, 0.14f, 0.38f, -0.14f } };
            case 748: return new float[][] {
                { -0.32f, -0.10f, 0.16f, -0.10f, 0.16f, 0.34f, -0.32f, 0.34f, -0.32f, -0.10f },
                { -0.16f, -0.10f, -0.16f, -0.30f, 0.32f, -0.30f, 0.32f, 0.16f, 0.16f, 0.16f } };
            case 362: return new float[][] {
                { -0.40f, -0.20f, 0.40f, -0.20f, 0.40f, 0.30f, -0.40f, 0.30f, -0.40f, -0.20f },
                { -0.16f, -0.42f, 0, -0.20f, 0.16f, -0.42f } };
            case 249: return new float[][] { gear(), arc(0, 0, 0.13f, 0, 360) };
            default: return null;
        }
    }

    public static float[][] fills(int code) {
        switch (code) {
            case 168: return new float[][] { { 0.02f, -0.24f, 0.02f, 0.24f, -0.34f, 0 }, { 0.38f, -0.24f, 0.38f, 0.24f, 0.02f, 0 } };
            case 208: return new float[][] { { -0.02f, -0.24f, -0.02f, 0.24f, 0.34f, 0 }, { -0.38f, -0.24f, -0.38f, 0.24f, -0.02f, 0 } };
            case 164: return new float[][] {
                { -0.40f, -0.25f, -0.40f, 0.25f, -0.04f, 0 },
                { 0.08f, -0.25f, 0.18f, -0.25f, 0.18f, 0.25f, 0.08f, 0.25f },
                { 0.28f, -0.25f, 0.38f, -0.25f, 0.38f, 0.25f, 0.28f, 0.25f } };
            case 113: return new float[][] { { -0.42f, -0.12f, -0.24f, -0.12f, -0.02f, -0.32f, -0.02f, 0.32f, -0.24f, 0.12f, -0.42f, 0.12f } };
            default: return null;
        }
    }
}
