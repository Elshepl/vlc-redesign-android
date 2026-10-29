#!/usr/bin/env python3
"""Translate the Live TV strings of the Android redesign from the desktop VLC Redesign catalogues.

The Live TV add-on of the app (gui/livetv, livetv) uses the desktop's texts word for word
(application/resources/src/main/res/values/redesign_livetv_strings.xml). Each one is looked up, by its English
text, in the desktop's po/<lang>.po and po/redesign/<lang>.po; the plurals by their two English forms, each
Android quantity of the language taking the form the catalogue's Plural-Forms gives a number of it. Written to
values-<lang>/redesign_livetv_strings.xml for every language the app ships. Fuzzy and empty translations are
skipped (Android falls back to English).

    import_livetv_strings.py [path to the desktop repository]   (default: ../repos/vlc next to this repository)
"""
import gettext
import os
import re
import sys
import xml.etree.ElementTree as ET

import polib

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from import_strings import RES, ROOT, android_dirs, esc, unesc  # noqa: E402

SOURCE = os.path.join(RES, "values", "redesign_livetv_strings.xml")
TARGET = "redesign_livetv_strings.xml"

# A number of each Android (CLDR) plural category, by language (integers only: Android counts are integers)
CATEGORIES = {
    "default": {"one": 1, "other": 5},
    "none": {"other": 5},
    "fr": {"one": 1, "many": 1000000, "other": 5},
    "pt": {"one": 1, "many": 1000000, "other": 5},
    "pt_BR": {"one": 1, "many": 1000000, "other": 5},
    "es": {"one": 1, "many": 1000000, "other": 5},
    "es_MX": {"one": 1, "many": 1000000, "other": 5},
    "it": {"one": 1, "many": 1000000, "other": 5},
    "ca": {"one": 1, "many": 1000000, "other": 5},
    "ru": {"one": 1, "few": 2, "many": 5, "other": 5},
    "uk": {"one": 1, "few": 2, "many": 5, "other": 5},
    "be": {"one": 1, "few": 2, "many": 5, "other": 5},
    "pl": {"one": 1, "few": 2, "many": 5, "other": 5},
    "cs": {"one": 1, "few": 2, "many": 5, "other": 5},
    "sk": {"one": 1, "few": 2, "many": 5, "other": 5},
    "hr": {"one": 1, "few": 2, "other": 5},
    "bs": {"one": 1, "few": 2, "other": 5},
    "sr": {"one": 1, "few": 2, "other": 5},
    "sl": {"one": 1, "two": 2, "few": 3, "other": 5},
    "lt": {"one": 1, "few": 2, "many": 5, "other": 10},
    "lv": {"zero": 10, "one": 1, "other": 2},
    "ro": {"one": 1, "few": 2, "other": 20},
    "ar": {"zero": 0, "one": 1, "two": 2, "few": 3, "many": 11, "other": 100},
    "he": {"one": 1, "two": 2, "other": 5},
    "ga": {"one": 1, "two": 2, "few": 3, "many": 7, "other": 11},
    "cy": {"zero": 0, "one": 1, "two": 2, "few": 3, "many": 6, "other": 4},
    "br": {"one": 1, "two": 2, "few": 3, "many": 1000000, "other": 5},
    "is": {"one": 1, "other": 5},
    "ja": "none", "ko": "none", "zh_CN": "none", "zh_TW": "none", "vi": "none", "th": "none", "id": "none",
    "ms": "none", "my": "none", "lo": "none", "km": "none",
}


# the texts of an option's choices, one msgid of the desktop ("Never|Every 6 hours|...")
COMBINED = [
    ("rd_tv_refresh_never", "rd_tv_refresh_6", "rd_tv_refresh_12", "rd_tv_refresh_day", "rd_tv_refresh_week"),
    ("rd_tv_banner_all", "rd_tv_banner_now", "rd_tv_banner_channel", "rd_tv_banner_nothing"),
]


def categories(lang):
    c = CATEGORIES.get(lang, CATEGORIES.get(lang.split("_")[0], "default"))
    return CATEGORIES[c] if isinstance(c, str) else c


