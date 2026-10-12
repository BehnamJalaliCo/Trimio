#!/usr/bin/env python3
"""
Builds the animated-picture catalogue the director searches (engine/autopilot composeResources
files/art): an index of every licence-clean animated picture from the open sources below, with
English and Persian keywords, and a small starter pack drawn offline.

Sources (see docs/ASSETS.md, "Animated pictures"):
  - Noto Animated Emoji (Google), CC BY 4.0: index from googlefonts.github.io/noto-emoji-animation,
    Lottie documents from fonts.gstatic.com; keywords from Unicode CLDR annotations (en, fa;
    Unicode License v3).
  - Material Line Icons (line-md, Vjacheslav Trushkin), MIT: animated SVG through Iconify.

Usage: python3 -I tools/art/build_art_index.py <work-dir> [--out engine/autopilot/src/commonMain/composeResources/files/art]
The work dir caches downloads; nothing here is run by the build.
"""
import json
import os
import re
import sys
import urllib.request

NOTO_API = "https://googlefonts.github.io/noto-emoji-animation/data/api.json"
NOTO_LOTTIE = "https://fonts.gstatic.com/s/e/notoemoji/latest/{id}/lottie.json"
CLDR = "https://raw.githubusercontent.com/unicode-org/cldr-json/main/cldr-json"
LINE_MD = "https://raw.githubusercontent.com/iconify/icon-sets/master/json/line-md.json"

# The offline starter pack: the reactions and signs talking-head edits reach for most.
STARTER_NOTO = (
    "1f525 1f680 1f4b8 1f389 1f4af 2705 274c 26a0_fe0f 1f4a1 1f914 1f44d 2764_fe0f 2728 1f440 "
    "23f0 1f3c6 1f3af 1f4c8 1f602"
).split()
STARTER_LINE = (
    "check-list-3 confirm-circle close-circle alert alert-circle lightbulb heart arrow-right arrow-left "
    "arrow-up arrow-down star account phone email cog search-filled bell chat-round clipboard-check "
    "calendar folder download-loop upload-loop link home cloud-up laptop".split()
)
SKIN = re.compile(r"_1f3f[b-f]")
NOISE = {"filled", "twotone", "loop", "alt", "outline", "off", "round", "line", "md", "small", "big", "simple", "rounded"}
# Brand marks in line-md are trademarks, not pictures: Trimio draws brands only from its licensed packs.
BRANDS = {
    "bluesky", "buy-me-a-coffee", "discord", "facebook", "github", "iconify1", "iconify2", "instagram", "linkedin", "mastodon",
    "patreon", "peertube", "pixelfed", "pleroma", "reddit", "soundcloud", "spotify", "telegram", "tiktok", "twitter", "youtube",
}
# Everyday words for line-md's interface names, so the director's queries ("warning", "idea") find them.
SYNONYMS = {
    "lightbulb": "light bulb, idea, bulb, tip", "alert": "warning, danger, caution, attention, risk",
    "confirm": "check, done, correct, yes, ok, success, tick", "check": "done, complete, correct, tick", "close": "cross, no, wrong, x",
    "cancel": "no, stop, forbidden", "remove": "delete, minus", "cog": "settings, gear, setup", "account": "person, user, profile, customer",
    "person": "user, people, profile, customer", "chat": "message, conversation, comment, talk", "email": "mail, letter, inbox",
    "phone": "call, telephone", "cellphone": "mobile, smartphone, phone", "search": "find, magnifier, look", "clipboard": "notes, checklist, tasks",
    "calendar": "date, schedule, day, deadline", "folder": "files, archive", "document": "paper, page, report", "file": "paper, page, document",
    "download": "save", "home": "house, real estate, home", "heart": "love, like, favorite", "star": "favourite, rating, review, best",
    "thumbs": "like, approve", "bell": "notification, alarm, reminder", "watch": "time, clock, hours", "speedometer": "speed, fast, performance",
    "speed": "fast, quick", "gauge": "measure, meter, level", "laptop": "computer, work", "computer": "pc, desktop", "monitor": "screen, display",
    "security": "safety, protection, shield, secure, lock", "map": "location, place, address, map", "compass": "direction, navigation",
    "coffee": "drink, cafe, break", "beer": "drink, party", "cake": "birthday, celebration", "pizza": "food, meal", "engine": "motor, car engine",
    "construction": "building, work in progress", "medical": "medicine, health, doctor", "sunny": "sun, day, weather",
    "moon": "night, sleep", "cloud": "weather, cloud", "water": "drop, water", "loading": "wait, loading", "play": "video, start",
    "image": "photo, picture", "link": "url, address", "question": "help, why, question", "trash": "delete, garbage, bin",
    "pencil": "write, edit", "edit": "write, edit", "volume": "sound, audio", "briefcase": "business, job, work", "emoji": "face",
    "steering": "car, driving", "car": "car, vehicle",
}


def fetch(url, path):
    if not os.path.exists(path):
        req = urllib.request.Request(url, headers={"User-Agent": "Trimio art index builder"})
        with urllib.request.urlopen(req, timeout=60) as r, open(path, "wb") as f:
            f.write(r.read())
    with open(path, "rb") as f:
        return f.read()


def emoji_of(codepoint):
    return "".join(chr(int(p, 16)) for p in codepoint.split("_"))


