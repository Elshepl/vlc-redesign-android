"""The emulator and the app, for the UI tour: start / stop one AVD on port 5558 (never another serial, never a
real phone), adb helpers, the in-app probe (src/debug AutoTourProbe), screenshots, taps by resource id or string
key, waits for a settled screen instead of sleeps."""
import gzip, hashlib, json, os, re, socket, subprocess, time

SDK = os.path.join(os.environ.get("LOCALAPPDATA", ""), "Android", "Sdk")
ADB = os.path.join(SDK, "platform-tools", "adb.exe")
EMULATOR = os.path.join(SDK, "emulator", "emulator.exe")
SERIAL = "emulator-5558"
PORT = 5558
PKG = "org.videolan.vlc.debug"
RECEIVER = PKG + "/org.videolan.vlc.autotour.AutoTourReceiver"
DUMP_FILE = "/sdcard/Android/data/%s/files/autotour-dump.json" % PKG
AVD_HOME = r"D:\vlc-redesign-tmp\android-autotour\avd"
MIN_FREE_MB = 4096
FWD_PORT = 27583
# taps never go to a renderer row other than the fake one, nor to anything that deletes or resets
DANGER = re.compile(r"delete|remove|clear|reset_|erase|uninstall|renderers_disconnect|bedroom", re.I)


class Refused(Exception):
    pass


def free_ram_mb():
    out = subprocess.run(["powershell", "-NoProfile", "-c", "(Get-CimInstance Win32_OperatingSystem).FreePhysicalMemory"],
                         capture_output=True, text=True).stdout.strip()
    return int(out) // 1024 if out.isdigit() else 0


def adb_raw(*args, timeout=60, binary=False):
    r = subprocess.run([ADB, "-s", SERIAL] + list(args), capture_output=True, timeout=timeout)
    return r.stdout if binary else r.stdout.decode("utf-8", "replace")


def serial_online():
    out = subprocess.run([ADB, "devices"], capture_output=True, text=True).stdout
    return any(l.split("\t")[0] == SERIAL and l.endswith("device") for l in out.splitlines())


class Emulator:
    """One AVD of ours at a time, port 5558 only, headless, <= 2 GB guest RAM."""

    def __init__(self, avd, log_dir):
        self.avd, self.log_dir, self.proc = avd, log_dir, None

    def start(self, gpu="swiftshader_indirect"):
        if serial_online():
            raise Refused("%s is already running: one emulator of ours at a time" % SERIAL)
        free = free_ram_mb()
        if free < MIN_FREE_MB:
            raise Refused("only %d MB RAM free (need > %d MB): emulator not started" % (free, MIN_FREE_MB))
        env = dict(os.environ, ANDROID_AVD_HOME=AVD_HOME)
        os.makedirs(self.log_dir, exist_ok=True)
        log = open(os.path.join(self.log_dir, "emulator-%s.log" % self.avd), "w")
        self.proc = subprocess.Popen([EMULATOR, "-avd", self.avd, "-port", str(PORT), "-memory", "2048", "-no-window",
                                      "-no-snapshot", "-no-boot-anim", "-no-audio", "-gpu", gpu, "-camera-back", "none",
                                      "-camera-front", "none"], env=env, stdout=log, stderr=subprocess.STDOUT,
                                     creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))
        t0 = time.time()
        while time.time() - t0 < 420:
            if self.proc.poll() is not None:
                raise RuntimeError("the emulator exited (log: %s)" % log.name)
            if serial_online() and adb_raw("shell", "getprop", "sys.boot_completed", timeout=10).strip() == "1":
                break
            time.sleep(3)
        else:
            raise RuntimeError("the emulator did not boot in 7 minutes")
        check_emulator()
        return time.time() - t0

    def stop(self):
        if serial_online():
            adb_raw("emu", "kill", timeout=30)
        if self.proc:
            try:
                self.proc.wait(60)
            except subprocess.TimeoutExpired:
                self.proc.kill()
        t0 = time.time()
        while serial_online() and time.time() - t0 < 60:
            time.sleep(2)


def check_emulator():
    """Refuses to act on anything that is not our emulator (the owner's phone must never be used)."""
    if adb_raw("shell", "getprop", "ro.kernel.qemu", timeout=10).strip() != "1" and \
            "emulator" not in adb_raw("shell", "getprop", "ro.product.model", timeout=10).lower() and \
            adb_raw("shell", "getprop", "ro.boot.qemu", timeout=10).strip() != "1":
        raise Refused("%s is not an emulator" % SERIAL)


