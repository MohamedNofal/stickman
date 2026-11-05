"""Builds the Stickman launcher icon + Fire TV banner from the ORIGINAL Tung Tung Tung Sahur image
(run: python3 art/make_icons.py).

Source: art/source/sahur-original.png (Wikimedia Commons, public domain, AI-generated; credited
creator @noxaasht; see art/SOURCE.md). art/source/sahur-mask.png is a one-off character cut-out
mask derived from it with rembg (isnet-general-use); it lets the background be darkened/blurred
while the character stays bright. Only PIL is needed here.
"""
import os
from PIL import Image, ImageDraw, ImageFont, ImageFilter, ImageEnhance

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = Image.open(os.path.join(ROOT, "art/source/sahur-original.png")).convert("RGB")
MASK = Image.open(os.path.join(ROOT, "art/source/sahur-mask.png")).convert("L")
S = 1024  # master size, downsampled later for smooth edges

BG1, BG2 = (0xFF, 0x9A, 0x3C), (0x3A, 0x24, 0x16)   # warm orange -> deep brown
WOOD_HI, INK, WHITE = (0xC6, 0x8A, 0x4E), (0x1C, 0x10, 0x08), (255, 255, 255)

def gradient(w, h, c1, c2):
    g = Image.new("RGB", (w, h))
    px = g.load()
    for y in range(h):
        for x in range(w):
            t = (x / w * 0.45 + y / h * 0.55)
            px[x, y] = tuple(int(c1[i] + (c2[i] - c1[i]) * t) for i in range(3))
    return g

def char_mask():
    """rembg mask + the right arm it missed (bright skin against the dark night, thresholded in a box),
    eroded 1 px and feathered so no background fringe or rim halo survives."""
    m = MASK.copy()
    px, sp = m.load(), SRC.load()
    for y in range(462, 738):
        for x in range(722, 792):
            r, g, b = sp[x, y]
            if 0.299 * r + 0.587 * g + 0.114 * b > 95 and r > g > b and not (x > 760 and y > 712):
                px[x, y] = 255
    # the render has a near-white rim light down the left edge of the body: cut it (and a 2 px margin) out
    band = Image.eval(MASK.filter(ImageFilter.MinFilter(17)), lambda v: 255 - v)   # outer ~8 px of the silhouette
    bp = band.load()
    rim = Image.new("L", m.size, 0); rp = rim.load()
    for y in range(300, 780):
        for x in range(480, 600):
            r, g, b = sp[x, y]
            if bp[x, y] > 128 and min(r, g, b) > 150 and max(r, g, b) - min(r, g, b) < 90:
                rp[x, y] = 255
    rim = rim.filter(ImageFilter.MaxFilter(5))
    m = Image.composite(Image.new("L", m.size, 0), m, rim)
    m = m.filter(ImageFilter.MaxFilter(3)).filter(ImageFilter.MinFilter(5))   # close holes, then erode
    return m.filter(ImageFilter.GaussianBlur(1.2))

CM = char_mask()

def studio(w, h, cx, cy, r, hot=(0xFF, 0xB8, 0x5C), mid=(0xC4, 0x5E, 0x1E), edge=(0x24, 0x14, 0x0C)):
    """Clean studio backdrop: warm amber spotlight fading to deep brown, slight vignette."""
    g = Image.new("RGB", (w, h))
    px = g.load()
    for y in range(h):
        for x in range(w):
            d = min(1.0, (((x - cx) ** 2 + (y - cy) ** 2) ** 0.5) / r)
            if d < 0.45:
                t = d / 0.45; c = tuple(hot[i] + (mid[i] - hot[i]) * t for i in range(3))
            else:
                t = (d - 0.45) / 0.55; c = tuple(mid[i] + (edge[i] - mid[i]) * t for i in range(3))
            px[x, y] = tuple(int(v) for v in c)
    return g

def figure(scale, erode=0):
    """The character cut out of the source (RGBA) at the given scale, slightly punchier."""
    fg = ImageEnhance.Contrast(ImageEnhance.Color(SRC).enhance(1.1)).enhance(1.05)
    m = CM.filter(ImageFilter.MinFilter(erode)) if erode else CM
    fg = fg.convert("RGBA"); fg.putalpha(m)
    if scale != 1:
        fg = fg.resize((int(SRC.width * scale), int(SRC.height * scale)), Image.LANCZOS)
    return fg

