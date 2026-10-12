#!/usr/bin/env python3
"""
Builds the art packs of the content channel (signed `.tpk` + manifest) and the in-app core set:

  art.noto-emoji        every Noto Animated Emoji (Google, CC BY 4.0) in its default colour — Lottie
  art.noto-emoji-tones  the same emoji in the five skin tones (and mixed-tone pairs)
  art.line-md           every animated Material Line icon (MIT), including the state morphs
  art.motion-icons      animated icons of other MIT Iconify sets that the engine draws (svg-spinners,
                        eos-icons' animated ones); meteocons is MIT too but uses gradients the engine
                        refuses, so it is left out until the SVG reader supports them
  --core <dir>          the in-app core set (feature/library resources): the most used emoji and
                        every line-md icon, drawn with no download

Keywords: Unicode CLDR annotations (English + Persian) for emoji, name words + synonyms for icons
(the same as tools/art/build_art_index.py). Every document passes tools/packs/artcheck.py (the
engine's feature check) or is left out.

Usage:
  python3 -I tools/packs/build_art_packs.py <work-dir> <out-dir> [--version N] [--only id,id] [--core feature/library/src/commonMain/composeResources/files/core]
Downloads are cached in <work-dir>; the signing key comes from TRIMIO_PACK_KEY or
~/.config/trimio/pack-signing.key (tools/packs/packlib.py keygen).
"""
import json
import os
import re
import sys
import time
import urllib.request

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import artcheck  # noqa: E402
import packlib  # noqa: E402

NOTO_API = "https://googlefonts.github.io/noto-emoji-animation/data/api.json"
NOTO_LOTTIE = "https://fonts.gstatic.com/s/e/notoemoji/latest/{id}/lottie.json"
CLDR = "https://raw.githubusercontent.com/unicode-org/cldr-json/main/cldr-json"
ICONIFY = "https://raw.githubusercontent.com/iconify/icon-sets/master/json/{set}.json"
SKIN = re.compile(r"_1f3f[b-f]")
TONES = {"1f3fb": "light skin tone", "1f3fc": "medium-light skin tone", "1f3fd": "medium skin tone", "1f3fe": "medium-dark skin tone", "1f3ff": "dark skin tone"}
TONES_FA = {"1f3fb": "پوست روشن", "1f3fc": "پوست نیمه‌روشن", "1f3fd": "پوست گندمی", "1f3fe": "پوست نیمه‌تیره", "1f3ff": "پوست تیره"}
NOISE = {"filled", "twotone", "loop", "alt", "outline", "off", "round", "line", "md", "small", "big", "simple", "rounded", "to"}
BRANDS = {
    "bluesky", "buy-me-a-coffee", "discord", "facebook", "github", "iconify1", "iconify2", "instagram", "linkedin", "mastodon",
    "patreon", "peertube", "pixelfed", "pleroma", "reddit", "soundcloud", "spotify", "telegram", "tiktok", "twitter", "youtube",
}
SYNONYMS = {
    "lightbulb": "light bulb, idea, bulb, tip", "alert": "warning, danger, caution, attention, risk", "confirm": "check, done, correct, yes, ok, success, tick",
    "check": "done, complete, correct, tick", "close": "cross, no, wrong, x", "cancel": "no, stop, forbidden", "cog": "settings, gear, setup",
    "account": "person, user, profile, customer", "chat": "message, conversation, comment, talk", "email": "mail, letter, inbox", "phone": "call, telephone",
    "search": "find, magnifier, look", "calendar": "date, schedule, day, deadline", "home": "house, real estate", "heart": "love, like, favorite",
    "star": "favourite, rating, review, best", "bell": "notification, alarm, reminder", "speedometer": "speed, fast, performance", "loading": "wait, loading, progress",
    "spinner": "loading, wait, progress", "blocks": "loading, building, blocks", "pulse": "pulse, signal, live", "wifi": "internet, network, signal",
    "bars": "loading, equalizer, audio, chart", "dots": "loading, typing, wait", "clock": "time, wait, hours", "ring": "loading, circle",
    "gauge": "measure, meter, level", "security": "safety, protection, shield, lock", "upload": "send, share", "download": "save, get",
}
CORE_EMOJI = 160  # the most popular emoji in the in-app core set (beyond the starter pictures)


def fetch(url, path, tries=4):
    if not os.path.exists(path):
        for i in range(tries):
            try:
                req = urllib.request.Request(url, headers={"User-Agent": "Trimio pack builder"})
                with urllib.request.urlopen(req, timeout=60) as r:
                    data = r.read()
                with open(path + ".tmp", "wb") as f:
                    f.write(data)
                os.replace(path + ".tmp", path)
                break
            except OSError as e:
                if i == tries - 1:
                    raise
                print("retry", url, e)
                time.sleep(2 + 3 * i)
    with open(path, "rb") as f:
        return f.read()


