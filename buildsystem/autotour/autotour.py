"""VLC Redesign for Android: the automated UI tour.

    py -3 buildsystem/autotour/autotour.py                  # the full matrix, then the report
    py -3 buildsystem/autotour/autotour.py --quick          # phone portrait, dark + light English only
    py -3 buildsystem/autotour/autotour.py --devices phone --cells phone-port-dark-he-f1.0 --only player
    py -3 buildsystem/autotour/autotour.py --report-only    # the report again from the last run

It boots one emulator of its own (port 5558, AVDs in D:\\vlc-redesign-tmp\\android-autotour\\avd; never the owner's
phone), installs the debug APK, pushes generated media, starts the Live TV mock server, walks every screen in every
cell of the matrix and writes the report (report.py) to D:\\vlc-redesign-tmp\\android-autotour\\report."""
import argparse, json, os, re, subprocess, sys, time

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import device, media, mockserver, tour

ROOT = r"D:\vlc-redesign-tmp\android-autotour"
REPO = os.path.abspath(os.path.join(HERE, "..", ".."))
import glob
# the debug APK of this checkout, whatever its version name (VLC-Android-<version>-debug-all.apk)
APK = (sorted(glob.glob(os.path.join(REPO, "application", "app", "build", "outputs", "apk", "debug", "VLC-Android-*-debug-all.apk")),
              key=os.path.getmtime) or [""])[-1]

# device -> AVD, orientations (user_rotation 0 = the device's natural orientation)
DEVICES = {
    "phone": ("AT_Phone_API34", [("port", 0), ("land", 1)]),
    "tab11": ("AT_Tab11_API34", [("land", 0), ("port", 1)]),
    "tab13": ("AT_Tab13_API34", [("land", 0)]),
}
# per orientation: dark/light x en/he/de at font 1.0; font 1.3 in German dark and Hebrew light
BASE = [(t, l, 1.0) for t in ("dark", "light") for l in ("en", "he", "de")] + [("dark", "de", 1.3), ("light", "he", 1.3)]
LOCALES = {"en": "en", "he": "he", "de": "de"}


def cells_for(dev, quick=False):
    avd, orients = DEVICES[dev]
    out = []
    for o, rot in orients:
        for theme, lang, font in BASE:
            if quick and (lang != "en" or font != 1.0):
                continue
            out.append({"name": "%s-%s-%s-%s-f%.1f" % (dev, o, theme, lang, font), "device": dev, "orient": o, "rotation": rot,
                        "theme": theme, "lang": lang, "font": font, "accent": "orange"})
    if dev == "phone" and not quick:
        # the accent probe: blue accent, orange anywhere outside the media is a hard-coded colour
        for theme in ("dark", "light"):
            out.append({"name": "phone-port-%s-en-f1.0-blue" % theme, "device": dev, "orient": "port", "rotation": 0, "theme": theme,
                        "lang": "en", "font": 1.0, "accent": "blue"})
    return out


def setup(d, log):
    d.prepare()
    d.install(APK)
    # once per AVD and app (the package name changed once: a new app has no library)
    marker = "/data/local/tmp/autotour-library-" + device.PKG
    first = "yes" not in d.sh("[ -f %s ] && echo yes" % marker)
    d.sh("mkdir -p /sdcard/Download")
    tree = media.generate(os.path.join(ROOT, "media"))
    log(device.adb_raw("push", tree, "/sdcard/Download/", timeout=900).strip()[-120:])
    d.sh("appops set %s MANAGE_EXTERNAL_STORAGE allow" % device.PKG)
    for perm in ("READ_MEDIA_VIDEO", "READ_MEDIA_AUDIO", "READ_MEDIA_IMAGES", "POST_NOTIFICATIONS", "READ_EXTERNAL_STORAGE"):
        d.sh("pm grant %s android.permission.%s" % (device.PKG, perm))
    d.stop_app()
    d.start_app(wait=False)
    # the first-run dialog is a screen too: kept in the first cell's folder later; then never again.
    # The probe's socket comes up with the app: on a freshly booted 13" tablet 4 s were not enough, the
    # writes went nowhere and the first cell stayed on the welcome screen
    deadline = time.time() + 60
    while time.time() < deadline and not (d.call("pref", key="app_onboarding_done", type="b", value="true") or "").startswith("ok"):
        time.sleep(1)
    for k, t, v in (("app_onboarding_done", "b", "true"), ("show_update", "b", "false"), ("app_theme", "s", "-1"), ("incognito_mode", "b", "false"),
                    ("rd_accent", "s", "orange")):
        r = d.call("pref", key=k, type=t, value=v)
        if not (r or "").startswith("ok"):
            log("pref %s not written: %s" % (k, r))
    d.stop_app()
    if first:
        # once per AVD: adding the folder again starts the parsing service again (see the findings: it crashed)
        d.start_app("--es debug_library_folder /storage/emulated/0/Download/vlc-redesign-test")
        d.sh("touch %s" % marker)
    else:
        d.start_app()
    spec = json.dumps(mockserver.sources())
    log("seed tv: %s" % d.call("seedtv", timeout=60, spec=spec))
    time.sleep(8)  # the library scan and the TV sources load


