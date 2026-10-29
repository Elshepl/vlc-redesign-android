"""Test media for the UI tour, generated with ffmpeg (cached): videos with English / Hebrew SRT and an ASS track,
music with covers and tags (Hebrew ones too), a folder tree, an M3U playlist, and the streams of the Live TV mock.

No colour of the media is orange (the accent check of the tour looks for orange pixels outside the media)."""
import os, subprocess, sys

FF = "ffmpeg"
DEVICE_DIR = "/sdcard/Download/vlc-redesign-test"

SRT_EN = """1
00:00:00,500 --> 00:00:04,000
The quick brown fox jumps over the lazy dog.

2
00:00:04,500 --> 00:00:09,000
A second line of English subtitles,
on two rows.

3
00:00:09,500 --> 00:10:00,000
Subtitles stay until the end.
"""
SRT_HE = """1
00:00:00,500 --> 00:00:04,000
שלום עולם, זו כתובית בעברית.

2
00:00:04,500 --> 00:00:09,000
שורה שנייה (עם סוגריים) ומספר 120.

3
00:00:09,500 --> 00:10:00,000
הכתוביות נשארות עד הסוף.
"""
ASS = """[Script Info]
ScriptType: v4.00+
PlayResX: 640
PlayResY: 360

[V4+ Styles]
Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding
Style: Default,Arial,28,&H00FFFFFF,&H000000FF,&H00000000,&H64000000,0,0,0,0,100,100,0,0,1,2,1,2,20,20,20,1

[Events]
Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
Dialogue: 0,0:00:00.50,0:10:00.00,Default,,0,0,0,,{\\b1}ASS styled{\\b0} subtitle — שלום (ASS)
"""

# (file, seconds, lavfi source); colours: blue / green / grey / purple, never orange
VIDEOS = [
    ("Videos/AutoTour Clip 01.mp4", 600, "testsrc2=size=640x360:rate=25,hue=h=200:s=0.6"),
    ("Videos/AutoTour Clip 02 with a very long title to see how lists cut it off nicely.mp4", 20, "smptehdbars=size=1280x720:rate=25,hue=s=0"),
    ("Videos/Series/S01/AutoTour Series S01E01.mp4", 15, "color=c=0x2050A0:size=1280x720:rate=25"),
    ("Videos/Series/S01/AutoTour Series S01E02.mp4", 15, "color=c=0x208050:size=1280x720:rate=25"),
    ("Videos/סרטון בדיקה עברית.mp4", 15, "color=c=0x603090:size=854x480:rate=25"),
    ("Videos/Portrait clip.mp4", 12, "testsrc2=size=720x1280:rate=25,hue=h=120:s=0.4"),
]
MKV = "Videos/AutoTour Tracks (3 subs, 2 audio).mkv"

# (title, artist, album, genre, track, hue for the cover)
SONGS = [
    ("Morning Light", "Aurora Lane", "First Light", "Ambient", 1, "0x2A5DB0"),
    ("Blue Hour", "Aurora Lane", "First Light", "Ambient", 2, "0x2A5DB0"),
    ("Northern Road", "Aurora Lane", "First Light", "Ambient", 3, "0x2A5DB0"),
    ("Paper Planes", "The Glass Owls", "City Lights", "Indie", 1, "0x2E8B57"),
    ("Neon Rain", "The Glass Owls", "City Lights", "Indie", 2, "0x2E8B57"),
    ("Long Distance Call From A Very Far Away Place", "The Glass Owls", "City Lights", "Indie", 3, "0x2E8B57"),
    ("שיר בוקר", "להקת הבדיקה", "אלבום עברי", "פופ", 1, "0x7040A0"),
    ("לילה טוב", "להקת הבדיקה", "אלבום עברי", "פופ", 2, "0x7040A0"),
    ("Sternenhimmel über der Stadt", "Die Langen Namen", "Donaudampfschifffahrt", "Schlager", 1, "0x406080"),
    ("Kurz", "Die Langen Namen", "Donaudampfschifffahrt", "Schlager", 2, "0x406080"),
    ("Tone A", "Test Signals", "Signals", "Electronic", 1, "0x505050"),
    ("Tone B", "Test Signals", "Signals", "Electronic", 2, "0x505050"),
]


def run(args):
    r = subprocess.run([FF, "-hide_banner", "-loglevel", "error", "-y"] + args, capture_output=True, text=True)
    if r.returncode:
        raise RuntimeError("ffmpeg failed: %s\n%s" % (" ".join(args), r.stderr[-800:]))


def write(path, text, encoding="utf-8"):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding=encoding, newline="\n") as f:
        f.write(text)


