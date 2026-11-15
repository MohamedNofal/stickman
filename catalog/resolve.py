#!/usr/bin/env python3
"""resolve.py - turn sources.json (hand-picked apps and where their publishers release them) into catalog.json.

For every app and CPU type it finds the newest release, downloads the APK once (cached), and records what
Stickman needs to install it safely: package, version code and name, minimum Android, size, SHA-256 and the signing
certificate. It also saves a banner/icon per app under icons/. Stickman only ever reads catalog.json, so a source
that changes its page breaks this script (and keeps the last good entry), never the app on the stick.

A file whose link and size are the same as in the last catalog.json is not downloaded again: its vetted entry
stands (--fresh downloads everything anyway). A source that fails keeps its last good entry. Every app is pinned in
sources.json to its package name and to the SHA-256 of its signing certificate ("package", "signer"): a release that
is another app or is signed with another key is refused until a person changes the pin, so a hijacked download page
cannot become an update. (An entry without pins is held to what the last catalogue had, and its key is printed.)

Usage: catalog/resolve.py [--only id,id] [--cache DIR] [--fresh]     needs aapt2, apksigner, python3 with Pillow.
GitHub requests use $GH_TOKEN / $GITHUB_TOKEN, or `gh auth token`, when there is one (rate limits).
The output (catalog.json, icons/) is published on the repository's "catalog" branch (by the workflow in catalog/catalog-workflow.yml once it is active, by hand until then);
it is not kept on main.
"""
import hashlib, io, json, os, re, subprocess, sys, time, urllib.request, zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
ABIS = ["armeabi-v7a", "arm64-v8a"]
UA = "stickman-catalog (+https://github.com/MohamedNofal/stickman)"


def token():
    t = os.environ.get("GH_TOKEN") or os.environ.get("GITHUB_TOKEN")
    if not t:
        try: t = subprocess.run(["gh", "auth", "token"], capture_output=True, text=True, timeout=10).stdout.strip()
        except Exception: t = ""
    return t


TOKEN = token()


def get(url, binary=False):
    h = {"User-Agent": UA}
    if url.startswith("https://api.github.com/") and TOKEN: h["Authorization"] = "Bearer " + TOKEN
    for attempt in range(3):
        try:
            with urllib.request.urlopen(urllib.request.Request(url, headers=h), timeout=120) as r:
                data = r.read()
            return data if binary else data.decode("utf-8", "replace")
        except Exception as e:
            err = e; time.sleep(2 + 3 * attempt)
    raise RuntimeError("GET %s: %s" % (url, err))


def vkey(v):
    return [int(x) for x in re.findall(r"\d+", v)]


def find_github(src, abi):
    rel = json.loads(get("https://api.github.com/repos/%s/releases/latest" % src["repo"]))
    name = src.get("abi_names", {}).get(abi, abi)
    rx = re.compile(src["asset"].replace("{abi}", re.escape(name)) + "$")
    for a in rel.get("assets", []):
        if rx.match(a["name"]):
            return a["browser_download_url"], {"downloads": a.get("download_count", 0), "published": rel.get("published_at", "")[:10], "size": a.get("size", -1)}
    raise RuntimeError("no asset matching %s in %s %s" % (rx.pattern, src["repo"], rel.get("tag_name")))


def find_index(src, abi):
    page = src["pages"][abi]
    rx = re.compile(src["match"].replace("{abi}", re.escape(abi)))
    hits = [(vkey(m.group(1)), m.group(0)) for m in rx.finditer(get(page))]
    if not hits: raise RuntimeError("nothing matching on " + page)
    return page + max(hits)[1], {}


def find_versioned(src, abi):
    vs = re.findall(src["version_match"], get(src["versions"]))
    if not vs: raise RuntimeError("no versions on " + src["versions"])
    for v in sorted(set(vs), key=vkey, reverse=True)[:4]:   # the newest folder can be empty while a release uploads
        url = src["file"].replace("{version}", v).replace("{abi}", abi)
        try:
            req = urllib.request.Request(url, method="HEAD", headers={"User-Agent": UA})
            with urllib.request.urlopen(req, timeout=60) as r:
                if r.status == 200: return url, {}
        except Exception:
            continue
    raise RuntimeError("no file for the newest versions of " + src["versions"])


