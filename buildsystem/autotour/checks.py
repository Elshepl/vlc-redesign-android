"""The automatic checks of the UI tour, run on what a cell saved (dumps, screenshots, logcat):
crashes / ANRs / StrictMode / app errors per step, ellipsized or cut text, overlapping text, touch targets under
48 dp, views outside their window or clipped by a non-scrolling parent, hard-coded orange (in the blue-accent
cells), untranslated strings (against values-<lang>/), clickable images with no content description, memory."""
import colorsys, glob, gzip, json, os, re
import xml.etree.ElementTree as ET

SEV = {"critical": 0, "high": 1, "medium": 2, "low": 3, "info": 4}
LANG_DIRS = {"he": ("values-he", "values-iw"), "de": ("values-de",)}


class Index:
    """Which layout declares an id, which strings each language has (for 'likely file' and translations)."""

    def __init__(self, repo):
        self.ids, self.keys = {}, {}
        app = os.path.join(repo, "application")
        for f in glob.glob(os.path.join(app, "*", "res", "layout*", "*.xml")) + glob.glob(os.path.join(app, "*", "src", "main", "res", "layout*", "*.xml")):
            try:
                txt = open(f, encoding="utf-8").read()
            except Exception:
                continue
            for m in re.finditer(r'@\+id/([A-Za-z0-9_]+)', txt):
                self.ids.setdefault(m.group(1), set()).add(os.path.relpath(f, repo).replace("\\", "/"))
        self.lang = {}
        for lang, dirs in LANG_DIRS.items():
            keys = set()
            for dname in dirs:
                for f in glob.glob(os.path.join(app, "*", "**", dname, "*.xml"), recursive=True):
                    try:
                        for el in ET.parse(f).getroot():
                            if el.get("name"):
                                keys.add(el.get("name"))
                    except Exception:
                        pass
            self.lang[lang] = keys
        self.string_file = {}
        self.fixed = set()
        for f in glob.glob(os.path.join(app, "*", "**", "values", "*.xml"), recursive=True):
            try:
                for el in ET.parse(f).getroot():
                    if el.get("translatable") == "false" and el.get("name"):
                        self.fixed.add(el.get("name"))
                    if el.tag == "string" and el.get("name"):
                        self.string_file.setdefault(el.get("name"), os.path.relpath(f, repo).replace("\\", "/"))
            except Exception:
                pass

    def likely(self, view, step):
        frag = (step.get("fragments") or [None])[-1] if step else None
        where = frag or (step or {}).get("activity") or ""
        files = self.ids.get((view or {}).get("id"), ())
        if len(files) == 1:
            return next(iter(files)) + (" (" + where + ")" if where else "")
        if files:
            return "%s; @id/%s is in %d layouts" % (where, view["id"], len(files))
        return where


def load(path):
    import device
    with gzip.open(path, "rt", encoding="utf-8") as f:
        return device.normalize(json.load(f))


def inter(a, b):
    x0, y0, x1, y1 = max(a[0], b[0]), max(a[1], b[1]), min(a[2], b[2]), min(a[3], b[3])
    return max(0, x1 - x0) * max(0, y1 - y0)


def area(r):
    return max(0, r[2] - r[0]) * max(0, r[3] - r[1])


def is_orange(hexstr):
    try:
        c = int(hexstr[1:], 16)
    except Exception:
        return False
    a, r, g, b = (c >> 24) & 255, (c >> 16) & 255, (c >> 8) & 255, c & 255
    if a < 80:
        return False
    h, s, v = colorsys.rgb_to_hsv(r / 255, g / 255, b / 255)
    return 0.05 <= h <= 0.11 and s > 0.6 and v > 0.6


def ancestors(views, i):
    out = set()
    p = views[i].get("p", -1)
    while p >= 0:
        out.add(p)
        p = views[p].get("p", -1)
    return out


def proper_noun(t):
    """Names that stay as they are in every language (VLC, AudioTrack, OpenSL ES, a licence, a URL)."""
    t = t.strip()
    return bool(re.search(r"VLC|©|GNU|https?:|@|\.(com|org)", t) or re.fullmatch(r"[A-Z0-9][A-Z0-9 .+/-]*|[A-Z][a-z]+[A-Z]\w*( \w+)?|[\d\s.,:%x×/-]+", t))


def media_text(v):
    """A text of the test media / user data (not the app's): only a string key makes it the app's."""
    return not v.get("sk")


