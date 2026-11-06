"""Amazon Appstore listing art for the Appstore edition, written to store/appstore/assets/.
Reuses the drawing functions of make_icons.py (everything above its "ic = icon()" line), so nothing in res/ changes."""
import os
from PIL import Image, ImageFilter

HERE = os.path.dirname(os.path.abspath(__file__))
code = open(os.path.join(HERE, "make_icons.py")).read().split("\nic = icon()")[0]
g = {"__file__": os.path.join(HERE, "make_icons.py"), "__name__": "make_icons_lib"}
exec(compile(code, "make_icons.py", "exec"), g)
icon, banner, studio, figure, place = g["icon"], g["banner"], g["studio"], g["figure"], g["place"]

OUT = os.path.join(HERE, "..", "store", "appstore", "assets")
os.makedirs(OUT, exist_ok=True)

def out(img, size, name):
    img.resize(size, Image.LANCZOS).save(os.path.join(OUT, name), optimize=True)
    print(name, size)

ic = icon()
out(ic, (114, 114), "icon-small-114.png")
out(ic, (512, 512), "icon-large-512.png")
bn = banner()                                   # 1280x720, title on the right
out(bn, (1280, 720), "firetv-app-icon-1280x720.png")

def background():
    """Fire TV background: the figure on the right third, dark studio, no text (Amazon overlays the title)."""
    W, H = 1920, 1080
    img = studio(W, H, W * 0.78, H * 0.45, W * 0.55, hot=(0xC8, 0x6A, 0x28), mid=(0x5A, 0x26, 0x0E), edge=(0x12, 0x09, 0x05))
    sc = H * 0.96 / 1180
    fig = figure(sc)
    img = place(img, fig, int(W * 0.78 - 640 * sc), int(H * 0.04)).convert("RGB")
    # darken the left 60 % so Amazon's overlaid title and buttons stay readable
    shade = Image.new("L", (W, H), 0)
    px = shade.load()
    for x in range(W):
        a = int(150 * max(0.0, 1 - x / (W * 0.62)))
        for y in range(H):
            px[x, y] = a
    dark = Image.new("RGB", (W, H), (8, 4, 2))
    return Image.composite(dark, img, shade)

out(background(), (1920, 1080), "firetv-background-1920x1080.png")

def promo():
    """1024x500 feature graphic: the banner composition cropped to the wider ratio."""
    b = banner()                                 # 1280x720
    h = int(1280 * 500 / 1024)                   # 625
    top = (720 - h) // 2
    return b.crop((0, top, 1280, top + h))

out(promo(), (1024, 500), "promo-1024x500.png")