def pref_sure(d, key, type, value, wait=60):
    """Writes a preference through the probe, starting the app and retrying until the probe says ok: right
    after a boot (or the system's font/night change) its socket is not up yet and a write went nowhere (the
    first cell then ran in the last cell's language and accent)."""
    deadline = time.time() + wait
    while True:
        r = d.call("pref", key=key, type=type, value=value)
        if (r or "").startswith("ok") or time.time() > deadline:
            return r
        if r is None:
            d.start_app(wait=False)
        time.sleep(1)


def configure(d, cell):
    d.set_system(night=cell["theme"] == "dark", font=cell["font"], rotation=cell["rotation"])
    for k, t, v in (("set_locale", "s", LOCALES[cell["lang"]]), ("rd_accent", "s", cell["accent"]), ("app_theme", "s", "-1")):
        r = pref_sure(d, k, t, v)
        if not (r or "").startswith("ok"):
            print("  configure(): pref %s not written: %s" % (k, r), flush=True)
    d.stop_app()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--devices", default="phone,tab11,tab13")
    ap.add_argument("--cells", default="", help="comma list of cell names (default: all)")
    ap.add_argument("--only", default="", help="comma list of step prefixes")
    ap.add_argument("--quick", action="store_true")
    ap.add_argument("--keep", action="store_true", help="leave the emulator running at the end")
    ap.add_argument("--resume", action="store_true", help="skip the cells the run folder already has")
    ap.add_argument("--report-only", action="store_true")
    ap.add_argument("--run", default=os.path.join(ROOT, "run"))
    ap.add_argument("--report", default=os.path.join(ROOT, "report"), help="the folder of the report")
    a = ap.parse_args()
    os.makedirs(a.run, exist_ok=True)
    logf = open(os.path.join(a.run, "autotour.log"), "a", encoding="utf-8")

    def log(msg):
        line = "%s %s" % (time.strftime("%H:%M:%S"), msg)
        print(line, flush=True)
        logf.write(line + "\n"); logf.flush()

    if not a.report_only:
        srv = mockserver.start(os.path.join(ROOT, "media"), logfile=os.path.join(a.run, "mockserver.log"))
        media.stream_file(os.path.join(ROOT, "media"))
        want = set(filter(None, a.cells.split(",")))
        only = list(filter(None, a.only.split(","))) or None
        t_all = time.time()
        for dev in a.devices.split(","):
            cells = [c for c in cells_for(dev, a.quick) if not want or c["name"] in want]
            if a.resume:
                cells = [c for c in cells if not os.path.exists(os.path.join(a.run, "cells", c["name"], "steps.json"))]
            if not cells:
                continue
            avd = DEVICES[dev][0]
            emu = device.Emulator(avd, os.path.join(a.run, "logs"))
            started = False
            if device.serial_online():
                log("%s already online: using it (%s)" % (device.SERIAL, avd))
            else:
                log("booting %s (free RAM %d MB)" % (avd, device.free_ram_mb()))
                log("booted in %.0f s" % emu.start())
                started = True
            os.makedirs(os.path.join(a.run, "logs"), exist_ok=True)
            logcat_path = os.path.join(a.run, "logs", "logcat-%s.txt" % dev)
            lc = None
            try:
                d = device.Device()
                setup(d, log)
                device.adb_raw("logcat", "-c")
                lc = subprocess.Popen([device.ADB, "-s", device.SERIAL, "logcat", "-v", "epoch", "-v", "uid"],
                                      stdout=open(logcat_path, "ab"), stderr=subprocess.DEVNULL)
                mem = {"start": d.meminfo()}
                for cell in cells:
                    t0 = time.time()
                    lc_from = os.path.getsize(logcat_path) if os.path.exists(logcat_path) else 0
                    configure(d, cell)
                    out = os.path.join(a.run, "cells", cell["name"])
                    t = tour.Tour(d, cell, out, logcat_path, only)
                    t.mem_start = d.meminfo()
                    t.run()
                    d.start_app(wait=False); time.sleep(2)
                    t.cell["mem_end"] = d.meminfo()
                    t.cell["mem_start"] = t.mem_start
                    t.save()
                    fails = sum(1 for s in t.steps if s.get("fail"))
                    # ANRs and crashes of the app in this cell's part of the logcat: a step can still pass after
                    # Android killed a frozen app and the tour started it again (the tablet Playlists loop did)
                    with open(logcat_path, "rb") as f:
                        f.seek(lc_from)
                        part = f.read().decode("utf-8", "replace")
                    anrs = part.count("ANR in " + device.PKG)
                    crashes = len(re.findall(r"FATAL EXCEPTION.*\n.*Process: " + re.escape(device.PKG), part))
                    log("%s: %d screens, %d step failures, %d ANRs, %d crashes, %.0f s"
                        % (cell["name"], len(t.steps), fails, anrs, crashes, time.time() - t0))
                mem["end"] = d.meminfo()
                with open(os.path.join(a.run, "mem-%s.json" % dev), "w") as f:
                    json.dump(mem, f)
            finally:
                if lc:
                    lc.terminate()
                if started and not a.keep:
                    emu.stop()
                    log("emulator stopped")
        log("tour done in %.0f min" % ((time.time() - t_all) / 60))
        srv.shutdown()
    import report
    path = report.build(a.run, a.report, REPO)
    log("report: " + path)


if __name__ == "__main__":
    main()