def find_page(src, abi):
    rx = re.compile(src["match"].replace("{abi}", re.escape(abi)))
    hits = [(vkey(m.group(1)), m.group(0)) for m in rx.finditer(get(src["url"]))]
    if not hits: raise RuntimeError("nothing matching on " + src["url"])
    return max(hits)[1], {}


FINDERS = {"github": find_github, "index": find_index, "versioned": find_versioned, "page": find_page}


def remote_size(url):
    """The size the server states for url, without downloading it; -1 when it does not say."""
    try:
        req = urllib.request.Request(url, method="HEAD", headers={"User-Agent": UA})
        with urllib.request.urlopen(req, timeout=60) as r:
            return int(r.headers.get("Content-Length") or -1)
    except Exception:
        return -1


def download(url, cache):
    path = os.path.join(cache, hashlib.sha256(url.encode()).hexdigest()[:24] + ".apk")
    if not os.path.exists(path) or os.path.getsize(path) < 100000:
        subprocess.run(["curl", "-sS", "-L", "--fail", "--retry", "3", "-m", "900", "-A", UA, "-o", path + ".part", url], check=True)
        os.replace(path + ".part", path)
    return path


def inspect(path):
    out = subprocess.run(["aapt2", "dump", "badging", path], capture_output=True, text=True).stdout
    m = re.search(r"package: name='([^']+)' versionCode='(\d+)' versionName='([^']*)'", out)
    if not m: raise RuntimeError("not an APK aapt2 understands")
    info = {"package": m.group(1), "versionCode": int(m.group(2)), "versionName": m.group(3)}
    s = re.search(r"sdkVersion:'(\d+)'", out)
    info["minSdk"] = int(s.group(1)) if s else 1
    n = re.search(r"native-code: (.*)", out)
    info["abis"] = re.findall(r"'([^']+)'", n.group(1)) if n else []
    info["tv"] = "leanback-launchable-activity" in out or "android.software.leanback" in out
    b = re.search(r"banner='([^']+)'", out)
    icons = sorted(((int(d), p) for d, p in re.findall(r"application-icon-(\d+):'([^']+)'", out)), reverse=True)
    info["banner"] = b.group(1) if b else None
    info["icon"] = icons[0][1] if icons else None
    c = subprocess.run(["apksigner", "verify", "--print-certs", path], capture_output=True, text=True).stdout
    g = re.search(r"Signer #1 certificate SHA-256 digest: ([0-9a-f]{64})", c)
    if not g: raise RuntimeError("no valid signature")
    info["signer"] = g.group(1)
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""): h.update(chunk)
    info["sha256"] = h.hexdigest(); info["size"] = os.path.getsize(path)
    return info


def resource_table(path):
    """aapt2's resource dump as {resource id: (type/name, [(config, file)], colour)} plus file -> id."""
    out = subprocess.run(["aapt2", "dump", "resources", path], capture_output=True, text=True).stdout
    table = {}; by_file = {}; cur = None
    for line in out.splitlines():
        m = re.match(r"\s+resource (0x[0-9a-f]{8}) (\S+)", line)
        if m:
            cur = m.group(1); table[cur] = {"name": m.group(2), "files": [], "color": None}
            continue
        if cur is None: continue
        f = re.match(r"\s+\(([^)]*)\) \(file\) (\S+)", line)
        if f:
            table[cur]["files"].append((f.group(1), f.group(2))); by_file.setdefault(f.group(2), cur)
            continue
        c = re.match(r"\s+\([^)]*\) (#[0-9a-fA-F]{8})\s*$", line)
        if c and table[cur]["color"] is None: table[cur]["color"] = c.group(1)
    return table, by_file


def is_bitmap(head):
    """PNG, JPEG or WebP by content: some builds store images without a file extension (TizenTube's banner)."""
    return head[:8] == b"\x89PNG\r\n\x1a\n" or head[:3] == b"\xff\xd8\xff" or (head[:4] == b"RIFF" and head[8:12] == b"WEBP")


def flat(im):
    """A picture of one colour (Bare Launcher's banner is a plain dark rectangle): no use as art."""
    ext = im.convert("RGB").getextrema()
    return all(hi - lo < 8 for lo, hi in ext)