class View:
    __slots__ = ("d", "w", "i")

    def __init__(self, d, w, i):
        self.d, self.w, self.i = d, w, i

    def __getattr__(self, k):
        return self.d.get(k)

    @property
    def rect(self):
        return self.d.get("vis") or self.d["b"]

    @property
    def center(self):
        r = self.rect
        return (r[0] + r[2]) // 2, (r[1] + r[3]) // 2

    def keys(self):
        return {self.d.get("id"), self.d.get("sk"), self.d.get("cdk")} - {None}


def normalize(data):
    """getGlobalVisibleRect gives window coordinates: moved to the screen's, like the bounds (once)."""
    if data.get("visfix"):
        return data
    for w in data.get("windows", []):
        ox, oy = w["b"][0], w["b"][1]
        if ox or oy:
            for v in w["views"]:
                if v.get("vis"):
                    r = v["vis"]
                    v["vis"] = [r[0] + ox, r[1] + oy, r[2] + ox, r[3] + oy]
    data["visfix"] = True
    return data


class Dump:
    def __init__(self, data):
        self.data = normalize(data)
        self.windows = data.get("windows", [])
        self.views = []
        for w in self.windows:
            for i, v in enumerate(w["views"]):
                self.views.append(View(v, w, i))

    @property
    def activity(self):
        return self.data.get("activity", "").rsplit(".", 1)[-1]

    @property
    def fragments(self):
        return [f["name"].rsplit(".", 1)[-1] for f in self.data.get("fragments", []) if f.get("visible")]

    def top_window(self):
        return self.windows[-1] if self.windows else None

    def find(self, key=None, cls=None, text=None, window=None, clickable=None, within=None):
        """Views (top window first) whose id / string key / content-description key is `key`."""
        out = []
        for w in reversed(self.windows):
            if window is not None and w is not window:
                continue
            vs = w["views"]
            for i, v in enumerate(vs):
                if v.get("hidden"):
                    continue
                if key is not None and key not in (v.get("id"), v.get("sk"), v.get("cdk")):
                    continue
                if cls is not None and cls not in (v.get("c"),) and not v.get("cls", "").endswith(cls):
                    continue
                if text is not None and text not in (v.get("txt") or "") and text not in (v.get("cd") or ""):
                    continue
                if clickable is not None and bool(v.get("clk")) != clickable:
                    continue
                if within is not None and not self.is_inside(w, i, within):
                    continue
                out.append(View(v, w, i))
        return out

    def is_inside(self, w, i, ancestor_key):
        vs = w["views"]
        p = vs[i].get("p", -1)
        while p >= 0:
            if ancestor_key in (vs[p].get("id"), vs[p].get("sk")):
                return True
            p = vs[p].get("p", -1)
        return False

    def clickable_ancestor(self, view):
        vs = view.w["views"]
        i = view.i
        while i >= 0:
            if vs[i].get("clk") or vs[i].get("lclk"):
                return View(vs[i], view.w, i)
            i = vs[i].get("p", -1)
        return view

    def signature(self):
        """What must stop changing for the screen to be settled: windows, and every non-time view's place."""
        h = hashlib.md5()
        for w in self.windows:
            h.update(w.get("title", "").encode())
            for v in w["views"]:
                t = v.get("txt") or ""
                if re.search(r"\d+:\d\d|%|\d+\.\d", t) or "Seek" in v.get("c", "") or "Progress" in v.get("c", ""):
                    continue
                h.update(("%s|%s|%s|%s" % (v.get("c"), v.get("id"), v.get("b"), t[:20])).encode())
        return h.hexdigest()


