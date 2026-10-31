"""The screens of the UI tour, in order, for one matrix cell. Each `snap` saves a screenshot, the probe's dump and
the place in the logcat; the names are stable (resource ids / indexes, never translated texts) so one screen
lines up across the matrix. Steps that open sub-screens explore what they find (the bar's buttons, a sheet's rows)
with a deny list for anything that leaves the app, changes the library or reaches a device."""
import json, os, re, time, traceback

import device
from device import Refused

PKG = device.PKG
TEST = "/sdcard/Download/vlc-redesign-test"
MKV = "file://" + TEST + "/Videos/AutoTour%20Tracks%20(3%20subs,%202%20audio).mkv"
CLIP = "file://" + TEST + "/Videos/AutoTour%20Clip%2001.mp4"

# coverage.md rows (section: row start) and the desktop (Figma frame) of a step, by step-name prefix
# (the Figma frames exported as PNG, outside the repository: AUTOTOUR_FIGMA, by default next to the checkout)
QA = os.environ.get("AUTOTOUR_FIGMA", os.path.join(os.path.dirname(__file__), "..", "..", "..", "vlc-redesign-qa", "qa", "figma"))
MAP = [
    ("main-home", "Main window: Home", "848-1996_sidebar.png"),
    ("videos-grid", "Main window: Videos: grid", "2404-9167_media.png"),
    ("videos-group", "Main window: Videos: groups / folders", "2404-9167_media.png"),
    ("videos-playlists", "Main window: Video playlists tab", "2459-9352_playlist.png"),
    ("videos-display", "Main window: Sort / display options sheets", "858-3290_View-Options.png"),
    ("videos-sort", "Main window: Sort / display options sheets", "856-2215_Sorting-Options.png"),
    ("videos-search", "Main window: Search (SearchActivity)", None),
    ("videos-action", "Main window: Action mode (multi-select)", None),
    ("ctx-", "Main window: Context menus (ContextSheet)", "2404-13340_file-context-menu.png"),
    ("music-tab", "Main window: Music: artists / albums / tracks / genres / playlists", "2404-9167_media.png"),
    ("music-detail", "Main window: Album / artist / playlist detail", "2404-9167_media.png"),
    ("music-album", "Main window: Album / artist / playlist detail", "2404-9167_media.png"),
    ("mini-player", "Main window: Mini player", "970-3091_Miniplayer.png"),
    ("tv-", "Live TV (list, guide, banner, sources)", None),
    ("more-", "Main window: More", None),
    ("more-streams", "Main window: Streams (new stream dialog)", "6216-13614_Open-network-stream.png"),
    ("more-history", "Main window: History / Recent", None),
    ("more-network", "Main window: Browse / Network", None),
    ("more-playlists", "Main window: Playlists", "2459-9352_playlist.png"),
    ("more-about", "Settings: About, authors, libraries, licence", None),
    ("settings-root", "Settings: Preferences root", "999-5047_Settings-global.png"),
    ("settings-general", "Settings: Preferences: General", "3046-9282_Settings-global.png"),
    ("settings-interface", "Settings: Preferences: Interface", "3062-11379_Settings-interface.png"),
    ("settings-privacy", "Settings: Preferences: Privacy", "3062-11911_Settings-privacy.png"),
    ("settings-controls", "Settings: Preferences: Controls", "6536-14482_Settings-controls.png"),
    ("settings-playback", "Settings: Preferences: Playback", "3062-12552_Settings-playback.png"),
    ("settings-video", "Settings: Preferences: Video", "3073-14187_Settings-video.png"),
    ("settings-audio", "Settings: Preferences: Audio", "3073-15879_Settings-audio.png"),
    ("settings-subtitles", "Settings: Preferences: Subtitles", "3180-17989_Settings-subtitles.png"),
    ("settings-library", "Settings: Preferences: Library", "6542-15251_Settings-library.png"),
    ("settings-tv", "Live TV sources (Settings > Live TV)", None),
    ("settings-network", "Settings: Preferences: Network", "6543-15649_Settings-network.png"),
    ("settings-performance", "Settings: Preferences: Performance", "6571-15958_Settings-performance.png"),
    ("settings-extensions", "Settings: Preferences: Extensions", "6575-17137_Settings-extensions.png"),
    ("settings-about", "Settings: About", None),
    ("settings-search", "Settings: Preference search", None),
    ("settings-sub", "Settings: sub-screens (remote access, Android Auto, optional features)", None),
    ("settings-dropdown", "Design system: dropdown", "2372-8300_Dropdown.png"),
    ("player-bar", "Players: Video player: control bar", "970-2703_Player.png"),
    ("player-hidden", "Players: Video player: hidden UI", "3328-11554_Player-hide-UI.png"),
    ("player-player_overlay_tracks", "Players: tracks panel", "1915-8397_subtitle-multiple.png"),
    ("player-tracks", "Players: tracks panel", "1915-8397_subtitle-multiple.png"),
    ("player-tracks-sync", "Players: subtitle sync timeline", None),
    ("player-seek-preview", "Players: seek bar frame preview", None),
    ("player-player_overlay_adv_function", "Players: advanced options sheet", "6062-13354_menu.png"),
    ("player-opt-rd_customize_player", "Players: Customize Player", "6587-19923_Player-customize.png"),
    ("player-opt-equalizer", "Players: Equalizer", "6114-13610_Audio-Equalizer.png"),
    ("player-opt-rd_fx_effects", "Players: Video effects / Audio effects", "536-1855_Video-Essential.png"),
    ("player-opt-playback_speed", "Players: speed", "2245-9805_audio-value.png"),
    ("player-opt-", "Players: resize/aspect, speed, sleep, jump-to-time, bookmarks, stats", None),
    ("player-player_resize", "Players: resize/aspect", None),
    ("player-playlist", "Players: Video player: playlist panel", "2459-9352_playlist.png"),
    ("player-gesture", "Players: gestures overlays", "244-184_volume.png"),
    ("player-cast", "Cast picker (fake renderer)", None),
    ("audio-player", "Players: Audio player (expanded)", "4767-23726_Player-Music.png"),
    ("audio-queue", "Players: Audio player: queue", "2459-9352_playlist.png"),
    ("audio-opt", "Players: audio player options", "6062-13354_menu.png"),
    ("onboarding", "Settings: Onboarding", "1902-3670_Onboarding.png"),
    ("dialog-", "Settings: Dialogs", None),
]

