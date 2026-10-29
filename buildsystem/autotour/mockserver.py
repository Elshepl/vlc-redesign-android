"""Local Live TV mock for the UI tour: an M3U playlist with its XMLTV guide, and an Xtream Codes account
(player_api.php, xmltv.php, /live/... streams). Bound to 127.0.0.1 only; the emulator reaches it at 10.0.2.2.
The guide is made around the current time at each request, so "now" always has a programme."""
import json, os, sys, threading, time
from http.server import ThreadingHTTPServer, BaseHTTPRequestHandler
from urllib.parse import urlparse, parse_qs

EMU_HOST = "10.0.2.2"
USER, PASSWORD = "autotour", "tour123"

CHANNELS = [
    # (id, name, group, tvg-id, language of the guide)
    (1, "AutoTour News", "News", "news.at", "en"),
    (2, "AutoTour Sport", "Sport", "sport.at", "en"),
    (3, "ערוץ הבדיקה", "עברית", "hebrew.at", "he"),
    (4, "Deutsches Testfernsehen mit langem Namen", "Deutsch", "de.at", "de"),
    (5, "Kids Channel", "Kids", "kids.at", "en"),
    (6, "Music Hits", "Music", "music.at", "en"),
    (7, "Documentary HD", "Documentary", "docs.at", "en"),
    (8, "AutoTour Radio", "Radio", "radio.at", "en"),
]
TITLES = {
    "en": ["Morning Briefing", "World Report", "The Long Interview", "Evening News", "Late Edition", "Weather"],
    "he": ["מהדורת בוקר", "חדשות העולם", "ראיון ארוך במיוחד", "מהדורת ערב", "לילה", "מזג אוויר"],
    "de": ["Frühstücksfernsehen", "Weltspiegel", "Das lange Gespräch über Donaudampfschifffahrt", "Tagesschau", "Spätausgabe", "Wetter"],
}


def xmltv(base):
    now = int(time.time())
    start0 = now - now % 1800 - 6 * 3600
    out = ['<?xml version="1.0" encoding="UTF-8"?>', '<tv generator-info-name="autotour">']
    for cid, name, group, tvg, lang in CHANNELS:
        out.append('<channel id="%s"><display-name>%s</display-name><icon src="%s/tv/logo.png"/></channel>' % (tvg, name, base))
    fmt = lambda t: time.strftime("%Y%m%d%H%M%S +0000", time.gmtime(t))
    for cid, name, group, tvg, lang in CHANNELS:
        t = start0
        i = cid
        while t < now + 36 * 3600:
            dur = 1800 * (1 + (i % 3))
            title = TITLES[lang][i % len(TITLES[lang])]
            desc = {"en": "A programme of the AutoTour guide, with a description long enough to wrap onto a second line.",
                    "he": "תוכנית במדריך הבדיקה, עם תיאור ארוך מספיק כדי לרדת לשורה שנייה.",
                    "de": "Eine Sendung des Testprogramms mit einer Beschreibung, die lang genug für eine zweite Zeile ist."}[lang]
            out.append('<programme start="%s" stop="%s" channel="%s"><title lang="%s">%s</title><desc lang="%s">%s</desc><category>%s</category></programme>'
                       % (fmt(t), fmt(t + dur), tvg, lang, title, lang, desc, group))
            t += dur
            i += 1
    out.append("</tv>")
    return "\n".join(out)


def m3u(base):
    lines = ['#EXTM3U url-tvg="%s/tv/guide.xml"' % base]
    for cid, name, group, tvg, lang in CHANNELS:
        radio = ' radio="true"' if group == "Radio" else ""
        lines.append('#EXTINF:-1 tvg-id="%s" tvg-chno="%d" tvg-logo="%s/tv/logo.png" group-title="%s"%s,%s' % (tvg, cid, base, group, radio, name))
        lines.append("%s/tv/stream/%d.ts" % (base, cid))
    return "\n".join(lines) + "\n"


