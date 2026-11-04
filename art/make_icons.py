"""Draws the KeyShift launcher icon + Fire TV banner (run: python3 art/make_icons.py)."""
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

BG1, BG2 = (36, 99, 235), (124, 58, 237)        # blue -> violet
ACCENT = (251, 146, 60)                          # orange
REMOTE, BTN = (24, 26, 34), (236, 238, 245)

def draw_remote(d, cx, top, w, h):
    """Fire TV style remote: dark pill, d-pad ring, buttons."""
    d.rounded_rectangle([cx - w/2, top, cx + w/2, top + h], radius=w/2, fill=REMOTE)
    r = w * 0.36; cy = top + h * 0.30
    d.ellipse([cx - r, cy - r, cx + r, cy + r], outline=BTN, width=int(w * 0.07))
    rc = w * 0.11
    d.ellipse([cx - rc, cy - rc, cx + rc, cy + rc], fill=BTN)
    for i, yy in enumerate((0.55, 0.66, 0.77)):
        for dx in (-0.2, 0.2):
            b = w * 0.085
            col = ACCENT if (i == 2) else BTN
            d.ellipse([cx + dx*w - b, top + h*yy - b, cx + dx*w + b, top + h*yy + b], fill=col)

def arrow_arc(d, cx, cy, r, a0, a1, width, color):
    """Arc from a0 to a1 (degrees) with an arrow head at a1."""
    d.arc([cx - r, cy - r, cx + r, cy + r], a0, a1, fill=color, width=width)
    a = math.radians(a1)
    tip = (cx + r * math.cos(a), cy + r * math.sin(a))
    tang = a + math.pi / 2                      # direction of travel (clockwise in PIL coords)
    hl = width * 2.0
    left = (tip[0] + hl * math.cos(tang) * 0.0 - hl * math.cos(a) * 0.9, tip[1] - hl * math.sin(a) * 0.9)
    p1 = (tip[0] + hl * math.cos(tang), tip[1] + hl * math.sin(tang))
    p2 = (tip[0] - hl * 0.9 * math.cos(a), tip[1] - hl * 0.9 * math.sin(a))
    p3 = (tip[0] + hl * 0.9 * math.cos(a), tip[1] + hl * 0.9 * math.sin(a))
    d.polygon([p1, p2, p3], fill=color)

def swap_badge(img, cx, cy, r):
    d = ImageDraw.Draw(img)
    d.ellipse([cx - r, cy - r, cx + r, cy + r], fill=ACCENT)
    w = int(r * 0.2)
    arrow_arc(d, cx, cy, r * 0.52, 200, 330, w, (255, 255, 255))
    arrow_arc(d, cx, cy, r * 0.52, 20, 150, w, (255, 255, 255))

def icon():
    img = gradient(S, S, BG1, BG2).convert("RGBA")
    mask = Image.new("L", (S, S), 0)
    ImageDraw.Draw(mask).rounded_rectangle([0, 0, S, S], radius=S * 0.22, fill=255)
    img.putalpha(mask)
    d = ImageDraw.Draw(img)
    draw_remote(d, S * 0.43, S * 0.13, S * 0.30, S * 0.74)
    swap_badge(img, S * 0.70, S * 0.70, S * 0.17)
    return img

def banner():
    W, H = 1280, 720
    img = gradient(W, H, BG1, BG2).convert("RGBA")
    d = ImageDraw.Draw(img)
    draw_remote(d, W * 0.17, H * 0.12, H * 0.25, H * 0.76)
    swap_badge(img, W * 0.27, H * 0.70, H * 0.13)
    font = None
    for f in ("/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf",
              "/usr/share/fonts/truetype/liberation/LiberationSans-Bold.ttf"):
        if os.path.exists(f): font = ImageFont.truetype(f, int(H * 0.17)); break
    d.text((W * 0.38, H * 0.27), "Key", font=font, fill=(255, 255, 255))
    d.text((W * 0.38, H * 0.50), "Shift", font=font, fill=(255, 220, 190))
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
