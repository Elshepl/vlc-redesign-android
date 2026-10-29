#!/usr/bin/env python3
"""The icons of the Settings sidebar (the desktop's SettingsSidebar.qml glyphs) as white vector drawables.

    python buildsystem/redesign/import_settings_icons.py [design folder]   (default: ../repos/vlc/design)

Writes application/vlc-android/res/drawable/rd_ic_set_<page>.xml and rd_ic_set_<page>_active.xml from
design/icons/svg/glyphs/<glyph>/{default,active}.svg; the sidebar tints them (FG/A2, the active one in the accent).
"""
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import import_design  # noqa: E402

ROOT = import_design.ROOT
OUT = os.path.join(ROOT, "application", "vlc-android", "res", "drawable")

# page -> the desktop's glyph (SettingsSidebar.qml; Controls: hotkeys, Performance: preferences, as the desktop)
PAGES = {
    "general": "preferences", "interface": "interface", "privacy": "privacy", "controls": "hotkeys",
    "playback": "playback", "video": "video", "audio": "audio", "subtitles": "subtitles", "library": "folder",
    "network": "streaming", "performance": "preferences", "extensions": "plugins", "about": "info",
}


def main():
    design = sys.argv[1] if len(sys.argv) > 1 else os.path.normpath(os.path.join(ROOT, "..", "repos", "vlc", "design"))
    hdr = '<?xml version="1.0" encoding="utf-8"?>\n' + import_design.HEADER.replace("import_design.py", "import_settings_icons.py") % "glyphs"
    for page, glyph in PAGES.items():
        for state, suffix in (("default", ""), ("active", "_active")):
            src = os.path.join(design, "icons", "svg", "glyphs", glyph, state + ".svg")
            if not os.path.exists(src):
                src = os.path.join(design, "icons", "svg", "glyphs", glyph, "default.svg")
            xml, _ = import_design.convert(src, None, 24)
            xml = re.sub(r'(android:(?:fill|stroke)Color=)"[^"]*"', r'\1"#FFFFFFFF"', xml)
            with open(os.path.join(OUT, "rd_ic_set_%s%s.xml" % (page, suffix)), "w", encoding="utf-8", newline="\n") as f:
                f.write(hdr + xml)
    print("settings icons:", len(PAGES) * 2)


if __name__ == "__main__":
    main()