def cldr(work, lang):
    out = {}
    for kind, sub in (("annotations", "cldr-annotations-full/annotations"), ("derived", "cldr-annotations-derived-full/annotationsDerived")):
        data = json.loads(fetch(f"{CLDR}/{sub}/{lang}/annotations.json", os.path.join(work, f"{kind}_{lang}.json")))
        root = data.get("annotations") or data.get("annotationsDerived")
        for ch, v in root["annotations"].items():
            out.setdefault(ch, v.get("tts", []) + v.get("default", []))
    return out


def emoji_of(cp):
    return "".join(chr(int(p, 16)) for p in cp.split("_"))


def noto_rows(work):
    """(row, codepoint, toned) for every animated emoji, keywords from CLDR."""
    en, fa = cldr(work, "en"), cldr(work, "fa")
    api = json.loads(fetch(NOTO_API, os.path.join(work, "noto_api.json")))
    rows = []
    for icon in api["icons"]:
        cp = icon["codepoint"]
        ch = emoji_of(cp)
        alt = ch.replace("️", "")
        base_cp = SKIN.sub("", cp)
        base = emoji_of(base_cp)
        names = [t.strip(":").replace("-", " ") for t in icon.get("tags", [])]
        toned = bool(SKIN.search(cp))
        tags_en = list(dict.fromkeys(names + en.get(ch, en.get(alt, en.get(base, [])))))
        tags_fa = list(dict.fromkeys(fa.get(ch, fa.get(alt, fa.get(base, [])))))
        if toned:
            tones = [t for t in TONES if t in cp]
            tags_en = list(dict.fromkeys(tags_en + [TONES[t] for t in tones]))
            tags_fa = list(dict.fromkeys(tags_fa + [TONES_FA[t] for t in tones]))
        cat = (icon.get("categories") or [""])[0].split(" ")[0].lower()
        name = names[0] if names else cp
        rows.append((("noto", cp, name, tags_en[:14], tags_fa[:10], icon.get("popularity", 0), cat), cp, toned))
    return rows


def noto_doc(work, cp):
    os.makedirs(os.path.join(work, "noto"), exist_ok=True)
    raw = fetch(NOTO_LOTTIE.format(id=cp), os.path.join(work, "noto", f"{cp}.json"))
    doc = json.loads(raw)
    problem = artcheck.lottie_problem(doc)
    return None if problem else json.dumps(packlib.minify_lottie(doc), separators=(",", ":")).encode("utf-8"), problem


def icon_rows(data, prefix, keep):
    cats = {}
    for cat, names in data.get("categories", {}).items():
        for n in names:
            cats.setdefault(n, cat.split(" ")[0].lower())
    rows = []
    for name, icon in data["icons"].items():
        if not keep(name, icon):
            continue
        tags = [w for w in name.split("-") if w not in NOISE and not w.isdigit()]
        phrase = " ".join(tags)
        extra = [w.strip() for t in tags[:2] for w in SYNONYMS.get(t, "").split(",") if w.strip()]
        if "-to-" in name:
            extra.append("transition")
        rows.append((prefix, name, phrase or name, list(dict.fromkeys([phrase] + tags + extra))[:14], [], 0, cats.get(name, "")))
    return rows


def source(sid, title, author, licence, home, url, fmt, w, h, loops, flavour, docs):
    return sid, {"title": title, "author": author, "licence": licence, "credit": None, "home": home, "url": url, "format": fmt,
                 "w": w, "h": h, "loops": loops, "flavour": flavour, "docs": docs}


NOTO_SOURCE = source("noto", "Noto Animated Emoji", "Google", "CC-BY-4.0", "https://googlefonts.github.io/noto-emoji-animation/", NOTO_LOTTIE,
                     "lottie", 1024, 1024, True, "emoji", "art/docs/{id}.json")


def iconify_source(sid, title, author, licence, home, w, h):
    return source(sid, title, author, licence, home, f"https://api.iconify.design/{sid}.json?icons={{id}}", "svg", w, h, False, "line", "art/docs/{id}.svg")


def index_json(sources, rows):
    return json.dumps({"v": 1, "sources": dict(sources), "art": [list(r) for r in rows]}, ensure_ascii=False, separators=(",", ":")).encode("utf-8")