def check_dump(dump, step, cell, idx):
    out = []
    dens = dump.get("config", {}).get("density", 2.625)
    lang = cell.get("lang")
    name = step["name"]

    def add(sev, check, detail, view=None, key=None):
        out.append({"severity": sev, "check": check, "step": name, "detail": detail, "view": (view or {}).get("id") or (view or {}).get("sk") or (view or {}).get("c", ""),
                    "key": key or "%s|%s" % (check, (view or {}).get("id") or (view or {}).get("sk") or (view or {}).get("c", "")),
                    "file": idx.likely(view, step)})

    for w in dump.get("windows", []):
        views = w["views"]
        wb = w["b"]
        texts = []
        for i, v in enumerate(views):
            if v.get("hidden") or (v.get("alpha", 1) < 0.1):
                continue
            vis = v.get("vis") or v["b"]
            txt = v.get("txt") or ""
            # text
            if txt.strip():
                ui = not media_text(v)
                if v.get("ell"):
                    if ui:
                        add("medium", "ellipsized", "'%s' ends in an ellipsis (%d chars hidden)" % (txt[:60], v["ell"]), v)
                    elif "long" not in txt.lower() and len(txt) < 24:
                        add("low", "ellipsized-short", "short media text '%s' ellipsized" % txt[:60], v)
                for k, label in (("cutw", "wider than its view, cut with no ellipsis"), ("cuth", "taller than its view, the bottom cut"),
                                 ("cutlines", "more lines than maxLines, cut with no ellipsis")):
                    if v.get(k):
                        add("high" if ui else "medium", "text-cut", "'%s' %s (%s %s)" % (txt[:60], label, v[k], "lines" if k == "cutlines" else "px"), v)
                if v.get("tr") and area(v["tr"]) > 0:
                    texts.append((i, v))
                # translation
                if lang in ("he", "de") and v.get("sk") and v.get("sken") and not proper_noun(txt):
                    key = v["sk"]
                    if key not in idx.lang.get(lang, ()) and key not in idx.fixed:
                        add("medium", "untranslated", "'%s' (%s) has no values-%s translation" % (txt[:60], key, lang), v, key="untranslated|" + key)
            if lang in ("he", "de") and v.get("cdk") and v.get("cdken") and v["cdk"] not in idx.lang.get(lang, ()):
                add("low", "untranslated", "content description '%s' (%s) has no values-%s translation" % ((v.get("cd") or "")[:60], v["cdk"], lang), v, key="untranslated|" + v["cdk"])
            # touch target
            if v.get("clk") and not v.get("dis") and i > 0:
                wpx, hpx = vis[2] - vis[0], vis[3] - vis[1]
                full = v["b"][2] - v["b"][0], v["b"][3] - v["b"][1]
                if min(full) / dens < 47.5 and min(full) > 0 and not v.get("insc", False) or (min(full) / dens < 40 and min(full) > 0):
                    add("low", "touch-target", "%s %dx%d dp (< 48 dp)" % (v.get("id") or v.get("c"), full[0] / dens, full[1] / dens), v)
            # outside the window / clipped by a parent that does not scroll
            has_content = txt.strip() or v.get("img") not in (None, "none") or v.get("clk")
            if has_content and i > 0 and not v.get("insc"):
                b = v["b"]
                par = views[v["p"]] if v.get("p", -1) >= 0 else None
                pb = par["b"] if par else wb
                par_out = pb[0] < wb[0] - 2 or pb[1] < wb[1] - 2 or pb[2] > wb[2] + 2 or pb[3] > wb[3] + 2
                sheet = par is not None and ("Coordinator" in par.get("c", "") or "Motion" in par.get("c", ""))
                if (b[0] < wb[0] - 2 or b[1] < wb[1] - 2 or b[2] > wb[2] + 2 or b[3] > wb[3] + 2) and not par_out and not sheet:
                    add("medium", "outside-window", "%s %s reaches outside its window %s" % (v.get("id") or v.get("c"), b, wb), v)
                elif txt.strip() and area(vis) < area(b) * 0.9 and area(b) > 0:
                    add("medium", "clipped", "'%s' shown %d%% (clipped by a parent)" % (txt[:50], 100 * area(vis) // area(b)), v)
            if v.get("insc") and txt.strip() and v.get("clk") and area(v["b"]) > 0:
                # a button cut at the side of a row that scrolls sideways (the row wider than its window)
                b = v["b"]
                if (vis[0] > b[0] + 4 or vis[2] < b[2] - 4) and (vis[3] - vis[1]) > (b[3] - b[1]) * 0.8 and area(vis) < area(b) * 0.9:
                    add("medium" if w is not dump["windows"][0] else "low", "button-cut-at-edge", "'%s' shown %d%% at the window's edge (a row wider than its window)" % (txt[:40], 100 * area(vis) // area(b)), v)
            # content description
            if v.get("clk") and "Image" in v.get("c", "") and not v.get("cd") and not v.get("tip") and not v.get("ifa"):
                kids_text = any((vv.get("txt") or "").strip() and vv.get("p") == i for vv in views)
                if not kids_text:
                    add("medium", "no-content-description", "clickable %s has no content description" % (v.get("id") or v.get("c")), v)
            # accent
            if cell.get("accent") == "blue":
                for k in ("tc", "tint", "bgt", "bg"):
                    if v.get(k) and is_orange(v[k]):
                        add("high", "orange-hardcoded", "%s %s=%s is orange with the blue accent" % (v.get("id") or v.get("c"), k, v[k]), v)
        # overlapping texts
        for a in range(len(texts)):
            ia, va = texts[a]
            anc_a = ancestors(views, ia)
            for b in range(a + 1, len(texts)):
                ib, vb = texts[b]
                anc_b = ancestors(views, ib)
                if ia in anc_b or ib in anc_a:
                    continue
                # a bar or sheet drawn over scrolling content, or two layers of a frame: not an overlap of a layout
                if bool(va.get("insc")) != bool(vb.get("insc")):
                    continue
                common = [p for p in sorted(anc_a & anc_b, reverse=True)]
                lca = views[common[0]] if common else None
                if lca is None or re.search(r"Coordinator|FrameLayout|DecorView|Drawer|Sliding|MotionLayout", lca.get("c", "")) or lca.get("scroll"):
                    continue
                ra = clip(va["tr"], va.get("vis") or va["b"])
                rb = clip(vb["tr"], vb.get("vis") or vb["b"])
                o = inter(ra, rb)
                if o > 0.15 * min(area(ra), area(rb)) and o > 40:
                    add("high", "overlap", "'%s' overlaps '%s'" % ((va.get("txt") or "")[:40], (vb.get("txt") or "")[:40]), va,
                        key="overlap|%s|%s" % (va.get("id") or va.get("sk") or va.get("c"), vb.get("id") or vb.get("sk") or vb.get("c")))
    return out


def clip(r, c):
    return [max(r[0], c[0]), max(r[1], c[1]), min(r[2], c[2]), min(r[3], c[3])]


def orange_pixels(png, dump):
    """Orange pixels outside the media (video surfaces, bitmaps): count and their box."""
    from PIL import Image
    im = Image.open(png).convert("RGB")
    W, H = im.size
    small = im.resize((W // 4, H // 4))
    masks = []
    for w in dump.get("windows", []):
        for v in w["views"]:
            if v.get("media") or v.get("img") == "bitmap" or "Surface" in v.get("c", "") or "Texture" in v.get("c", ""):
                masks.append(v.get("vis") or v["b"])
    px = small.load()
    n, box = 0, [W, H, 0, 0]
    for y in range(small.size[1]):
        for x in range(small.size[0]):
            r, g, b = px[x, y]
            if r < 150 or r - b < 90:
                continue
            h, s, v = colorsys.rgb_to_hsv(r / 255, g / 255, b / 255)
            if 0.05 <= h <= 0.11 and s > 0.6 and v > 0.6:
                X, Y = x * 4, y * 4
                if any(m[0] <= X < m[2] and m[1] <= Y < m[3] for m in masks):
                    continue
                n += 1
                box = [min(box[0], X), min(box[1], Y), max(box[2], X), max(box[3], Y)]
    return n, box


NOISE = re.compile(r"s_glBindAttribLocation|eglCodecCommon|EGL_emulation|HostConnection|libEGL|gralloc|Davey|chatty|FrameEvents|"
                   r"FrameTracker|request 3 not implemented|androidx.car.app.connection|goldfish|ranchu|Gralloc|qemu")


def logcat_findings(lines, step, pid):
    out = []
    pid = (pid or "").split()[0] if pid else ""
    for n, ln in enumerate(lines):
        if "FATAL EXCEPTION" in ln or ("AndroidRuntime" in ln and "Process: org.kuchisofts.vlcredesign.debug" in ln):
            ctx = " / ".join(l.strip()[-160:] for l in lines[n + 1:n + 4])
            out.append(("critical", "crash", ln.strip()[-200:] + " " + ctx, "crash|" + re.sub(r"\d+", "#", ctx[:120])))
        elif "ANR in org.kuchisofts.vlcredesign.debug" in ln:
            out.append(("critical", "anr", ln.strip()[-300:], "anr|" + step["name"]))
        elif "StrictMode" in ln and "policy violation" in ln and (not pid or (" %s " % pid) in ln):
            kind = re.search(r"strictmode\.(\w+)", ln)
            frame = ""
            for l in lines[n + 1:n + 40]:
                m = re.search(r"at (org\.videolan\.[\w.$]+\([\w.]+:\d+\))", l)
                if m and "autotour" not in m.group(1):
                    frame = m.group(1); break
            k = (kind.group(1) if kind else "violation")
            out.append(("low", "strictmode", "%s at %s" % (k, frame or "(no app frame)"), "strictmode|%s|%s" % (k, re.sub(r":\d+", "", frame))))
        elif pid and (" %s " % pid) in ln and re.search(r"\s[EF]\s", ln):
            if NOISE.search(ln):
                continue
            msg = re.sub(r"^.*?\s[EF]\s+", "", ln.strip())
            sev = "low" if re.search(r"libvlc |VLC-std", msg) else ("medium" if re.search(r"VLC|videolan|Invalid resource", msg) else "low")
            out.append((sev, "log-error", msg[-240:], "log|" + re.sub(r"\[[0-9a-f/]+\]|0x[0-9a-f]+|[0-9a-f]{8,}|\d+", "#", msg)[:120]))
    return out


def run_cell(cell_dir, idx, logcat_path):
    meta = json.load(open(os.path.join(cell_dir, "steps.json"), encoding="utf-8"))
    cell, steps = meta["cell"], meta["steps"]
    found = []
    log = []
    if logcat_path and os.path.exists(logcat_path):
        with open(logcat_path, "rb") as f:
            data = f.read()
    else:
        data = b""
    for s in steps:
        base = {"cell": cell["name"], "png": s.get("png"), "step": s["name"]}
        if s.get("fail"):
            found.append(dict(base, severity="medium" if "Lost" in s["fail"] or "refused" in s["fail"] else "high",
                              check="tour-step-failed", detail=s["fail"], view="", key="fail|" + s["name"], file=""))
            continue
        jp = os.path.join(cell_dir, s["name"] + ".json.gz")
        if not os.path.exists(jp):
            continue
        dump = load(jp)
        if not dump.get("windows"):
            found.append(dict(base, severity="high", check="app-not-in-front", detail="no window of the app (crashed, closed, or another app in front) %s" % (dump.get("error") or ""),
                              view="", key="nowindow|" + s["name"], file=""))
        for f in check_dump(dump, s, cell, idx):
            found.append(dict(base, **f))
        if cell.get("accent") == "blue" and s.get("png") and os.path.exists(os.path.join(cell_dir, s["png"])):
            n, box = orange_pixels(os.path.join(cell_dir, s["png"]), dump)
            if n > 40:
                found.append(dict(base, severity="high", check="orange-pixels", detail="%d orange pixel blocks outside media with the blue accent, box %s" % (n, box),
                                  view="", key="orangepx|" + s["name"], file=s.get("fragments", [""])[-1] if s.get("fragments") else s.get("activity", "")))
        a, b = s.get("log", [0, 0])
        lines = data[a:b].decode("utf-8", "replace").splitlines()
        for sev, check, detail, k in logcat_findings(lines, s, s.get("pid")):
            found.append(dict(base, severity=sev, check=check, detail=detail, view="", key=k, file=""))
    for e in cell.get("dropbox", []):
        found.append({"cell": cell["name"], "png": None, "step": "system", "severity": "critical",
                      "check": "crash" if "crash" in e["tag"] else "anr", "detail": "%s: %s %s" % (e["tag"], e["head"], e["frame"]),
                      "view": "", "key": "dropbox|" + re.sub(r"\{[^}]*\}|\d+", "#", e["head"])[:150], "file": e["frame"]})
    ms, me = cell.get("mem_start", {}), cell.get("mem_end", {})
    if ms.get("pss_total_kb") and me.get("pss_total_kb") and me["pss_total_kb"] > ms["pss_total_kb"] * 1.4 and me["pss_total_kb"] - ms["pss_total_kb"] > 60000:
        found.append({"cell": cell["name"], "png": None, "step": "memory", "severity": "medium", "check": "memory-growth",
                      "detail": "PSS %d -> %d KB over the cell" % (ms["pss_total_kb"], me["pss_total_kb"]), "view": "", "key": "mem", "file": ""})
    return meta, found