def place(bg, fig, ox, oy, shadow=True):
    """Composite the figure with a soft contact shadow + subtle dark outline glow for depth."""
    bg = bg.convert("RGBA")
    if shadow:
        a = fig.split()[3]
        sh = Image.new("RGBA", fig.size, (12, 6, 2, 0)); sh.putalpha(a.point(lambda v: int(v * 0.55)))
        sh = sh.filter(ImageFilter.GaussianBlur(max(4, fig.width // 60)))
        bg.alpha_composite(sh, (ox + fig.width // 70, oy + fig.width // 90))
    bg.alpha_composite(fig, (ox, oy))
    return bg

def icon():
    # head + shoulders on a dark backdrop with a warm glow behind the head, for contrast at small sizes
    sc = S / 600.0
    fig = figure(sc, erode=3)
    bg = studio(S, S, S * 0.52, S * 0.36, S * 0.95, hot=(0xF2, 0x8C, 0x38), mid=(0x7A, 0x34, 0x12), edge=(0x1A, 0x0E, 0x08))
    fx, fy = 640 * sc, 300 * sc                         # face centre in scaled source
    img = place(bg, fig, int(S * 0.5 - fx), int(S * 0.45 - fy))
    mask = Image.new("L", (S, S), 0)
    ImageDraw.Draw(mask).rounded_rectangle([0, 0, S - 1, S - 1], radius=S * 0.22, fill=255)
    img.putalpha(mask)
    return img

def banner():
    W, H = 1280, 720
    img = studio(W, H, W * 0.27, H * 0.45, W * 0.62)
    sc = H * 0.98 / 1180
    fig = figure(sc)
    img = place(img, fig, int(W * 0.24 - 640 * sc), int(H * 0.02)).convert("RGB")
    d = ImageDraw.Draw(img)
    fpath = None
    for f in ("/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf",
              "/usr/share/fonts/truetype/liberation/LiberationSans-Bold.ttf"):
        if os.path.exists(f): fpath = f; break
    area_x0, area_w = W * 0.47, W * 0.48
    size = int(H * 0.22)
    while True:
        font = ImageFont.truetype(fpath, size) if fpath else ImageFont.load_default()
        l, t, r, b = d.textbbox((0, 0), "Stickman", font=font)
        if r - l <= area_w or not fpath: break
        size -= 4
    tx = area_x0 + (area_w - (r - l)) / 2 - l
    ty = (H - (b - t)) / 2 - t - H * 0.04
    # soft drop shadow, then clean white title
    sh = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    ImageDraw.Draw(sh).text((tx + 6, ty + 10), "Stickman", font=font, fill=(20, 8, 2, 170))
    img = img.convert("RGBA"); img.alpha_composite(sh.filter(ImageFilter.GaussianBlur(8)))
    d = ImageDraw.Draw(img)
    d.text((tx, ty), "Stickman", font=font, fill=WHITE)
    uy = ty + b + H * 0.045
    d.rounded_rectangle([tx + l, uy, tx + l + (r - l) * 0.38, uy + H * 0.02], radius=H * 0.01, fill=(0xFF, 0xD0, 0x8A))
    return img.convert("RGB")

def save(img, size, path):
    p = os.path.join(ROOT, path); os.makedirs(os.path.dirname(p), exist_ok=True)
    img.resize(size, Image.LANCZOS).save(p, optimize=True)

ic = icon()
for dpi, px in (("mdpi", 48), ("hdpi", 72), ("xhdpi", 96), ("xxhdpi", 144), ("xxxhdpi", 192)):
    save(ic, (px, px), f"res/mipmap-{dpi}/ic_launcher.png")
save(ic, (512, 512), "art/icon-512.png")
bn = banner()
# banner is 320x180 dp: give every density its real pixel size so Fire TV never upscales it
for dpi, f in (("xhdpi", 2), ("xxhdpi", 3), ("xxxhdpi", 4)):
    save(bn, (320 * f, 180 * f), f"res/drawable-{dpi}/banner.png")
save(bn, (640, 360), "art/banner-640.png")
print("icons written")