def art_pack(pack_id, version, sources, rows, docs, out, key, licence, title_fa, title_en, summary_fa, summary_en, **extra):
    files = [("pack.json", packlib.pack_json(pack_id, "Art", title_fa, title_en, len(rows), licence)),
             ("art/index.json", index_json(sources, rows))]
    files += sorted(docs.items())
    return packlib.build(pack_id, "Art", version, files, out, key, licence, titleFa=title_fa, titleEn=title_en,
                         summaryFa=summary_fa, summaryEn=summary_en, items=len(rows), **extra)


def build_noto(work, out, key, version, toned_pack):
    rows, docs, refused = [], {}, {}
    for row, cp, toned in noto_rows(work):
        if toned != toned_pack:
            continue
        doc, problem = noto_doc(work, cp)
        if doc is None:
            refused[cp] = problem
            continue
        rows.append(row)
        docs[f"art/docs/{cp}.json"] = doc
    print(f"noto ({'tones' if toned_pack else 'default'}): {len(rows)} kept, {len(refused)} refused", dict(list(refused.items())[:5]))
    if toned_pack:
        return art_pack("art.noto-emoji-tones", version, [NOTO_SOURCE], rows, docs, out, key, "CC-BY-4.0",
                        "ایموجی متحرک با رنگ پوست", "Animated emoji · skin tones",
                        "همان ایموجی‌های متحرک دست و چهره در پنج رنگ پوست", "The animated hand and people emoji in all five skin tones")
    return art_pack("art.noto-emoji", version, [NOTO_SOURCE], rows, docs, out, key, "CC-BY-4.0",
                    "همهٔ ایموجی‌های متحرک", "All animated emoji",
                    "هر واکنش و حسی که گوینده می‌گوید، با ایموجی متحرک و بدون اینترنت", "Every reaction and feeling the speaker names, as a moving emoji, offline",
                    recommended=True)


def iconify(work, name):
    return json.loads(fetch(ICONIFY.format(set=name), os.path.join(work, f"{name}.json")))


def svg_docs(data, rows):
    docs, kept, refused = {}, [], 0
    for r in rows:
        body = data["icons"][r[1]]["body"]
        if artcheck.svg_problem(body):
            refused += 1
            continue
        kept.append(r)
        docs[f"art/docs/{r[1]}.svg"] = body.encode("utf-8")
    return kept, docs, refused


def build_line_md(work, out, key, version):
    data = iconify(work, "line-md")
    rows = icon_rows(data, "line-md", lambda n, i: "<animate" in i["body"] and not any(n == b or n.startswith(b + "-") for b in BRANDS))
    rows, docs, refused = svg_docs(data, rows)
    print(f"line-md: {len(rows)} kept, {refused} refused")
    src = iconify_source("line-md", "Material Line Icons", "Vjacheslav Trushkin", "MIT", "https://github.com/cyberalien/line-md", 24, 24)
    docs["art/LICENSE-line-md.txt"] = MIT("2021 Vjacheslav Trushkin")
    return art_pack("art.line-md", version, [src], rows, docs, out, key, "MIT", "آیکون‌های خطی متحرک", "Animated line icons",
                    "۱۲۰۰ آیکون که خودشان را می‌کشند، برای سبک‌های آرام، فنی و لوکس", "1,200 self-drawing icons for calm, technical and premium styles",
                    recommended=True)


def build_motion_icons(work, out, key, version):
    sets = [("svg-spinners", "SVG Spinners", "Utkarsh Verma", "https://github.com/n3r4zzurr0/svg-spinners", "2022 Utkarsh Verma"),
            ("eos-icons", "EOS Icons", "SUSE UX/UI team", "https://gitlab.com/SUSE-UIUX/eos-icons", "2019 SUSE UX/UI team")]
    sources, rows, docs = [], [], {}
    for sid, title, author, home, holder in sets:
        data = iconify(work, sid)
        assert data["info"]["license"]["spdx"] == "MIT", sid
        r = icon_rows(data, sid, lambda n, i: "<animate" in i["body"] or "<set" in i["body"])
        kept, d, refused = svg_docs(data, r)
        print(f"{sid}: {len(kept)} kept, {refused} refused")
        rows += kept
        docs.update({k.replace("art/docs/", f"art/docs/{sid}/"): v for k, v in d.items()})
        sources.append(source(sid, title, author, "MIT", home, f"https://api.iconify.design/{sid}.json?icons={{id}}", "svg",
                              data.get("width", 24), data.get("height", 24), True, "line", f"art/docs/{sid}/{{id}}.svg"))
        docs[f"art/LICENSE-{sid}.txt"] = MIT(holder)
    return art_pack("art.motion-icons", version, sources, rows, docs, out, key, "MIT", "آیکون‌های حرکتی", "Motion icons",
                    "لودینگ، سیگنال، پالس و نشانه‌های زنده برای لحظه‌های انتظار و پردازش", "Loaders, signals and live marks for waiting and processing moments")