def raster(z, table, rid):
    """The largest bitmap among a drawable's density variants, as a PIL image, or None (vector only)."""
    from PIL import Image
    files = [f for _, f in table.get(rid, {}).get("files", []) if not f.lower().endswith(".xml")]
    for f in sorted(files, key=lambda n: z.getinfo(n).file_size, reverse=True):
        try:
            data = z.read(f)
            if not is_bitmap(data[:12]): continue
            im = Image.open(io.BytesIO(data)).convert("RGBA")
            if not flat(im): return im
        except Exception: continue
    return None


def adaptive(path, z, table, xml):
    """An adaptive icon/banner drawn the way a launcher would: its foreground bitmap over its background."""
    from PIL import Image
    tree = subprocess.run(["aapt2", "dump", "xmltree", "--file", xml, path], capture_output=True, text=True).stdout
    refs = dict(re.findall(r"E: (background|foreground)[^\n]*\n\s+A: [^\n]*drawable[^\n]*=@(0x[0-9a-f]{8})", tree))
    fg = raster(z, table, refs.get("foreground", ""))
    if fg is None: return None
    bg_res = table.get(refs.get("background", ""), {})
    bg = raster(z, table, refs.get("background", ""))
    if bg is None:
        col = bg_res.get("color") or "#ff202020"
        bg = Image.new("RGBA", fg.size, (int(col[3:5], 16), int(col[5:7], 16), int(col[7:9], 16), 255))
    bg = bg.resize(fg.size, Image.LANCZOS)
    bg.alpha_composite(fg)
    # launchers show the middle two thirds of the 108dp canvas
    w, h = bg.size; dx, dy = w // 6, h // 6
    return bg.crop((dx, dy, w - dx, h - dy))


