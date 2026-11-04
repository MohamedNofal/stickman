"""Draws the Stickman (Tung Tung Tung Sahur-style) launcher icon + Fire TV banner (run: python3 art/make_icons.py)."""
import math, os
from PIL import Image, ImageDraw, ImageFont

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
S = 1024  # master size, downsampled later for smooth edges

def gradient(w, h, c1, c2):
    g = Image.new("RGB", (w, h))
    px = g.load()
    for y in range(h):
        for x in range(w):
            t = (x / w * 0.45 + y / h * 0.55)
            px[x, y] = tuple(int(c1[i] + (c2[i] - c1[i]) * t) for i in range(3))
    return g

BG1, BG2 = (0xFF, 0x9A, 0x3C), (0x3A, 0x24, 0x16)   # warm orange -> deep brown
WOOD, WOOD_HI, BARK = (0x8B, 0x5A, 0x2B), (0xC6, 0x8A, 0x4E), (0x5A, 0x36, 0x18)
RING_A, RING_B = (0xE8, 0xC0, 0x8A), (0xC6, 0x8A, 0x4E)
BAT, BAT_HI = (0xE6, 0xB8, 0x7A), (0xF6, 0xD8, 0xA8)
INK, WHITE = (0x1C, 0x10, 0x08), (255, 255, 255)

def thick_line(d, pts, w, fill, outline=INK, ow=None):
    """Stick limb: dark outline stroke, then fill stroke, round ends."""
    ow = ow if ow is not None else w * 0.45
    for col, ww in ((outline, w + 2 * ow), (fill, w)):
        d.line(pts, fill=col, width=int(ww), joint="curve")
        for p in (pts[0], pts[-1]):
            r = ww / 2
            d.ellipse([p[0] - r, p[1] - r, p[0] + r, p[1] + r], fill=col)

def draw_bat(d, hx, hy, L, ang, w):
    """Wooden bat: handle at (hx,hy), extends length L at angle ang (rad, PIL coords)."""
    ux, uy = math.cos(ang), math.sin(ang); nx, ny = -uy, ux
    def P(t, s): return (hx + ux * L * t + nx * s, hy + uy * L * t + ny * s)
    hw, bw = w * 0.30, w * 0.62          # handle / barrel half-widths
    outline = [P(-0.06, hw * 1.5), P(0.0, hw * 1.5), P(0.0, hw), P(0.38, hw * 1.1), P(0.95, bw),
               P(0.95, -bw), P(0.38, -hw * 1.1), P(0.0, -hw), P(0.0, -hw * 1.5), P(-0.06, -hw * 1.5)]
    o = w * 0.18
    d.polygon(outline, fill=BAT, outline=INK, width=int(o))
    ex, ey = P(0.95, 0)
    d.ellipse([ex - bw, ey - bw, ex + bw, ey + bw], fill=BAT, outline=INK, width=int(o))
    # cover the seam between ellipse and body
    d.polygon([P(0.6, bw * 0.85 - o), P(0.95, bw - o), P(0.95, -bw + o), P(0.6, -bw * 0.85 + o)], fill=BAT)
    thick_line(d, [P(0.45, bw * 0.15), P(1.02, bw * 0.35)], w * 0.14, BAT_HI, outline=BAT_HI, ow=0)