def load_catalogue(path, out, plurals, formula):
    if not os.path.exists(path):
        return
    po = polib.pofile(path)
    m = re.search(r"plural=([^;]+)", po.metadata.get("Plural-Forms", ""))
    if m and not formula:
        formula.append(gettext.c2py(m.group(1).strip()))
    for e in po:
        if e.obsolete or "fuzzy" in e.flags:
            continue
        if e.msgid_plural:
            forms = [e.msgstr_plural[k] for k in sorted(e.msgstr_plural)]
            if all(f.strip() for f in forms):
                plurals[(e.msgid, e.msgid_plural)] = forms
        elif e.msgstr.strip() and (e.msgid not in out or not e.msgctxt):
            out[e.msgid] = e.msgstr


def qt(text):
    return re.sub(r"%(\d)\$[sd]", r"%\1", text)


def android(text, kind="s"):
    return re.sub(r"%(\d)(?!\$)", r"%\1$" + kind, text)


def main():
    desktop = sys.argv[1] if len(sys.argv) > 1 else os.path.normpath(os.path.join(ROOT, "..", "repos", "vlc"))
    root = ET.parse(SOURCE).getroot()
    strings = [(n.get("name"), unesc(n.text or "")) for n in root if n.tag == "string"]
    plurals = [(n.get("name"), {i.get("quantity"): unesc(i.text or "") for i in n}) for n in root if n.tag == "plurals"]
    # every language with a catalogue of the redesign or of VLC (the app ships some the redesign has none of)
    langs = sorted({f[:-3] for d in (os.path.join(desktop, "po", "redesign"), os.path.join(desktop, "po"))
                    for f in os.listdir(d) if f.endswith(".po")})
    written = 0
    for lang in langs:
        cat, plu, formula = {}, {}, []
        # the redesign's catalogue first: its translations win
        load_catalogue(os.path.join(desktop, "po", "redesign", lang + ".po"), cat, plu, formula)
        base, base_plu = {}, {}
        load_catalogue(os.path.join(desktop, "po", lang + ".po"), base, base_plu, formula)
        for k, v in base.items():
            cat.setdefault(k, v)
        for k, v in base_plu.items():
            plu.setdefault(k, v)
        rows = [(name, android(cat[qt(text)])) for name, text in strings if qt(text) in cat]
        # the choices of an option: one text of the desktop, separated by |
        have = {name for name, _ in rows}
        for names in COMBINED:
            english = "|".join(dict(strings)[n] for n in names)
            parts = cat.get(english, "").split("|")
            if len(parts) == len(names):
                rows += [(n, t) for n, t in zip(names, parts) if n not in have and t.strip()]
        prow = []
        if formula:
            for name, forms in plurals:
                key = (qt(forms.get("one", forms["other"])), qt(forms["other"]))
                tr = plu.get(key)
                if not tr:
                    continue
                items = {}
                for quantity, n in categories(lang).items():
                    i = formula[0](n)
                    if i < len(tr):
                        items[quantity] = android(tr[i], "d")
                if "other" in items:
                    prow.append((name, items))
        for d in android_dirs(lang):
            folder = os.path.join(RES, "values-" + d)
            if not os.path.isdir(folder):
                continue
            target = os.path.join(folder, TARGET)
            if not rows and not prow:
                if os.path.exists(target):
                    os.remove(target)
                continue
            with open(target, "w", encoding="utf-8", newline="\n") as f:
                f.write('<?xml version="1.0" encoding="utf-8"?>\n')
                f.write("<!-- Generated by buildsystem/redesign/import_livetv_strings.py from the desktop VLC Redesign "
                        "translations. Do not edit. -->\n<resources>\n")
                for name, text in rows:
                    f.write('    <string name="%s">%s</string>\n' % (name, esc(text)))
                for name, items in prow:
                    f.write('    <plurals name="%s">\n' % name)
                    for q in ("zero", "one", "two", "few", "many", "other"):
                        if q in items:
                            f.write('        <item quantity="%s">%s</item>\n' % (q, esc(items[q])))
                    f.write("    </plurals>\n")
                f.write("</resources>\n")
            written += 1
            print("%-8s %-10s %d/%d strings, %d/%d plurals" % (lang, "values-" + d, len(rows), len(strings), len(prow), len(plurals)))
    print("%d files" % written)


if __name__ == "__main__":
    main()