# rows / buttons the explorer never taps
DENY_KEYS = re.compile(r"lock|play_as_audio|pip|popup|repeat|shuffle|ab_repeat|delete|remove|clear|reset|share|ringtone|"
                       r"favorite|mark_as|mark_all|renderer_disconnect|disconnect|exit|quit|hide|ban|scan|refresh|"
                       r"orientation|seekbar|overlay_time|overlay_length|overlay_play\b|playlist_previous|playlist_next|"
                       r"player_overlay_rewind|player_overlay_forward|download|update|feedback|send|rate|donate|"
                       r"welcome_setup|onboarding|pin_|parental|clear_|restore|import|export|tips|incognito|sleep_cancel|ml_menu_scan|custom_dir|last_playlist", re.I)


def coverage_of(name):
    best = None
    for prefix, row, desk in MAP:
        if name.startswith(prefix) and (best is None or len(prefix) > len(best[0])):
            best = (prefix, row, desk)
    return (best[1], os.path.join(QA, best[2]) if best and best[2] else None) if best else (None, None)


def slug(s):
    return re.sub(r"[^a-z0-9_]+", "_", (s or "").lower()).strip("_")[:40] or "x"


NAV_TITLES = {"nav_home": "home", "nav_video": "rd_nav_videos", "nav_audio": "rd_nav_music", "nav_tv": "rd_nav_tv",
              "nav_more": "more", "nav_directories": "rd_nav_network", "nav_playlists": "playlists"}


class Lost(Exception):
    pass