def draw_sahur(img, cx, top, h):
    """Tung Tung Tung Sahur-style log: body width ~0.42h, standing on stick legs, bat in right hand."""
    d = ImageDraw.Draw(img)
    bw = h * 0.40; bh = h * 0.70           # log body
    x0, x1 = cx - bw / 2, cx + bw / 2
    capH = bw * 0.30                       # top ellipse height
    y0 = top + capH / 2; y1 = top + bh
    o = h * 0.022                          # outline width
    lw = h * 0.035                         # limb width
    # legs
    ly = y1 - capH * 0.3
    thick_line(d, [(cx - bw * 0.22, ly), (cx - bw * 0.26, top + h * 0.92), (cx - bw * 0.45, top + h * 0.95)], lw, WOOD)
    thick_line(d, [(cx + bw * 0.22, ly), (cx + bw * 0.26, top + h * 0.92), (cx + bw * 0.45, top + h * 0.95)], lw, WOOD)
    # left arm (waving down/out)
    thick_line(d, [(x0 + o, top + bh * 0.58), (x0 - bw * 0.30, top + bh * 0.72), (x0 - bw * 0.38, top + bh * 0.52)], lw, WOOD)
    # body: outline, fill, bottom ellipse
    d.rounded_rectangle([x0 - o, y0, x1 + o, y1 + o], radius=bw * 0.18, fill=INK)
    d.rectangle([x0, y0, x1, y1 - bw * 0.1], fill=WOOD)
    d.rounded_rectangle([x0, y0, x1, y1], radius=bw * 0.16, fill=WOOD)
    # highlight stripe + bark lines
    d.rectangle([x0 + bw * 0.08, y0, x0 + bw * 0.20, y1 - bw * 0.12], fill=WOOD_HI)
    for fx, a, b in ((0.34, 0.10, 0.30), (0.80, 0.05, 0.22), (0.86, 0.62, 0.92), (0.30, 0.78, 0.95), (0.62, 0.84, 0.97)):
        X = x0 + bw * fx
        d.line([(X, y0 + bh * a), (X, y0 + bh * b)], fill=BARK, width=int(h * 0.014))
    # top cut: ellipse with rings
    d.ellipse([x0 - o, top - o, x1 + o, top + capH + o], fill=INK)
    d.ellipse([x0, top, x1, top + capH], fill=RING_A)
    ccy = top + capH / 2
    for k, ring in ((0.72, True), (0.46, False), (0.20, True)):
        rx, ry = bw / 2 * k, capH / 2 * k
        if ring: d.ellipse([cx - rx, ccy - ry, cx + rx, ccy + ry], fill=RING_B)
        else:    d.ellipse([cx - rx, ccy - ry, cx + rx, ccy + ry], fill=RING_A)
    # face: big eyes
    er = bw * 0.19; ey = top + bh * 0.36
    for ex in (cx - bw * 0.21, cx + bw * 0.21):
        d.ellipse([ex - er - o, ey - er - o, ex + er + o, ey + er + o], fill=INK)
        d.ellipse([ex - er, ey - er, ex + er, ey + er], fill=WHITE)
        pr = er * 0.52
        px, py = ex + er * 0.12, ey + er * 0.08
        d.ellipse([px - pr, py - pr, px + pr, py + pr], fill=INK)
        sr = pr * 0.32
        d.ellipse([px - pr * 0.35 - sr, py - pr * 0.4 - sr, px - pr * 0.35 + sr, py - pr * 0.4 + sr], fill=WHITE)
        # brow
        d.line([(ex - er * 0.9, ey - er * 1.45), (ex + er * 0.8, ey - er * 1.30)], fill=INK, width=int(h * 0.028))
    # wide grin
    my = top + bh * 0.58; mw = bw * 0.36; mh = bw * 0.22
    d.chord([cx - mw, my - mh, cx + mw, my + mh], 0, 180, fill=INK)
    d.chord([cx - mw * 0.82, my - mh * 0.55, cx + mw * 0.82, my + mh * 0.25], 0, 180, fill=WHITE)
    # right arm holding bat diagonally up-right
    hx, hy = x1 + bw * 0.34, top + bh * 0.50
    draw_bat(d, hx, hy, h * 0.50, math.radians(-58), h * 0.15)
    thick_line(d, [(x1 - o, top + bh * 0.66), (x1 + bw * 0.20, top + bh * 0.66), (hx, hy)], lw, WOOD)
    # fist
    fr = lw * 1.1
    d.ellipse([hx - fr - o, hy - fr - o, hx + fr + o, hy + fr + o], fill=INK)
    d.ellipse([hx - fr, hy - fr, hx + fr, hy + fr], fill=WOOD_HI)

def rounded_bg(w, h, rad):
    img = gradient(w, h, BG1, BG2).convert("RGBA")
    mask = Image.new("L", (w, h), 0)
    ImageDraw.Draw(mask).rounded_rectangle([0, 0, w, h], radius=rad, fill=255)
    img.putalpha(mask)
    return img

def icon():
    img = rounded_bg(S, S, S * 0.22)
    draw_sahur(img, S * 0.40, S * 0.10, S * 0.84)
    return img

def banner():
    W, H = 1280, 720
    img = gradient(W, H, BG1, BG2).convert("RGBA")
    draw_sahur(img, W * 0.20, H * 0.11, H * 0.84)
    d = ImageDraw.Draw(img)
    font = None
    for f in ("/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf",
              "/usr/share/fonts/truetype/liberation/LiberationSans-Bold.ttf"):
        if os.path.exists(f): font = ImageFont.truetype(f, int(H * 0.19)); break
    l, t, r, b = d.textbbox((0, 0), "Stickman", font=font)
    tx = W * 0.40 + (W * 0.58 - (r - l)) / 2 - l       # centre in the right-hand area
    ty = (H - (b - t)) / 2 - t
    d.text((tx + 8, ty + 8), "Stickman", font=font, fill=(0x1C, 0x10, 0x08))
    d.text((tx, ty), "Stickman", font=font, fill=(255, 255, 255))
    return img

def save(img, size, path):
    p = os.path.join(ROOT, path); os.makedirs(os.path.dirname(p), exist_ok=True)
    img.resize(size, Image.LANCZOS).save(p, optimize=True)

ic = icon()
for dpi, px in (("mdpi", 48), ("hdpi", 72), ("xhdpi", 96), ("xxhdpi", 144), ("xxxhdpi", 192)):
    save(ic, (px, px), f"res/mipmap-{dpi}/ic_launcher.png")
save(ic, (512, 512), "art/icon-512.png")
bn = banner()
save(bn, (320, 180), "res/drawable-xhdpi/banner.png")
save(bn, (640, 360), "art/banner-640.png")
print("icons written")