def MIT(holder):
    return ("MIT License\n\nCopyright (c) " + holder + "\n\nPermission is hereby granted, free of charge, to any person obtaining a copy of this software "
            "and associated documentation files (the \"Software\"), to deal in the Software without restriction, including without limitation the rights "
            "to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is "
            "furnished to do so, subject to the following conditions:\n\nThe above copyright notice and this permission notice shall be included in all copies "
            "or substantial portions of the Software.\n\nTHE SOFTWARE IS PROVIDED \"AS IS\", WITHOUT WARRANTY OF ANY KIND.\n").encode("utf-8")


def build_core(work, core_dir):
    """The in-app core set: the most popular default-tone emoji and every line-md icon, one JSON (index rows + documents)."""
    os.makedirs(core_dir, exist_ok=True)
    noto = sorted([r for r in noto_rows(work) if not r[2]], key=lambda r: -r[0][5])[:CORE_EMOJI]
    rows, docs = [], {}
    for row, cp, _ in noto:
        doc, _problem = noto_doc(work, cp)
        if doc is not None:
            rows.append(row)
            docs[f"noto:{cp}"] = doc.decode("utf-8")
    data = iconify(work, "line-md")
    lrows = icon_rows(data, "line-md", lambda n, i: "<animate" in i["body"] and not any(n == b or n.startswith(b + "-") for b in BRANDS))
    lrows, ldocs, _ = svg_docs(data, lrows)
    rows += lrows
    docs.update({f"line-md:{k[len('art/docs/'):-4]}": v.decode("utf-8") for k, v in ldocs.items()})
    sources = dict([NOTO_SOURCE, iconify_source("line-md", "Material Line Icons", "Vjacheslav Trushkin", "MIT", "https://github.com/cyberalien/line-md", 24, 24)])
    for s in sources.values():
        s.pop("docs")
    with open(os.path.join(core_dir, "art.json"), "w", encoding="utf-8") as f:
        json.dump({"v": 1, "sources": sources, "art": [list(r) for r in rows], "docs": docs}, f, ensure_ascii=False, separators=(",", ":"))
    print("core:", len(rows), "pictures,", os.path.getsize(os.path.join(core_dir, "art.json")) // 1024, "KB")


def online_catalogue(work, core_dir):
    """Catalogue rows of the pack-only icon sets, fetched online when their pack is not installed (packs first, online second)."""
    sources, rows = [], []
    for sid, title, author, home in (("svg-spinners", "SVG Spinners", "Utkarsh Verma", "https://github.com/n3r4zzurr0/svg-spinners"),
                                     ("eos-icons", "EOS Icons", "SUSE UX/UI team", "https://gitlab.com/SUSE-UIUX/eos-icons")):
        data = iconify(work, sid)
        r = icon_rows(data, sid, lambda n, i: "<animate" in i["body"] or "<set" in i["body"])
        kept, _, _ = svg_docs(data, r)
        rows += kept
        s = iconify_source(sid, title, author, "MIT", home, data.get("width", 24), data.get("height", 24))
        s[1].pop("docs")
        s[1]["loops"] = True
        sources.append(s)
    with open(os.path.join(core_dir, "online.json"), "w", encoding="utf-8") as f:
        f.write(index_json(sources, rows).decode("utf-8"))
    print("online catalogue:", len(rows), "rows")


def main():
    args = sys.argv[1:]
    work, out = args[0], args[1]
    opt = lambda k, d=None: args[args.index(k) + 1] if k in args else d  # noqa: E731
    version = int(opt("--version", "1"))
    only = set(opt("--only", "").split(",")) - {""}
    os.makedirs(work, exist_ok=True)
    if opt("--core"):
        build_core(work, opt("--core"))
        online_catalogue(work, opt("--core"))
    if only == {"core"}:
        return
    key = packlib.load_key()
    builders = {
        "art.line-md": lambda: build_line_md(work, out, key, version),
        "art.motion-icons": lambda: build_motion_icons(work, out, key, version),
        "art.noto-emoji": lambda: build_noto(work, out, key, version, False),
        "art.noto-emoji-tones": lambda: build_noto(work, out, key, version, True),
    }
    for pid, build in builders.items():
        if not only or pid in only:
            build()
    packlib.write_index(out)


if __name__ == "__main__":
    main()