def save_art(path, banner, icon, out):
    """A banner (TV launcher art, 16:9) when the app has one, else its icon; PNG, at most 320 px wide.
    Follows the resource table, so density variants and adaptive (XML) art resolve to real bitmaps."""
    from PIL import Image
    table, by_file = resource_table(path)
    with zipfile.ZipFile(path) as z:
        for ref in [banner, icon]:
            if not ref: continue
            rid = by_file.get(ref)
            im = raster(z, table, rid) if rid else None
            if im is None and ref.lower().endswith(".xml"):
                # the same resource may hold the XML for new systems only; else draw the adaptive layers
                im = adaptive(path, z, table, ref)
            if im is None and not ref.lower().endswith(".xml"):
                try:
                    data = z.read(ref)
                    im = Image.open(io.BytesIO(data)).convert("RGBA") if is_bitmap(data[:12]) else None
                    if im is not None and flat(im): im = None
                except Exception: im = None
            if im is None: continue
            if im.width > 320: im = im.resize((320, max(1, im.height * 320 // im.width)), Image.LANCZOS)
            im.save(out, "PNG", optimize=True)
            return "banner" if im.width > im.height * 1.3 else "icon"
    if os.path.exists(out): os.remove(out)
    return None


def resolve(app, cache, old, fresh):
    """One catalogue entry. old = the app's entry in the last catalog.json (or None); returns (entry, kept)."""
    src = app["source"]; files = {}; meta = {}; first = None
    icon = os.path.join(HERE, "icons", app["id"] + ".png")
    abis = ["any"] if src.get("any") else ABIS
    for abi in abis:
        url, m = FINDERS[src["type"]](src, ABIS[0] if abi == "any" else abi)
        size = m.pop("size", -1)
        meta.update(m)
        prev = (old or {}).get("files", {}).get(abi)
        if prev and not fresh and prev.get("url") == url and os.path.exists(icon):
            if size < 0: size = remote_size(url)
            if size == prev.get("size"):
                files[abi] = prev      # the file that was vetted last time is still what is published
                continue
        path = download(url, cache); info = inspect(path)
        if abi != "any" and info["abis"] and abi not in info["abis"]:
            raise RuntimeError("%s is not an %s build (%s)" % (url, abi, info["abis"]))
        if abi == "any" and info["abis"] and not set(ABIS) <= set(info["abis"]):
            raise RuntimeError("%s marked any, but only has %s" % (url, info["abis"]))
        if first and (info["package"], info["signer"]) != (first["package"], first["signer"]):
            raise RuntimeError("the %s build has a different package or signing key" % abi)
        # the pins: sources.json's own (reviewed by a person), else what the last catalogue had
        want_pkg = app.get("package") or (old or {}).get("package")
        want_key = app.get("signer") or (old or {}).get("signer")
        if want_pkg and info["package"] != want_pkg:
            raise RuntimeError("the file is %s, not %s" % (info["package"], want_pkg))
        if want_key and info["signer"] != want_key:
            raise RuntimeError("signed with another key (%s) than the pinned one (%s): if the publisher really changed "
                               "keys, change \"signer\" for this app in sources.json" % (info["signer"], want_key))
        if not app.get("signer"): print("note %-14s has no \"signer\" pin in sources.json yet: %s" % (app["id"], info["signer"]), flush=True)
        first = first or info
        files[abi] = {"url": url, "size": info["size"], "sha256": info["sha256"], "versionCode": info["versionCode"]}
        if "path" not in first: first["path"] = path
    if first is None:
        e = dict(old)                  # nothing new was published
        e["files"] = files
        kept = True
    else:
        art = save_art(first["path"], first["banner"], first["icon"], icon)
        e = {"package": first["package"], "version": first["versionName"], "minSdk": first["minSdk"], "tv": first["tv"],
             "signer": first["signer"], "files": files}
        if art: e["art"] = art
        kept = False
    e.update({k: app[k] for k in ("id", "name", "category", "summary", "homepage")})   # wording is edited in sources.json
    e["source"] = src["type"] + ":" + (src.get("repo") or src.get("url") or src.get("versions") or src["pages"][ABIS[0]])
    if meta.get("downloads"): e["downloads"] = meta["downloads"]
    if meta.get("published"): e["published"] = meta["published"]
    if src["type"] == "github":
        try: e["stars"] = json.loads(get("https://api.github.com/repos/" + src["repo"])).get("stargazers_count", 0)
        except Exception: pass
    order = ("id", "name", "category", "summary", "homepage", "package", "version", "minSdk", "tv", "signer", "source", "files", "art", "downloads", "published", "stars")
    return {k: e[k] for k in order if k in e}, kept


def main():
    args = sys.argv[1:]
    only = set(args[args.index("--only") + 1].split(",")) if "--only" in args else None
    fresh = "--fresh" in args
    cache = args[args.index("--cache") + 1] if "--cache" in args else os.path.join(os.environ.get("TMPDIR", "/tmp"), "stickman-catalog")
    os.makedirs(cache, exist_ok=True); os.makedirs(os.path.join(HERE, "icons"), exist_ok=True)
    apps = json.load(open(os.path.join(HERE, "sources.json")))["apps"]
    out_path = os.path.join(HERE, "catalog.json")
    old_doc = json.load(open(out_path)) if os.path.exists(out_path) else {}
    old = {a["id"]: a for a in old_doc.get("apps", [])}
    done = []; failed = []
    for app in apps:
        if only and app["id"] not in only:
            if app["id"] in old: done.append(old[app["id"]])
            continue
        try:
            e, kept = resolve(app, cache, old.get(app["id"]), fresh)
            done.append(e)
            print("%-4s %-14s %-28s %-12s %s" % ("same" if kept else "ok", e["id"], e["package"], e["version"],
                  ",".join("%s %.0fMB" % (k, v["size"] / 1e6) for k, v in e["files"].items())), flush=True)
        except Exception as ex:
            failed.append(app["id"]); print("FAIL %-14s %s" % (app["id"], ex), flush=True)
            if app["id"] in old: done.append(old[app["id"]])   # keep the last good entry
    # art of apps that are no longer in sources.json goes with them
    ids = {a["id"] for a in done}
    for f in os.listdir(os.path.join(HERE, "icons")):
        if f.endswith(".png") and f[:-4] not in ids: os.remove(os.path.join(HERE, "icons", f))
    json.dump({"format": 1, "generated": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()), "apps": done},
              open(out_path, "w"), indent=1, ensure_ascii=False)
    print("%d apps in catalog.json, %d failed%s" % (len(done), len(failed), ": " + ",".join(failed) if failed else ""))
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