def cldr(work, lang):
    out = {}
    for kind, sub in (("annotations", "cldr-annotations-full/annotations"), ("derived", "cldr-annotations-derived-full/annotationsDerived")):
        data = json.loads(fetch(f"{CLDR}/{sub}/{lang}/annotations.json", os.path.join(work, f"{kind}_{lang}.json")))
        root = data.get("annotations") or data.get("annotationsDerived")
        for ch, v in root["annotations"].items():
            out.setdefault(ch, v.get("tts", []) + v.get("default", []))
    return out


def words(text):
    return [w for w in re.split(r"[^\w؀-ۿ‌]+", text.lower()) if w]


def noto(work, en, fa):
    api = json.loads(fetch(NOTO_API, os.path.join(work, "noto_api.json")))
    out = []
    for icon in api["icons"]:
        cp = icon["codepoint"]
        if SKIN.search(cp):
            continue  # skin-tone variants: the default yellow form stands for all
        ch = emoji_of(cp)
        alt = ch.replace("️", "")
        names = [t.strip(":").replace("-", " ") for t in icon.get("tags", [])]
        tags_en = list(dict.fromkeys(names + en.get(ch, en.get(alt, []))))
        tags_fa = list(dict.fromkeys(fa.get(ch, fa.get(alt, []))))
        cat = (icon.get("categories") or [""])[0].split(" ")[0].lower()
        name = names[0] if names else cp
        out.append(["noto", cp, name, tags_en[:12], tags_fa[:10], icon.get("popularity", 0), cat])
    return out


def line_md(work):
    data = json.loads(fetch(LINE_MD, os.path.join(work, "line-md.json")))
    cats = {}
    for cat, names in data.get("categories", {}).items():
        for n in names:
            cats.setdefault(n, cat.split(" ")[0].lower())
    out = []
    for name in data["icons"]:
        if "-to-" in name or "transition" in name or any(name == b or name.startswith(b + "-") for b in BRANDS):
            continue  # morphs between two states, or a brand's mark
        tags = [w for w in name.split("-") if w not in NOISE and not w.isdigit()]
        phrase = " ".join(tags)
        extra = [w.strip() for w in SYNONYMS.get(tags[0] if tags else name, "").split(",") if w.strip()]
        if tags[:2] == ["emoji", "smile"] or tags[:2] == ["emoji", "grin"]:
            extra += ["happy", "smile"]
        elif tags[:2] == ["emoji", "cry"]:
            extra += ["sad", "cry"]
        out.append(["line-md", name, phrase or name, list(dict.fromkeys([phrase] + tags + extra)), [], 0, cats.get(name, "")])
    return out, data


def minify(o):
    """Rounds numbers and drops editor-only keys: a third of the size, the same drawing."""
    drop = {"nm", "mn", "ix", "cix", "np", "ln", "cl", "props", "meta"}
    if isinstance(o, dict):
        return {k: minify(v) for k, v in o.items() if k not in drop}
    if isinstance(o, list):
        return [minify(v) for v in o]
    if isinstance(o, float):
        r = round(o, 3)
        return int(r) if r == int(r) else r
    return o


def main():
    work = sys.argv[1]
    out = sys.argv[sys.argv.index("--out") + 1] if "--out" in sys.argv else "engine/autopilot/src/commonMain/composeResources/files/art"
    os.makedirs(work, exist_ok=True)
    os.makedirs(out, exist_ok=True)
    en, fa = cldr(work, "en"), cldr(work, "fa")
    entries = noto(work, en, fa)
    lines, line_set = line_md(work)
    entries += lines
    sources = {
        "noto": {
            "title": "Noto Animated Emoji", "author": "Google", "licence": "CC-BY-4.0",
            "credit": "Animated emoji: Noto Emoji Animation by Google (CC BY 4.0)",
            "home": "https://googlefonts.github.io/noto-emoji-animation/",
            "url": NOTO_LOTTIE, "format": "lottie", "w": 1024, "h": 1024, "loops": True,
        },
        "line-md": {
            "title": "Material Line Icons", "author": "Vjacheslav Trushkin", "licence": "MIT",
            "credit": None, "home": "https://github.com/cyberalien/line-md",
            "url": "https://api.iconify.design/line-md.json?icons={id}", "format": "svg", "w": 24, "h": 24, "loops": False,
        },
    }
    keywords = "CLDR emoji annotations (Unicode License v3)"
    with open(os.path.join(out, "index.json"), "w", encoding="utf-8") as f:
        json.dump({"v": 1, "keywords": keywords, "sources": sources, "art": entries}, f, ensure_ascii=False, separators=(",", ":"))
    starter = {}
    for cp in STARTER_NOTO:
        doc = json.loads(fetch(NOTO_LOTTIE.format(id=cp), os.path.join(work, f"noto_{cp}.json")))
        starter[f"noto:{cp}"] = json.dumps(minify(doc), separators=(",", ":"))
    for name in STARTER_LINE:
        icon = line_set["icons"].get(name)
        if icon is None:
            print("missing line-md", name)
            continue
        starter[f"line-md:{name}"] = icon["body"]
    with open(os.path.join(out, "starter.json"), "w", encoding="utf-8") as f:
        json.dump(starter, f, ensure_ascii=False, separators=(",", ":"))
    print(len(entries), "entries;", len(starter), "starter;", os.path.getsize(os.path.join(out, "index.json")), "+", os.path.getsize(os.path.join(out, "starter.json")), "bytes")


if __name__ == "__main__":
    main()
