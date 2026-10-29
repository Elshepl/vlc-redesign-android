"""The UI tour's report: index.html (failures sorted by severity, then a contact sheet per screen across the
matrix, beside the desktop's Figma frame when there is one) and report.md (the same failures, for agents)."""
import glob, html, json, os, shutil, sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import checks

ORDER_DEV = ["phone-port", "phone-land", "tab11-land", "tab11-port", "tab13-land"]


def thumb(src, dst, width=220):
    from PIL import Image
    if os.path.exists(dst) and os.path.getmtime(dst) >= os.path.getmtime(src):
        return
    im = Image.open(src).convert("RGB")
    h = int(im.size[1] * width / im.size[0])
    im.resize((width, h)).save(dst, "JPEG", quality=72)


def fix_rotation(cell_dir):
    """The emulator's own screenshot is the panel as mounted: a turned display comes out sideways. Turned upright
    from the window's shape in the dump (once: the file is rewritten)."""
    from PIL import Image
    import device
    for png in glob.glob(os.path.join(cell_dir, "*.png")):
        js = png[:-4] + ".json.gz"
        if not os.path.exists(js):
            continue
        try:
            d = device.load_dump(js)
        except Exception:
            continue
        if not d.windows:
            continue
        b = d.windows[0]["b"]
        land = (b[2] - b[0]) > (b[3] - b[1])
        im = Image.open(png)
        if land != (im.size[0] > im.size[1]):
            im.rotate(90, expand=True).save(png)


