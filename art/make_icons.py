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

def popped(blur, dark, char_boost=1.08):
    """Source with the background blurred + darkened and the character kept sharp (slightly brighter)."""
    bg = SRC.filter(ImageFilter.GaussianBlur(blur)) if blur else SRC.copy()
    bg = ImageEnhance.Brightness(bg).enhance(dark)
    fg = ImageEnhance.Contrast(ImageEnhance.Brightness(SRC).enhance(char_boost)).enhance(1.06)
    m = MASK.filter(ImageFilter.GaussianBlur(2))
    return Image.composite(fg, bg, m)

def icon():
    img = popped(blur=6, dark=0.45)
    # square crop centred on the face + upper torso (source px; face ~ (630, 290))
    box = (372, 52, 372 + 516, 52 + 516)
    img = img.crop(box).resize((S, S), Image.LANCZOS)
    img = ImageEnhance.Color(img).enhance(1.12).convert("RGBA")
    mask = Image.new("L", (S, S), 0)
    ImageDraw.Draw(mask).rounded_rectangle([0, 0, S, S], radius=S * 0.22, fill=255)
    img.putalpha(mask)
    return img

def banner():
    W, H = 1280, 720
    img = gradient(W, H, BG1, BG2).convert("RGB")
    # character on the left: scale the whole source so the figure fills the height
    sc = H / 1150
    pic = popped(blur=4, dark=0.40).crop((0, 20, SRC.width, 1170))
    pic = pic.resize((int(pic.width * sc), H), Image.LANCZOS)
    ox = int(W * 0.22 - 540 * sc)            # figure centre (~x540 incl. bat) -> 22% of width
    # fade the photo out towards the text area
    fade = Image.new("L", pic.size, 255)
    fp = fade.load()
    f0, f1 = int(W * 0.33) - ox, int(W * 0.46) - ox
    for x in range(pic.width):
        v = 255 if x < f0 else 0 if x >= f1 else int(255 * (f1 - x) / (f1 - f0))
        for y in range(pic.height): fp[x, y] = v
    # but never fade the character itself
    cm = MASK.crop((0, 20, SRC.width, 1170)).resize(pic.size, Image.LANCZOS)
    fade = Image.composite(Image.new("L", pic.size, 255), fade, cm)
    img.paste(pic, (ox, 0), fade)
    # darken the text side a bit so the warm gradient sits behind the title
    shade = Image.new("L", (W, H), 0)
    sp = shade.load()
    for x in range(W):
        v = int(max(0, min(1, (x - W * 0.45) / (W * 0.2))) * 110)
        for y in range(H): sp[x, y] = v
    img = Image.composite(Image.new("RGB", (W, H), INK), img, shade)
    d = ImageDraw.Draw(img)
    fpath = None
    for f in ("/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf",
              "/usr/share/fonts/truetype/liberation/LiberationSans-Bold.ttf"):
        if os.path.exists(f): fpath = f; break
    area_x0, area_w = W * 0.45, W * 0.52
    size = int(H * 0.22)
    while True:
        font = ImageFont.truetype(fpath, size) if fpath else ImageFont.load_default()
        l, t, r, b = d.textbbox((0, 0), "Stickman", font=font, stroke_width=max(1, size // 18))
        if r - l <= area_w or not fpath: break
        size -= 4
    sw = max(1, size // 18)
    tx = area_x0 + (area_w - (r - l)) / 2 - l
    ty = (H - (b - t)) / 2 - t - H * 0.03
    d.text((tx + 9, ty + 9), "Stickman", font=font, fill=INK, stroke_width=sw, stroke_fill=INK)
    d.text((tx, ty), "Stickman", font=font, fill=WHITE, stroke_width=sw, stroke_fill=(0x8B, 0x5A, 0x2B))
    # wood-coloured underline accent
    uy = ty + b + H * 0.04
    d.rounded_rectangle([tx + l, uy, tx + r, uy + H * 0.025], radius=H * 0.0125, fill=BG1)
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