def xtream(action, base):
    now = int(time.time())
    if not action:
        return {"user_info": {"username": USER, "password": PASSWORD, "auth": 1, "status": "Active", "exp_date": str(now + 86400 * 365),
                              "max_connections": "2", "active_cons": "0", "allowed_output_formats": ["ts", "m3u8"]},
                "server_info": {"url": EMU_HOST, "port": "8765", "timestamp_now": now, "time_now": time.strftime("%Y-%m-%d %H:%M:%S", time.gmtime(now))}}
    groups = sorted({c[2] for c in CHANNELS})
    if action == "get_live_categories":
        return [{"category_id": str(i + 1), "category_name": g, "parent_id": 0} for i, g in enumerate(groups)]
    if action == "get_live_streams":
        return [{"num": cid, "name": name, "stream_type": "radio_streams" if group == "Radio" else "live", "stream_id": cid,
                 "stream_icon": base + "/tv/logo.png", "epg_channel_id": tvg, "category_id": str(groups.index(group) + 1),
                 "tv_archive": 1 if cid == 1 else 0, "tv_archive_duration": 3 if cid == 1 else 0, "is_adult": 0}
                for cid, name, group, tvg, lang in CHANNELS]
    if action == "get_vod_categories":
        return [{"category_id": "100", "category_name": "Films", "parent_id": 0}]
    if action == "get_series_categories":
        return [{"category_id": "200", "category_name": "Series", "parent_id": 0}]
    if action == "get_vod_streams":
        return [{"num": 1, "name": "AutoTour Film", "stream_id": 501, "stream_icon": base + "/tv/logo.png", "category_id": "100",
                 "container_extension": "mp4", "rating": "7.5", "added": str(now - 86400)}]
    if action == "get_series":
        return [{"num": 1, "name": "AutoTour Series", "series_id": 601, "cover": base + "/tv/logo.png", "category_id": "200",
                 "rating": "8", "last_modified": str(now - 86400), "releaseDate": "2024-01-01"}]
    return []


class Handler(BaseHTTPRequestHandler):
    root = ""
    log = None

    def log_message(self, fmt, *args):
        if Handler.log:
            Handler.log.write("%s %s\n" % (time.strftime("%H:%M:%S"), fmt % args))
            Handler.log.flush()

    def send(self, body, ctype, code=200):
        if isinstance(body, str):
            body = body.encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        if self.command != "HEAD":
            self.wfile.write(body)

    def do_HEAD(self):
        self.do_GET()

    def do_GET(self):
        u = urlparse(self.path)
        q = parse_qs(u.query)
        base = "http://%s:%d" % (EMU_HOST, self.server.server_address[1])
        p = u.path
        if p == "/tv/playlist.m3u":
            return self.send(m3u(base), "audio/x-mpegurl")
        if p in ("/tv/guide.xml", "/xmltv.php"):
            return self.send(xmltv(base), "application/xml")
        if p == "/tv/logo.png":
            return self.send(open(os.path.join(self.root, "_tv", "logo.png"), "rb").read(), "image/png")
        if p == "/player_api.php":
            if q.get("username", [""])[0] != USER or q.get("password", [""])[0] != PASSWORD:
                return self.send(json.dumps({"user_info": {"auth": 0}}), "application/json")
            return self.send(json.dumps(xtream(q.get("action", [""])[0], base)), "application/json")
        if p.startswith("/tv/stream/") or p.startswith("/live/") or p.startswith("/movie/") or p.startswith("/series/"):
            return self.send(open(os.path.join(self.root, "_tv", "channel.ts"), "rb").read(), "video/mp2t")
        self.send("not found", "text/plain", 404)


def start(root, port=8765, logfile=None):
    Handler.root = root
    if logfile:
        Handler.log = open(logfile, "a", encoding="utf-8")
    srv = ThreadingHTTPServer(("127.0.0.1", port), Handler)
    threading.Thread(target=srv.serve_forever, daemon=True).start()
    return srv


def sources(port=8765):
    """The Live TV sources the probe adds (seedtv)."""
    base = "http://%s:%d" % (EMU_HOST, port)
    return [{"name": "AutoTour M3U", "kind": "m3u", "url": base + "/tv/playlist.m3u"},
            {"name": "AutoTour Xtream", "kind": "xtream", "server": base, "username": USER, "password": PASSWORD}]


if __name__ == "__main__":
    s = start(sys.argv[1] if len(sys.argv) > 1 else r"D:\vlc-redesign-tmp\android-autotour\media")
    print("serving on", s.server_address)
    while True:
        time.sleep(3600)