def build(run_dir, out_dir, repo):
    os.makedirs(os.path.join(out_dir, "thumbs"), exist_ok=True)
    os.makedirs(os.path.join(out_dir, "shots"), exist_ok=True)
    idx = checks.Index(repo)
    cells, findings = [], []
    for cd in sorted(glob.glob(os.path.join(run_dir, "cells", "*"))):
        if not os.path.exists(os.path.join(cd, "steps.json")):
            continue
        fix_rotation(cd)
        dev = os.path.basename(cd).split("-")[0]
        lc = os.path.join(run_dir, "logs", "logcat-%s.txt" % dev)
        meta, found = checks.run_cell(cd, idx, lc)
        meta["dir"] = cd
        cells.append(meta)
        findings += found
    # screenshots copied (full size, for links) and thumbnails
    for m in cells:
        cname = m["cell"]["name"]
        for s in m["steps"]:
            if s.get("png"):
                src = os.path.join(m["dir"], s["png"])
                if os.path.exists(src):
                    dst = os.path.join(out_dir, "shots", cname, s["png"])
                    os.makedirs(os.path.dirname(dst), exist_ok=True)
                    if not os.path.exists(dst) or os.path.getmtime(dst) < os.path.getmtime(src):
                        shutil.copy2(src, dst)
                    thumb(src, os.path.join(out_dir, "thumbs", cname + "__" + s["png"][:-4] + ".jpg"))
    # group the findings: same check + same view on the same screen across cells
    groups = {}
    for f in findings:
        g = groups.setdefault((f["severity"], f["check"], f["step"].split("-")[0] if f["check"].startswith(("untranslated", "touch-target", "strictmode", "log-error", "no-content-description", "crash")) else f["step"], f["key"]), {"f": f, "cells": []})
        if f["cell"] not in g["cells"]:
            g["cells"].append(f["cell"])
    rows = sorted(groups.values(), key=lambda g: (checks.SEV[g["f"]["severity"]], -len(g["cells"]), g["f"]["check"], g["f"]["step"]))
    sev_count = {}
    for g in rows:
        sev_count[g["f"]["severity"]] = sev_count.get(g["f"]["severity"], 0) + 1
    total_secs = sum(m.get("seconds", 0) for m in cells)
    nshots = sum(1 for m in cells for s in m["steps"] if s.get("png"))
    with open(os.path.join(out_dir, "findings.json"), "w", encoding="utf-8") as f:
        json.dump([dict(g["f"], cells=g["cells"]) for g in rows], f, ensure_ascii=False, indent=1)

    # ---- markdown
    md = ["# VLC Redesign Android: UI tour report", "",
          "Cells: %d, screenshots: %d, tour time: %.0f min. Findings (grouped): %s." % (
              len(cells), nshots, total_secs / 60, ", ".join("%s %d" % (k, sev_count[k]) for k in sorted(sev_count, key=checks.SEV.get))), "",
          "| Severity | Check | Screen | Cells | Detail | Screenshot | Likely file |", "|---|---|---|---|---|---|---|"]
    for g in rows:
        f = g["f"]
        if f["severity"] == "info":
            continue
        shot = "shots/%s/%s" % (f["cell"], f["png"]) if f.get("png") else ""
        cl = ", ".join(g["cells"][:4]) + (" +%d" % (len(g["cells"]) - 4) if len(g["cells"]) > 4 else "")
        md.append("| %s | %s | %s | %d: %s | %s | %s | %s |" % (f["severity"], f["check"], f["step"], len(g["cells"]), cl,
                                                              f["detail"].replace("|", "/")[:200], shot, f.get("file", "")))
    with open(os.path.join(out_dir, "report.md"), "w", encoding="utf-8") as f:
        f.write("\n".join(md) + "\n")

    # ---- html
    by_screen = {}
    for m in cells:
        for s in m["steps"]:
            if s.get("png"):
                by_screen.setdefault(s["name"], {})[m["cell"]["name"]] = s
    cell_names = [m["cell"]["name"] for m in cells]
    layouts = []
    for c in cell_names:
        lay = "-".join(c.split("-")[:2])
        if lay not in layouts:
            layouts.append(lay)
    layouts.sort(key=lambda l: ORDER_DEV.index(l) if l in ORDER_DEV else 99)
    variants = []
    for c in cell_names:
        v = "-".join(c.split("-")[2:])
        if v not in variants:
            variants.append(v)
    e = html.escape
    h = ["<!doctype html><html lang=en><head><meta charset=utf-8><meta name=viewport content='width=device-width,initial-scale=1'>",
         "<title>Android UI tour</title><style>",
         ":root{--bg:#141414;--fg:#eee;--mut:#999;--card:#1e1e1e;--line:#333;--crit:#ff5a5a;--high:#ff9b4a;--med:#e6c84a;--low:#7aa7ff}",
         "@media (prefers-color-scheme: light){:root:not([data-theme=dark]){--bg:#f6f6f6;--fg:#111;--mut:#555;--card:#fff;--line:#ddd}}",
         "body{background:var(--bg);color:var(--fg);font:14px/1.4 system-ui,sans-serif;margin:0;padding:16px}",
         "table{border-collapse:collapse;width:100%;font-size:13px}td,th{border-bottom:1px solid var(--line);padding:4px 6px;vertical-align:top;text-align:left}",
         ".critical{color:var(--crit);font-weight:700}.high{color:var(--high);font-weight:600}.medium{color:var(--med)}.low{color:var(--low)}",
         ".sheet{overflow-x:auto;margin:8px 0 28px}.sheet table{width:auto}.sheet img{width:150px;display:block;border:1px solid var(--line)}",
         ".mut{color:var(--mut)}h2{margin-top:32px}a{color:inherit}.desk img{width:300px}details{margin:4px 0}",
         "</style></head><body>",
         "<h1>VLC Redesign Android: UI tour</h1>",
         "<p>%d cells, %d screenshots, %.0f min of tour. Findings grouped by check + view + screen: %s.</p>" % (
             len(cells), nshots, total_secs / 60, ", ".join("<span class=%s>%s %d</span>" % (k, k, sev_count[k]) for k in sorted(sev_count, key=checks.SEV.get))),
         "<h2>Failures by severity</h2><table><tr><th>Severity</th><th>Check</th><th>Screen</th><th>Cells</th><th>Detail</th><th>Shot</th><th>Likely file</th></tr>"]
    for g in rows:
        f = g["f"]
        if f["severity"] == "info":
            continue
        shot = ("<a href='shots/%s/%s'>%s</a>" % (e(f["cell"]), e(f["png"]), e(f["cell"]))) if f.get("png") else ""
        h.append("<tr><td class=%s>%s</td><td>%s</td><td><a href='#s-%s'>%s</a></td><td title='%s'>%d</td><td>%s</td><td>%s</td><td class=mut>%s</td></tr>" % (
            f["severity"], f["severity"], e(f["check"]), e(f["step"]), e(f["step"]), e(", ".join(g["cells"])), len(g["cells"]),
            e(f["detail"][:300]), shot, e(f.get("file", ""))))
    h.append("</table><h2>Screens across the matrix</h2>")
    for name in sorted(by_screen):
        cellmap = by_screen[name]
        any_s = next(iter(cellmap.values()))
        h.append("<h3 id='s-%s'>%s <span class=mut>%s</span></h3>" % (e(name), e(name), e(any_s.get("coverage") or "")))
        desk = any_s.get("desktop")
        if desk and os.path.exists(desk):
            dname = "desk_" + os.path.basename(desk)
            dst = os.path.join(out_dir, "thumbs", dname.replace(".png", ".jpg"))
            try:
                thumb(desk, dst, width=420)
                h.append("<div class=desk><span class=mut>Desktop (Figma) %s</span><br><img loading=lazy src='thumbs/%s'></div>" % (e(os.path.basename(desk)), e(os.path.basename(dst))))
            except Exception:
                pass
        h.append("<div class=sheet><table><tr><th></th>" + "".join("<th>%s</th>" % e(v) for v in variants) + "</tr>")
        for lay in layouts:
            tds = []
            for v in variants:
                c = "%s-%s" % (lay, v)
                s = cellmap.get(c)
                if s:
                    t = "thumbs/%s__%s.jpg" % (c, s["png"][:-4])
                    tds.append("<td><a href='shots/%s/%s'><img loading=lazy src='%s'></a></td>" % (e(c), e(s["png"]), e(t)))
                else:
                    tds.append("<td class=mut>-</td>")
            h.append("<tr><th>%s</th>%s</tr>" % (e(lay), "".join(tds)))
        h.append("</table></div>")
    h.append("<h2>Memory (dumpsys meminfo, KB)</h2><table><tr><th>Cell</th><th>PSS start</th><th>PSS end</th><th>Java heap end</th><th>Views end</th><th>Activities end</th><th>Tour s</th></tr>")
    for m in cells:
        c = m["cell"]
        ms, me = c.get("mem_start", {}), c.get("mem_end", {})
        h.append("<tr><td>%s</td><td>%s</td><td>%s</td><td>%s</td><td>%s</td><td>%s</td><td>%s</td></tr>" % (
            e(c["name"]), ms.get("pss_total_kb", ""), me.get("pss_total_kb", ""), me.get("java_heap_kb", ""), me.get("views", ""), me.get("activities", ""), m.get("seconds", "")))
    h.append("</table></body></html>")
    path = os.path.join(out_dir, "index.html")
    with open(path, "w", encoding="utf-8") as f:
        f.write("\n".join(h))
    return path


if __name__ == "__main__":
    print(build(sys.argv[1], sys.argv[2], sys.argv[3] if len(sys.argv) > 3 else os.path.abspath(os.path.join(HERE, "..", ".."))))