class Device:
    def __init__(self):
        check_emulator()
        self.dumps = 0
        self.forwarded = False

    # ---- adb
    def sh(self, cmd, timeout=60):
        return adb_raw("shell", cmd, timeout=timeout)

    def install(self, apk):
        out = adb_raw("install", "-r", "-g", apk, timeout=600)
        if "Success" not in out:
            raise RuntimeError("install failed: " + out[-400:])

    def push(self, local, remote):
        return adb_raw("push", local, remote, timeout=600)

    def pull(self, remote, local):
        return adb_raw("pull", remote, local, timeout=300)

    def key(self, code):
        self.sh("input keyevent %s" % code)

    def back(self):
        self.key(4)

    def tap_xy(self, x, y):
        self.sh("input tap %d %d" % (x, y))

    def swipe(self, x0, y0, x1, y1, ms=300):
        self.sh("input swipe %d %d %d %d %d" % (x0, y0, x1, y1, ms))

    def screenshot(self, path):
        """By the emulator itself (host side, ~1 s); screencap as the fallback (PNG made on the device, slow)."""
        if os.path.exists(path):
            os.remove(path)
        adb_raw("emu", "screenrecord", "screenshot", os.path.abspath(path), timeout=60)
        if os.path.exists(path) and os.path.getsize(path) > 1000:
            return
        png = adb_raw("exec-out", "screencap", "-p", binary=True, timeout=60)
        if len(png) < 1000:
            raise RuntimeError("screencap returned %d bytes" % len(png))
        with open(path, "wb") as f:
            f.write(png)

    def pid(self):
        return self.sh("pidof %s" % PKG).strip()

    def focus(self):
        out = self.sh("dumpsys window | grep -E 'mCurrentFocus|mFocusedApp'")
        return out.strip()

    # ---- app
    def probe(self, action, timeout=30, **extras):
        args = ["am", "broadcast", "-n", RECEIVER, "-a", action]
        for k, v in extras.items():
            if isinstance(v, bool):
                args += ["--ez", k, "true" if v else "false"]
            elif isinstance(v, int):
                args += ["--ei", k, str(v)]
            else:
                args += ["--es", k, "'%s'" % str(v).replace("'", "'\\''")]
        out = self.sh(" ".join(args), timeout=timeout)
        m = re.search(r'data="(.*)"', out, re.S)
        return m.group(1) if m else out.strip()

    def call(self, action, timeout=30, **extras):
        """One command over the probe's socket (adb forward); None when the app has no socket (not running)."""
        if not self.forwarded:
            adb_raw("forward", "tcp:%d" % FWD_PORT, "localabstract:vlc_autotour")
            self.forwarded = True
        try:
            with socket.create_connection(("127.0.0.1", FWD_PORT), timeout=timeout) as s:
                s.settimeout(timeout)
                s.sendall((action + " " + json.dumps(extras) + chr(10)).encode("utf-8"))
                chunks = []
                while True:
                    b = s.recv(1 << 20)
                    if not b:
                        break
                    chunks.append(b)
            data = b"".join(chunks).decode("utf-8", "replace")
            return data if data else None
        except OSError:
            return None

    def dump(self, timeout=30):
        self.dumps += 1
        raw = self.call("dump", timeout=timeout)
        if raw and raw.startswith("{"):
            try:
                return Dump(json.loads(raw))
            except ValueError:
                pass
        if raw and raw.startswith("busy"):
            return Dump({"windows": [], "error": raw})
        raw = adb_raw("exec-out", "sh", "-c", "am broadcast -n %s -a dump --es out %s >/dev/null 2>&1; cat %s; rm -f %s"
                      % (RECEIVER, DUMP_FILE, DUMP_FILE, DUMP_FILE), timeout=timeout, binary=True)
        try:
            return Dump(json.loads(raw.decode("utf-8", "replace")))
        except ValueError:
            return Dump({"windows": [], "error": raw[:300].decode("utf-8", "replace")})

    def settle(self, timeout=6.0, min_wait=0.25):
        """The screen once two dumps in a row are the same (animations are off on the emulator)."""
        time.sleep(min_wait)
        t0 = time.time()
        last = self.dump()
        sig = last.signature()
        while time.time() - t0 < timeout:
            time.sleep(0.25)
            d = self.dump()
            s = d.signature()
            if s == sig and any(w.get("views") for w in d.windows):
                return d
            last, sig = d, s
        return last

    def tap(self, view, d=None):
        keys = " ".join(filter(None, [view.id, view.sk, view.cdk, view.txt, view.cd]))
        if DANGER.search(keys):
            raise Refused("refused a tap on a destructive / device control: " + keys)
        x, y = view.center
        self.tap_xy(x, y)

    def tap_key(self, key, index=0, d=None, within=None, settle=True):
        d = d or self.dump()
        vs = d.find(key, within=within)
        if len(vs) <= index:
            return None
        self.tap(vs[index])
        return self.settle() if settle else True

    def start_app(self, *extras, wait=True):
        cmd = "am start -W -n %s/org.videolan.vlc.StartActivity %s" % (PKG, " ".join(extras))
        self.sh(cmd, timeout=90)
        return self.settle(timeout=10, min_wait=1.0) if wait else None

    def stop_app(self):
        self.sh("am force-stop %s" % PKG)

    def view_intent(self, uri, mime):
        self.sh("am start -W -a android.intent.action.VIEW -d '%s' -t %s -n %s/org.videolan.vlc.StartActivity" % (uri, mime, PKG), timeout=90)

    def now(self):
        try:
            return int(self.sh("date +%s").strip())
        except ValueError:
            return int(time.time())

    def dropbox(self, since):
        """Crashes and ANRs of the app recorded by the system since `since` (device epoch): the ones the logcat
        of the app process never shows (a service that did not start in time, an ANR dialog)."""
        import calendar
        out = []
        for tag in ("data_app_crash", "data_app_anr", "data_app_native_crash"):
            text = self.sh("dumpsys dropbox --print %s" % tag, timeout=60)
            for block in re.split(r"\n(?=20\d\d-\d\d-\d\d \d\d:\d\d:\d\d )", "\n" + text):
                m = re.match(r"\s*(20\d\d-\d\d-\d\d \d\d:\d\d:\d\d) ", block)
                if not m or ("Process: " + PKG) not in block:
                    continue
                t = calendar.timegm(time.strptime(m.group(1), "%Y-%m-%d %H:%M:%S"))
                if t < since:
                    continue
                lines = [l for l in block.splitlines() if l.strip()]
                head = next((l for l in lines if "Exception" in l or "Error" in l or l.startswith("Subject:")), lines[-1] if lines else "")
                frame = next((l.strip() for l in lines if "at org.videolan" in l), "")
                out.append({"t": t, "tag": tag, "head": head.strip()[:300], "frame": frame[:200]})
        return out

    def system_dialog(self):
        """The title of a system crash / ANR dialog in front, else ''."""
        f = self.sh("dumpsys window displays | grep mCurrentFocus")
        m = re.search(r"(Application Error|Application Not Responding|isn't responding|has stopped)[^}]*", f)
        return m.group(0) if m else ""

    def dismiss_system_dialogs(self):
        """Taps the dialog's own close button (resource id android:id/aerr_close), found by a uiautomator dump."""
        xml = self.sh("uiautomator dump /sdcard/at-ui.xml >/dev/null 2>&1; cat /sdcard/at-ui.xml", timeout=60)
        m = re.search(r'resource-id="android:id/aerr_close"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml)
        if m:
            x0, y0, x1, y1 = map(int, m.groups())
            self.tap_xy((x0 + x1) // 2, (y0 + y1) // 2)
        else:
            self.key(4)
        time.sleep(1)

    def meminfo(self):
        out = self.sh("dumpsys meminfo %s" % PKG, timeout=60)
        res = {}
        for k, pat in (("pss_total_kb", r"TOTAL PSS:\s+(\d+)|TOTAL\s+(\d+)"), ("java_heap_kb", r"Java Heap:\s+(\d+)"),
                       ("native_heap_kb", r"Native Heap:\s+(\d+)"), ("views", r"Views:\s+(\d+)"), ("activities", r"Activities:\s+(\d+)")):
            m = re.search(pat, out)
            if m:
                res[k] = int(next(g for g in m.groups() if g))
        return res

    # ---- system configuration of a matrix cell
    def set_system(self, night=None, font=None, rotation=None):
        if night is not None:
            self.sh("cmd uimode night %s" % ("yes" if night else "no"))
        if font is not None:
            self.sh("settings put system font_scale %s" % font)
        if rotation is not None:
            self.sh("settings put system accelerometer_rotation 0; settings put system user_rotation %d; cmd window user-rotation lock %d" % (rotation, rotation))
            want = "ROTATION_%d" % (rotation * 90)
            for _ in range(20):
                time.sleep(0.5)
                if want in self.sh("dumpsys window displays | grep -m1 mCurrentRotation"):
                    break
                self.sh("cmd window user-rotation lock %d" % rotation)
            else:
                raise RuntimeError("the display did not turn to %s" % want)

    def prepare(self):
        """Once per boot: animations off, screen on and unlocked, no screen timeout, immersive confirmations off."""
        for k in ("window_animation_scale", "transition_animation_scale", "animator_duration_scale"):
            self.sh("settings put global %s 0" % k)
        self.sh("settings put system screen_off_timeout 1800000; svc power stayon true; input keyevent 224; wm dismiss-keyguard")
        self.sh("settings put secure immersive_mode_confirmations confirmed; settings put secure show_ime_with_hard_keyboard 0")
        # crash / ANR dialogs would take the keys; the crashes and ANRs are read from the dropbox instead
        self.sh("settings put global hide_error_dialogs 1")


def save_dump(d, path):
    with gzip.open(path, "wt", encoding="utf-8") as f:
        json.dump(d.data, f, ensure_ascii=False)


def load_dump(path):
    with gzip.open(path, "rt", encoding="utf-8") as f:
        return Dump(json.load(f))