class Tour:
    def __init__(self, dev, cell, out_dir, logcat_path, only=None):
        self.d, self.cell, self.out = dev, cell, out_dir
        self.logcat_path = logcat_path
        self.only = only
        self.steps = []
        self.strings = {}
        os.makedirs(out_dir, exist_ok=True)
        self.log_off = os.path.getsize(logcat_path) if os.path.exists(logcat_path) else 0
        self.t0 = time.time()

    # ---- recording
    def snap(self, name, d=None, note=""):
        if any(s["name"] == name for s in self.steps):
            return d
        d = d or self.d.settle()
        self.check_system()
        print("  snap", name, flush=True)
        png = os.path.join(self.out, name + ".png")
        try:
            self.d.screenshot(png)
        except Exception as e:
            png = None
            note += " screenshot failed: %s" % e
        device.save_dump(d, os.path.join(self.out, name + ".json.gz"))
        off = os.path.getsize(self.logcat_path) if os.path.exists(self.logcat_path) else 0
        row, desk = coverage_of(name)
        self.steps.append({"name": name, "png": os.path.basename(png) if png else None, "activity": d.activity,
                           "fragments": d.fragments, "windows": [w.get("title", "")[-60:] for w in d.windows],
                           "log": [self.log_off, off], "pid": self.d.pid(), "coverage": row, "desktop": desk,
                           "t": round(time.time() - self.t0, 1), "note": note.strip(), "error": d.data.get("error")})
        self.log_off = off
        return d

    def check_system(self):
        """A system crash / ANR dialog in front: its screenshot kept as a step, then closed."""
        t = self.d.system_dialog()
        if t:
            n = len([x for x in self.steps if x["name"].startswith("_system-dialog")])
            png = "_system-dialog-%d.png" % n
            try:
                self.d.screenshot(os.path.join(self.out, png))
            except Exception:
                png = None
            self.steps.append({"name": "_system-dialog-%d" % n, "png": png, "fail": "system dialog in front: " + t,
                               "log": [self.log_off, self.log_off], "pid": self.d.pid(), "t": round(time.time() - self.t0, 1)})
            self.d.dismiss_system_dialogs()

    def fail(self, name, err):
        self.steps.append({"name": name, "fail": str(err)[:500], "png": None, "log": [self.log_off, self.log_off],
                           "pid": self.d.pid(), "t": round(time.time() - self.t0, 1)})

    def save(self):
        with open(os.path.join(self.out, "steps.json"), "w", encoding="utf-8") as f:
            json.dump({"cell": self.cell, "steps": self.steps, "seconds": round(time.time() - self.t0)}, f, ensure_ascii=False, indent=1)

    # ---- helpers
    def s(self, key):
        """A string of the app in the cell's language (to find a view by its text when it has no id)."""
        if key not in self.strings:
            self.strings[key] = self.d.call("str", key=key) or ""
        return self.strings[key]

    def find(self, d, key=None, string=None, **kw):
        if string:
            t = self.s(string)
            return [v for v in d.find(**kw) if t and (v.txt == t or v.cd == t)] if t else []
        return d.find(key, **kw)

    def tap(self, d, key=None, string=None, index=0, settle=True, **kw):
        vs = self.find(d, key, string, **kw)
        for _ in range(6):
            # not there yet (a fragment still loading): a few more dumps before anything else
            if len(vs) > index:
                break
            time.sleep(0.5)
            d = self.d.dump()
            vs = self.find(d, key, string, **kw)
        if len(vs) <= index and key and not string and index == 0:
            # off screen (a toolbar scrolled away, a bar hidden): click it in the app
            r = self.d.call("click", id=key)
            if r and r.startswith("ok"):
                return self.d.settle() if settle else None
        if len(vs) <= index:
            raise Lost("not found: %s" % (key or string))
        v = d.clickable_ancestor(vs[index])
        self.d.tap(v)
        return self.d.settle() if settle else None

    def in_app(self, d=None):
        d = d or self.d.dump()
        return bool(d.windows)

    def main(self, tab=None, retry=True):
        """MainActivity with nothing over it (back presses, a relaunch when lost); then the tab."""
        try:
            return self._main(tab)
        except Lost:
            if not retry:
                raise
        # the task brought to the front first (cheap), a cold start only when that is not enough
        print("  main(): lost, to the front", flush=True)
        self.d.sh("am start -W -n %s/org.videolan.vlc.StartActivity" % PKG, timeout=60)
        try:
            return self._main(tab)
        except Lost:
            print("  main(): lost, relaunch", flush=True)
            self.d.stop_app()
            self.d.start_app()
            self.after_start()
            return self._main(tab)

    def _main(self, tab=None):
        self.check_system()
        for i in range(5):
            d = self.d.dump()
            if d.activity == "MainActivity" and len([w for w in d.windows if w.get("views")]) == 1:
                break
            if d.activity == "MainActivity" and not any(w.get("views") for w in d.windows):
                time.sleep(0.5)
                continue
            if not d.windows or i == 4:
                print("  main(): relaunch (%s, windows %s)" % (d.activity, [w.get("title", "")[-30:] for w in d.windows]), flush=True)
                self.d.stop_app()
                self.d.start_app()
                self.after_start()
                d = self.d.dump()
                break
            self.d.back()
            time.sleep(0.3)
        if tab:
            d = self.d.settle(timeout=3)
            for _ in range(12):
                if d.find(tab) or d.find("navigation") or d.find("navigation_rail") or d.find("rd_tablet_sidebar"):
                    break
                time.sleep(0.5)
                d = self.d.dump()
            title = self.s(NAV_TITLES.get(tab, "")) if tab in NAV_TITLES else ""
            side = [v for v in d.find(within="rd_tablet_sidebar") if title and v.cd == title and v.clk]
            if d.find(tab):
                d = self.tap(d, tab)
            elif side:
                self.d.tap(side[0])
                d = self.d.settle()
            else:
                # the bottom bar hides while a list scrolls: click it in the app
                r = self.d.call("click", id=tab)
                if not r or not r.startswith("ok"):
                    raise Lost("no navigation entry %s (%s)" % (tab, r))
                d = self.d.settle()
        return d

    def after_start(self):
        self.d.call("renderer", name="AutoTour Fake TV")

    def back_to(self, ok, restore=None, tries=3):
        for _ in range(tries):
            self.d.back()
            d = self.d.settle(timeout=3)
            if ok(d):
                return d
        if restore:
            return restore()
        raise Lost("could not go back")

    def scroll_pages(self, base, d, scroller_keys=("recycler_view", "list", "rd_home_scroll"), max_pages=6, region=None):
        """Screens below the first: swipes up in the content until nothing moves."""
        w = d.windows[-1]["b"]
        x = (w[0] + w[2]) // 2 if not region else (region[0] + region[2]) // 2
        top, bottom = (w[1] + (w[3] - w[1]) * 0.30, w[1] + (w[3] - w[1]) * 0.75) if not region else (region[1] + 80, region[3] - 80)
        sig = d.signature()
        for p in range(2, max_pages + 1):
            self.d.swipe(x, int(bottom), x, int(top), 900)
            d2 = self.d.settle(timeout=3)
            if d2.signature() == sig:
                break
            self.snap("%s-%d" % (base, p), d2)
            sig = d2.signature()
        return self.d.dump()

    def step(self, name, fn, *a):
        if self.only and not any(name.startswith(o) for o in self.only):
            return
        try:
            fn(*a)
        except Refused as e:
            self.fail(name, "refused: %s" % e)
        except Exception as e:
            self.fail(name, "%s: %s" % (type(e).__name__, e))
            traceback.print_exc()
            try:
                self.d.screenshot(os.path.join(self.out, "_fail-%s.png" % name))
            except Exception:
                pass

    # ---- the tour
    def run(self):
        self.since = self.d.now()
        self.d.stop_app()
        self.d.start_app()
        self.after_start()
        self.step("main-home", self.home)
        self.step("videos", self.videos)
        self.step("music", self.music)
        self.step("tv", self.tv)
        self.step("more", self.more)
        self.step("settings", self.settings)
        self.step("player", self.player)
        self.step("audio", self.audio)
        self.step("onboarding", self.onboarding)
        self.cell["dropbox"] = self.d.dropbox(self.since)
        self.save()

    def home(self):
        d = self.main("nav_home")
        self.snap("main-home", d)
        self.scroll_pages("main-home", d, max_pages=3)
        # the cast picker (the fake renderer only; a row of it is never tapped)
        d = self.main("nav_home")
        if d.find("ml_menu_renderers"):
            self.snap("player-cast-picker-main", self.tap(d, "ml_menu_renderers"))
        # the toolbar's overflow menu
        d = self.main("nav_home")
        if d.find(cls="OverflowMenuButton"):
            d = self.tap(d, cls="OverflowMenuButton")
            self.snap("main-overflow", d)

    def videos(self):
        d = self.main("nav_video")
        self.snap("videos-grid", d)
        self.scroll_pages("videos-grid", d, max_pages=2)
        self.step("videos-display", self.display_sheet, "nav_video", "videos-display-sheet")
        self.step("videos-ctx", self.videos_rest)

    def display_sheet(self, tab, name):
        """The display / sort sheet: the toolbar's button, else the overflow's entry."""
        d = self.main(tab)
        if d.find("ml_menu_display_options"):
            self.snap(name, self.tap(d, "ml_menu_display_options"))
            return
        ov = d.find(cls="OverflowMenuButton")
        if not ov:
            raise Lost("no overflow menu")
        self.d.tap(ov[0])
        d = self.d.settle()
        want = {self.s(k) for k in ("display_settings", "sortby", "display_options")} - {""}
        e = [v for v in d.find(window=d.windows[-1]) if v.txt in want]
        if not e:
            raise Lost("no display options")
        self.d.tap(d.clickable_ancestor(e[0]))
        self.snap(name, self.d.settle())

    def videos_rest(self):
        d = self.main("nav_video")
        cards = [v for v in d.find("item_more")]
        if len(cards) > 1:
            self.snap("ctx-video", self.tap(d, "item_more", index=1))
            self.ctx_rows("ctx-video", lambda: self.tap(self.main("nav_video"), "item_more", index=1))
        d = self.main("nav_video")
        groups = [v for v in d.find(cls="ConstraintLayout") if (v.cd or "").count(",") and v.clk]
        if groups:
            self.d.tap(groups[0])
            self.snap("videos-group", self.d.settle())
        d = self.main("nav_video")
        items = [v for v in d.find(cls="ConstraintLayout") if v.clk and v.cd]
        if len(items) > 1:
            x, y = items[1].center
            self.d.swipe(x, y, x, y, 900)
            self.snap("videos-action-mode", self.d.settle())
        d = self.main("nav_video")
        tabs = d.find(cls="TabView")
        if len(tabs) > 1:
            self.d.tap(tabs[1])
            self.snap("videos-playlists", self.d.settle())
        d = self.main("nav_video")
        if d.find("ml_menu_filter"):
            d = self.tap(d, "ml_menu_filter")
            self.d.sh("input text Auto")
            self.d.key(66)
            self.snap("videos-search", self.d.settle())
            self.d.key(111)

    def ctx_rows(self, base, reopen, allow=("info", "rename", "playlist", "properties", "add_to")):
        """A context sheet's harmless rows (info, rename, add to playlist), one at a time."""
        d = self.d.dump()
        top = d.windows[-1] if d.windows else None
        if not top:
            return
        rows = [v for v in d.find(window=top) if v.txt and v.sk]
        n = 0
        for v in rows:
            key = v.sk
            if not any(a in key for a in allow) or DENY_KEYS.search(key):
                continue
            n += 1
            if n > 3:
                break
            d = self.d.dump()
            cur = [x for x in d.find(window=d.windows[-1]) if x.sk == key]
            if not cur:
                reopen()
                d = self.d.dump()
                cur = [x for x in d.find(window=d.windows[-1]) if x.sk == key]
                if not cur:
                    continue
            self.d.tap(d.clickable_ancestor(cur[0]))
            self.snap("%s-%s" % (base, slug(key)), self.d.settle())
            self.main()
            reopen()

    def music(self):
        d = self.main("nav_audio")
        tabs = d.find(cls="TabView")
        for i in range(len(tabs)):
            d = self.d.dump()
            t = d.find(cls="TabView")
            if i < len(t):
                self.d.tap(t[i])
                d = self.d.settle()
                self.snap("music-tab%d" % i, d)
                if i in (0, 3):  # artists, genres: list + detail on wide windows, a page on a phone
                    items = [v for v in d.find(cls="ConstraintLayout") if v.clk and v.cd] or d.find("container")
                    if items:
                        self.d.tap(d.clickable_ancestor(items[0]))
                        self.snap("music-detail%d" % i, self.d.settle())
                        d = self.main("nav_audio")
                        t = d.find(cls="TabView")
                        if i < len(t):
                            self.d.tap(t[i]); self.d.settle()
                if i == 1:
                    d = self.d.dump()
                    items = d.find("container") or [v for v in d.find(cls="ConstraintLayout") if v.clk and v.cd]
                    if items:
                        self.d.tap(d.clickable_ancestor(items[0]))
                        self.snap("music-album-detail", self.d.settle())
                        self.main("nav_audio")
                        t = self.d.dump().find(cls="TabView")
                        self.d.tap(t[i]); self.d.settle()
                    d = self.d.dump()
                    if d.find("item_more"):
                        self.snap("ctx-album", self.tap(d, "item_more"))
                        self.main("nav_audio")
        d = self.main("nav_audio")
        if d.find(cls="OverflowMenuButton"):
            self.snap("music-overflow", self.tap(d, cls="OverflowMenuButton"))
        self.step("music-display", self.display_sheet, "nav_audio", "music-display-sheet")

    def tv(self):
        d = self.main()
        if not d.find("nav_tv") and not d.find("rd_nav_tv"):
            raise Lost("no Live TV entry")
        d = self.tap(d, "nav_tv") if d.find("nav_tv") else self.tap(d, "rd_nav_tv")
        # the channels come from the mock server: wait for them
        for _ in range(20):
            if len([v for v in d.views if v.txt and "AutoTour" in v.txt]) >= 1:
                break
            time.sleep(1)
            d = self.d.dump()
        self.snap("tv-list", self.d.settle())
        # the chips / tabs at the top (Channels, Guide, Films, Sources...): each one's screen
        d = self.d.dump()
        wb = d.windows[0]["b"] if d.windows else [0, 0, 1, 1]
        chips = [v for v in d.views if v.clk and v.txt and v.sk and v.c in ("TextView", "MaterialTextView", "AppCompatTextView", "MaterialButton", "Chip")
                 and v.rect[1] < wb[1] + (wb[3] - wb[1]) * 0.3 and not DENY_KEYS.search(v.sk)]
        for k in [c.sk for c in chips][:8]:
            d = self.main("nav_tv")
            cur = [v for v in d.views if v.sk == k and v.clk]
            if cur:
                self.d.tap(cur[0])
                self.snap("tv-tab-%s" % slug(k), self.d.settle(timeout=5))
        # the toolbar's actions (display / guide / sources...), one at a time
        acts = [v for v in d.find(cls="ActionMenuItemView")] + d.find(cls="OverflowMenuButton")
        names = [("overflow" if v.c == "OverflowMenuButton" else (v.id or v.sk or v.cdk or "a%d" % i)) for i, v in enumerate(acts)]
        for i, nm in enumerate(names):
            if DENY_KEYS.search(nm) or nm == "ml_menu_filter":
                continue
            d = self.main("nav_tv")
            cur = [v for v in d.find(cls="ActionMenuItemView")] + d.find(cls="OverflowMenuButton")
            if i >= len(cur):
                continue
            self.d.tap(cur[i])
            d = self.d.settle()
            self.snap("tv-action-%s" % slug(nm), d)
            if nm == "overflow":
                # the overflow's entries
                entries = [v for v in d.find(window=d.windows[-1]) if v.txt and v.sk and not DENY_KEYS.search(v.sk)]
                for e in entries[:6]:
                    d2 = self.main("nav_tv")
                    d2 = self.tap(d2, cls="OverflowMenuButton")
                    cur2 = [v for v in d2.find(window=d2.windows[-1]) if v.sk == e.sk]
                    if cur2:
                        self.d.tap(d2.clickable_ancestor(cur2[0]))
                        self.snap("tv-menu-%s" % slug(e.sk), self.d.settle())
        # a channel: the TV player with its banner (the stream is the mock's)
        d = self.main("nav_tv")
        ch = [v for v in d.views if v.txt == "AutoTour News"]
        if ch:
            self.d.tap(d.clickable_ancestor(ch[0]))
            time.sleep(2)
            self.snap("tv-player", self.d.settle())
            self.d.tap_xy(*self.center())
            self.snap("tv-player-banner", self.d.settle())

    def center(self):
        d = self.d.dump()
        b = d.windows[0]["b"] if d.windows else [0, 0, 1080, 2400]
        return (b[0] + b[2]) // 2, (b[1] + b[3]) // 2

    def more(self):
        d = self.main("nav_more")
        self.snap("more-panel", d)
        rows = [v for v in d.find(cls="MaterialTextView") if v.clk]
        keys = [(v.id or v.sk or "row%d" % i) for i, v in enumerate(rows)]
        for i, k in enumerate(keys):
            if k in ("settingsButton",) or DENY_KEYS.search(k):
                continue
            d = self.main("nav_more")
            cur = [v for v in d.find(cls="MaterialTextView") if v.clk]
            if i >= len(cur):
                continue
            self.d.tap(cur[i])
            name = {"streamsButton": "more-streams", "historyButton": "more-history", "aboutButton": "more-about",
                    "playlistsButton": "more-playlists"}.get(k, "more-" + slug(k))
            d = self.d.settle()
            self.snap(name, d)
            if name == "more-about":
                self.scroll_pages(name, d, max_pages=3)
            if name == "more-network":
                # a folder of the test media: the browser's file list
                dd = self.d.dump()
                hits = [v for v in dd.views if v.txt in ("Download", "Internal memory", "Interner Speicher", "זיכרון פנימי")]
                if hits:
                    self.d.tap(dd.clickable_ancestor(hits[0]))
                    self.snap("more-network-folder", self.d.settle())

    # ---- settings
    def open_settings(self):
        d = self.main("nav_more")
        return self.tap(d, "settingsButton")

    def settings(self):
        d = self.open_settings()
        self.snap("settings-root", d)
        side = self.sidebar_rows(d)
        names = ["general", "interface", "privacy", "controls", "playback", "video", "audio", "subtitles", "library", "tv",
                 "network", "performance", "extensions", "about"]
        for i in range(len(side)):
            d = self.d.dump()
            if d.activity != "PreferencesActivity":
                d = self.open_settings()
            rows = self.sidebar_rows(d)
            if i >= len(rows):
                break
            self.d.tap(rows[i])
            d = self.d.settle()
            nm = "settings-" + (names[i] if i < len(names) else "page%d" % i)
            self.snap(nm, d)
            pane = self.page_pane(d)
            self.scroll_pages(nm, d, max_pages=8, region=pane)
            if nm == "settings-interface":
                self.dropdown(nm)
            if nm in ("settings-general", "settings-privacy", "settings-network", "settings-library"):
                self.sub_screens(nm, i)
            # back to the sidebar on a phone (one pane)
            d = self.d.dump()
            if d.activity == "PreferencesActivity" and not self.sidebar_rows(d):
                self.d.back(); self.d.settle()
        d = self.open_settings()
        if d.find("menu_pref_search"):
            d = self.tap(d, "menu_pref_search")
            self.d.sh("input text audio")
            self.snap("settings-search", self.d.settle())
            self.d.key(111)

    def sidebar_rows(self, d):
        side = [v for v in d.find(cls="LinearLayout", within="rd_settings_sidebar") if v.clk and v.cd]
        if not side:
            side = [v for v in d.find(cls="LinearLayout") if v.clk and v.cd and v.cdk]
        return side

    def page_pane(self, d):
        rv = [v for v in d.find(cls="RecyclerView")]
        if not rv:
            return None
        big = max(rv, key=lambda v: (v.rect[2] - v.rect[0]) * (v.rect[3] - v.rect[1]) + (v.rect[0] * 10))
        return big.rect

    def dropdown(self, base):
        d = self.d.dump()
        wells = [v for v in d.views if v.id and "dropdown" in v.id and v.clk] or \
                [v for v in d.views if v.c and "Spinner" in v.c]
        if wells:
            self.d.tap(wells[0])
            self.snap("settings-dropdown", self.d.settle())
            self.d.back(); self.d.settle()

    def sub_screens(self, page, idx):
        """The page's rows that open a sub-screen (remote access, Android Auto, optional features, permissions)."""
        keys = ("remote_access", "android_auto", "optional_features", "permissions", "directories", "medialibrary_directories")
        d = self.d.dump()
        for v in [v for v in d.views if v.sk and any(k in v.sk for k in keys)]:
            d = self.d.dump()
            cur = [x for x in d.views if x.sk == v.sk]
            if not cur:
                continue
            self.d.tap(d.clickable_ancestor(cur[0]))
            self.snap("settings-sub-%s" % slug(v.sk), self.d.settle())
            self.d.back(); d = self.d.settle()
            if d.activity != "PreferencesActivity":
                break

    # ---- player
    def open_player(self, uri=MKV, mime="video/x-matroska"):
        self.d.view_intent(uri, mime)
        d = self.d.settle(timeout=5, min_wait=1.5)
        if d.activity != "VideoPlayerActivity":
            raise Lost("the player did not open (%s)" % d.activity)
        return self.controls(pause=True)

    def controls(self, pause=False):
        """The player's bar shown (a tap on the video when hidden), paused so it stays."""
        for _ in range(3):
            d = self.d.dump()
            if d.find("rd_bar") or d.find("player_overlay_play"):
                break
            self.d.tap_xy(*self.center())
            time.sleep(0.4)
        d = self.d.dump()
        if pause:
            p = d.find("player_overlay_play")
            if p and p[0].sk in ("lb_playback_controls_pause", "pause"):
                self.d.tap(p[0])
                d = self.d.settle()
            elif p and (p[0].cd or "").lower().startswith("pause"):
                self.d.tap(p[0]); d = self.d.settle()
        if not (d.find("rd_bar") or d.find("player_overlay_play")):
            self.d.tap_xy(*self.center())
            d = self.d.settle()
        return d

    def player_ok(self, d):
        return d.activity == "VideoPlayerActivity" and len(d.windows) == 1

    def ensure_player(self):
        d = self.d.dump()
        if d.activity != "VideoPlayerActivity" or not d.windows:
            return self.open_player()
        for _ in range(3):
            if len(d.windows) <= 1 and not d.find("options_background"):
                break
            self.d.back(); d = self.d.settle(timeout=3)
        return self.controls()

    def player(self):
        d = self.open_player()
        self.snap("player-bar", d)
        # the bar's buttons and the top bar's (cast with the fake renderer)
        buttons = [v for v in d.views if v.clk and v.id and not DENY_KEYS.search(v.id) and v.id not in ("rd_bar", "player_root")]
        ids = []
        for v in buttons:
            if v.id not in ids:
                ids.append(v.id)
        for bid in ids:
            d = self.ensure_player()
            cur = d.find(bid)
            if not cur:
                continue
            if bid in ("video_renderer",):
                self.d.tap(cur[0]); self.snap("player-cast-picker", self.d.settle())
                continue  # never a row of the picker
            self.d.tap(cur[0])
            d = self.d.settle()
            self.snap("player-%s" % bid, d)
            if bid == "player_overlay_tracks":
                tabs = d.find(cls="TabView")
                for i, t in enumerate(tabs):
                    dd = self.d.dump()
                    tt = dd.find(cls="TabView")
                    if i < len(tt):
                        self.d.tap(tt[i]); self.snap("player-tracks-tab%d" % i, self.d.settle())
                # the subtitle sync timeline (Subtitles tab, Subtitle timing)
                dd = self.d.dump()
                if dd.find("rd_sub_sync_open"):
                    self.d.tap(dd.find("rd_sub_sync_open")[0])
                    time.sleep(2.5)
                    self.snap("player-tracks-sync", self.d.settle())
            if bid == "player_overlay_adv_function":
                self.player_options(d)
        # the frame preview of the seek bar, while the finger is still down
        d = self.ensure_player()
        bar = d.find("player_overlay_seekbar")
        if bar:
            r = bar[0].rect
            y = (r[1] + r[3]) // 2
            import threading
            t = threading.Thread(target=lambda: self.d.sh("input swipe %d %d %d %d 5000" % (r[0] + (r[2] - r[0]) // 5, y, r[0] + (r[2] - r[0]) // 2, y)))
            t.start()
            time.sleep(3.5)
            self.snap("player-seek-preview", self.d.dump())
            t.join()
        # hidden UI, gestures
        d = self.ensure_player()
        self.d.tap_xy(*self.center()); time.sleep(0.6)
        self.snap("player-hidden", self.d.settle(timeout=2))
        b = d.windows[0]["b"]
        w, h = b[2] - b[0], b[3] - b[1]
        self.d.swipe(int(w * 0.85), int(h * 0.6), int(w * 0.85), int(h * 0.55), 400)
        self.snap("player-gesture-volume", self.d.dump())
        self.d.swipe(int(w * 0.15), int(h * 0.6), int(w * 0.15), int(h * 0.55), 400)
        self.snap("player-gesture-brightness", self.d.dump())
        self.d.back(); self.d.settle()

    def player_options(self, d):
        rows = [v for v in d.find("option_title")]
        keys = [v.sk or "row%d" % i for i, v in enumerate(rows)]
        for i, k in enumerate(keys):
            if DENY_KEYS.search(k) or k in ("control_setting",):
                continue
            d = self.ensure_player()
            d = self.tap(d, "player_overlay_adv_function")
            cur = d.find("option_title")
            if i >= len(cur):
                continue
            self.d.tap(d.clickable_ancestor(cur[i]))
            d = self.d.settle()
            nm = "player-opt-%s" % slug(k)
            self.snap(nm, d)
            if k in ("rd_fx_effects", "equalizer"):
                tabs = d.find(cls="TabView")
                for j in range(1, len(tabs)):
                    dd = self.d.dump(); tt = dd.find(cls="TabView")
                    if j < len(tt):
                        self.d.tap(tt[j]); self.snap("%s-tab%d" % (nm, j), self.d.settle())

    # ---- audio player
    def audio(self):
        d = self.main("nav_audio")
        tabs = d.find(cls="TabView")
        if len(tabs) > 2:
            self.d.tap(tabs[2]); d = self.d.settle()
        # (a large font scrolls the tab strip: the third tab seen is then another one; each in turn)
        for i in range(len(tabs)):
            if any(v.txt == "Morning Light" for v in d.views):
                break
            tt = d.find(cls="TabView")
            if i < len(tt):
                self.d.tap(tt[i]); d = self.d.settle()
        songs = [v for v in d.views if v.txt == "Morning Light"]
        if not songs:
            raise Lost("no song row")
        self.d.tap(d.clickable_ancestor(songs[0]))
        d = self.d.settle(timeout=4)
        self.snap("mini-player", d)
        # expand
        hdr = d.find("header") or d.find("audio_media_switcher")
        if hdr:
            self.d.tap(hdr[0])
        d = self.d.settle()
        play = d.find("play_pause") or d.find("header_play_pause")
        if play and (play[0].cd or "").lower().startswith(("pause", "השה", "paus")):
            self.d.tap(play[0]); d = self.d.settle()
        self.snap("audio-player", d)
        if d.find("playlist_switch"):
            self.snap("audio-queue", self.tap(d, "playlist_switch"))
            d = self.tap(self.d.dump(), "playlist_switch") if self.d.dump().find("playlist_switch") else self.d.dump()
        if d.find("adv_function"):
            d = self.tap(d, "adv_function")
            self.snap("audio-opt", d)
            rows = d.find("option_title")
            keys = [v.sk or "row%d" % i for i, v in enumerate(rows)]
            for i, k in enumerate(keys):
                if DENY_KEYS.search(k) or k in ("control_setting",):
                    continue
                d = self.d.dump()
                while len(d.windows) > 1 or d.find("options_background"):
                    self.d.back(); d = self.d.settle(timeout=3)
                if not d.find("adv_function"):
                    break
                d = self.tap(d, "adv_function")
                cur = d.find("option_title")
                if i < len(cur):
                    self.d.tap(d.clickable_ancestor(cur[i]))
                    self.snap("audio-opt-%s" % slug(k), self.d.settle())

    # ---- onboarding (last: it is a separate activity started from Settings > General)
    def onboarding(self):
        self.d.call("pref", key="app_onboarding_done", type="b", value="false")
        self.d.stop_app()
        self.d.start_app()
        try:
            for i in range(8):
                d = self.d.settle()
                if d.activity != "OnboardingActivity":
                    break
                self.snap("onboarding-%d" % (i + 1), d)
                nxt = d.find("next_button")
                if not nxt:
                    break
                if i >= 5:
                    break
                self.d.tap(nxt[0])
        finally:
            self.d.call("pref", key="app_onboarding_done", type="b", value="true")
            self.d.stop_app()