def generate(root):
    """Writes the tree under root (cached: a file already there is kept). Returns the tree's root."""
    tree = os.path.join(root, "vlc-redesign-test")
    for rel, secs, src in VIDEOS:
        out = os.path.join(tree, rel)
        if os.path.exists(out):
            continue
        os.makedirs(os.path.dirname(out), exist_ok=True)
        run(["-f", "lavfi", "-i", src, "-f", "lavfi", "-i", "sine=frequency=330:sample_rate=48000", "-t", str(secs),
             "-c:v", "libx264", "-preset", "veryfast", "-pix_fmt", "yuv420p", "-c:a", "aac", "-b:a", "96k", "-shortest", out])
    # external subtitles next to clip 01: English and Hebrew SRT, an ASS
    base = os.path.join(tree, "Videos", "AutoTour Clip 01")
    write(base + ".en.srt", SRT_EN)
    write(base + ".he.srt", SRT_HE)
    write(base + ".ass", ASS)
    mkv = os.path.join(tree, MKV)
    if not os.path.exists(mkv):
        tmp = os.path.join(root, "_tmp")
        os.makedirs(tmp, exist_ok=True)
        write(os.path.join(tmp, "en.srt"), SRT_EN)
        write(os.path.join(tmp, "he.srt"), SRT_HE)
        write(os.path.join(tmp, "s.ass"), ASS)
        run(["-f", "lavfi", "-i", "testsrc2=size=640x360:rate=25,hue=h=240:s=0.5", "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000",
             "-f", "lavfi", "-i", "sine=frequency=220:sample_rate=48000",
             "-i", os.path.join(tmp, "en.srt"), "-i", os.path.join(tmp, "he.srt"), "-i", os.path.join(tmp, "s.ass"), "-t", "600",
             "-map", "0:v", "-map", "1:a", "-map", "2:a", "-map", "3", "-map", "4", "-map", "5",
             "-c:v", "libx264", "-preset", "veryfast", "-pix_fmt", "yuv420p", "-c:a", "aac", "-b:a", "96k", "-c:s:0", "srt", "-c:s:1", "srt", "-c:s:2", "ass",
             "-metadata:s:a:0", "language=eng", "-metadata:s:a:0", "title=English stereo",
             "-metadata:s:a:1", "language=heb", "-metadata:s:a:1", "title=Hebrew commentary",
             "-metadata:s:s:0", "language=eng", "-metadata:s:s:1", "language=heb", "-metadata:s:s:2", "language=ara",
             "-metadata", "title=AutoTour Tracks", "-shortest", mkv])
    covers = os.path.join(root, "_covers")
    os.makedirs(covers, exist_ok=True)
    for title, artist, album, genre, track, hue in SONGS:
        rel = os.path.join("Music", artist, album, "%02d %s.mp3" % (track, title))
        out = os.path.join(tree, rel)
        if os.path.exists(out):
            continue
        os.makedirs(os.path.dirname(out), exist_ok=True)
        cover = os.path.join(covers, hue[2:] + ".png")
        if not os.path.exists(cover):
            run(["-f", "lavfi", "-i", "color=c=%s:size=500x500,drawbox=x=60:y=60:w=380:h=380:color=white@0.35:t=12" % hue, "-frames:v", "1", cover])
        freq = 200 + 40 * track
        run(["-f", "lavfi", "-i", "sine=frequency=%d:sample_rate=44100" % freq, "-i", cover, "-t", "40", "-map", "0:a", "-map", "1:v",
             "-c:a", "libmp3lame", "-b:a", "96k", "-c:v", "png", "-disposition:v", "attached_pic", "-id3v2_version", "3",
             "-metadata", "title=" + title, "-metadata", "artist=" + artist, "-metadata", "album_artist=" + artist,
             "-metadata", "album=" + album, "-metadata", "genre=" + genre, "-metadata", "track=%d" % track, "-metadata", "date=2024", out])
    # a folder tree with an empty folder and a deep one, and an M3U playlist
    for d in ("Folders/Empty", "Folders/Deep/Level 2/Level 3"):
        os.makedirs(os.path.join(tree, d), exist_ok=True)
        keep = os.path.join(tree, d, ".keep")
        if not os.path.exists(keep):
            write(keep, "")
    deep = os.path.join(tree, "Folders/Deep/Level 2/Level 3/Deep clip.mp4")
    if not os.path.exists(deep):
        run(["-f", "lavfi", "-i", "color=c=0x305070:size=640x360:rate=25", "-t", "5", "-c:v", "libx264", "-preset", "veryfast", "-pix_fmt", "yuv420p", deep])
    songs = ["../Music/%s/%s/%02d %s.mp3" % (a, al, t, ti) for ti, a, al, g, t, h in SONGS[:5]]
    write(os.path.join(tree, "Playlists", "AutoTour Playlist.m3u"), "#EXTM3U\n" + "\n".join(songs) + "\n")
    return tree


def stream_file(root):
    """A small MPEG-TS loop for the Live TV channels of the mock server."""
    out = os.path.join(root, "_tv", "channel.ts")
    if not os.path.exists(out):
        os.makedirs(os.path.dirname(out), exist_ok=True)
        run(["-f", "lavfi", "-i", "testsrc2=size=640x360:rate=25,hue=h=200:s=0.5", "-f", "lavfi", "-i", "sine=frequency=300:sample_rate=48000",
             "-t", "20", "-c:v", "libx264", "-preset", "veryfast", "-pix_fmt", "yuv420p", "-c:a", "aac", "-f", "mpegts", out])
    logo = os.path.join(root, "_tv", "logo.png")
    if not os.path.exists(logo):
        run(["-f", "lavfi", "-i", "color=c=0x2060C0:size=128x128,drawbox=x=24:y=24:w=80:h=80:color=white@0.8:t=fill", "-frames:v", "1", logo])
    return out


if __name__ == "__main__":
    print(generate(sys.argv[1] if len(sys.argv) > 1 else r"D:\vlc-redesign-tmp\android-autotour\media"))
