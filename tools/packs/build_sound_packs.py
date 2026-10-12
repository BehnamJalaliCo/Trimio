#!/usr/bin/env python3
"""
Builds the recorded-sound packs of the content channel from CC0 sources with a verified author:

  sound.kenney-sfx    Kenney's audio packs (CC0-1.0, kenney.nl; the licence file of each pack says
                      "Creative Commons Zero, CC0"): interface, impact, UI, digital, sci-fi, casino
                      and RPG sounds, each file mapped to the soundtrack cue it voices (impact, whoosh,
                      swoosh, pop, tick, glitch, shimmer, riser, rewind) and the palettes it suits
  music.kenney-stings Kenney's Music Jingles (CC0-1.0): short stings for openings, reveals and
                      endings (catalogue only for now: the soundtrack composes its bed)

Audio is decoded with ffmpeg to 48 kHz mono 16-bit WAV (what the engine reads with no codec),
trimmed of leading silence and peak-normalised to -1 dBFS.

Further CC0 libraries go through the same mapping: add a SOURCES entry (zip URL, licence proof)
and a RULES row per file-name prefix. Music beds: see docs/ASSETS.md («بسته‌های دانلودی»).

Usage: python3 -I tools/packs/build_sound_packs.py <work-dir> <out-dir> [--version N]
"""
import json
import os
import re
import subprocess
import sys
import urllib.request
import zipfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import packlib  # noqa: E402

KENNEY = "https://kenney.nl/media/pages/assets/"
SOURCES = {
    "interface": KENNEY + "interface-sounds/fa43c1dd4d-1677589452/kenney_interface-sounds.zip",
    "impact": KENNEY + "impact-sounds/87b4ddecda-1677589768/kenney_impact-sounds.zip",
    "ui": KENNEY + "ui-audio/490d233f68-1677590494/kenney_ui-audio.zip",
    "digital": KENNEY + "digital-audio/216eac4753-1677590265/kenney_digital-audio.zip",
    "scifi": KENNEY + "sci-fi-sounds/6b296f9ecf-1677589334/kenney_sci-fi-sounds.zip",
    "casino": KENNEY + "casino-audio/2472606a04-1721639069/kenney_casino-audio.zip",
    "rpg": KENNEY + "rpg-audio/8e99002d76-1677590336/kenney_rpg-audio.zip",
    "jingles": KENNEY + "music-jingles/f37e530b9e-1677590399/kenney_music-jingles.zip",
}
ALL = ["punchy", "cinematic", "soft", "digital", "luxe", "playful"]
# (source, file-name prefix) -> (cue, palettes, tags). First match wins; unmatched files are left out.
RULES = [
    ("impact", "impactPunch", "impact", ["punchy", "cinematic"], ["punch", "hit"]),
    ("impact", "impactMetal_heavy", "impact", ["cinematic", "punchy"], ["metal", "heavy hit"]),
    ("impact", "impactPlate_heavy", "impact", ["cinematic"], ["heavy hit"]),
    ("impact", "impactBell", "impact", ["luxe", "cinematic"], ["bell"]),
    ("impact", "impactSoft", "impact", ["soft", "luxe"], ["soft hit"]),
    ("impact", "impactGeneric_light", "impact", ["soft", "digital"], ["light hit"]),
    ("impact", "impactWood", "pop", ["playful", "soft"], ["knock", "wood"]),
    ("impact", "impactPlank", "pop", ["playful"], ["knock"]),
    ("impact", "impactTin", "pop", ["playful"], ["tin"]),
    ("impact", "impactGlass_light", "shimmer", ["luxe"], ["glass", "ting"]),
    ("interface", "tick", "tick", ALL, ["tick"]),
    ("interface", "click", "glitch", ["digital", "soft", "luxe"], ["click"]),
    ("interface", "select", "glitch", ["digital", "soft"], ["select"]),
    ("interface", "switch", "glitch", ["digital"], ["switch"]),
    ("interface", "toggle", "glitch", ["digital", "playful"], ["toggle"]),
    ("interface", "glitch", "glitch", ["digital", "punchy"], ["glitch"]),
    ("interface", "drop", "pop", ["playful", "soft"], ["drop", "pop"]),
    ("interface", "pluck", "pop", ["playful", "luxe"], ["pluck"]),
    ("interface", "confirmation", "shimmer", ["soft", "luxe", "playful"], ["confirm", "success"]),
    ("interface", "glass", "shimmer", ["luxe", "soft"], ["glass", "ting"]),
    ("interface", "bong", "shimmer", ["luxe", "cinematic"], ["bong"]),
    ("interface", "maximize", "swoosh", ["soft", "digital"], ["open"]),
    ("interface", "minimize", "swoosh", ["soft", "digital"], ["close"]),
    ("interface", "open", "swoosh", ["soft"], ["open"]),
    ("interface", "scroll", "swoosh", ["digital", "soft"], ["scroll"]),
    ("interface", "scratch", "rewind", ["playful", "punchy"], ["scratch"]),
    ("ui", "click", "tick", ["digital", "soft"], ["click"]),
    ("ui", "switch", "glitch", ["digital", "soft"], ["switch"]),
    ("ui", "rollover", "tick", ["soft", "luxe"], ["hover"]),
    ("digital", "phaserUp", "riser", ["digital", "playful"], ["rise", "phaser"]),
    ("digital", "powerUp", "riser", ["playful", "digital"], ["power up"]),
    ("digital", "zapThreeToneUp", "riser", ["digital"], ["zap up"]),
    ("digital", "highUp", "riser", ["digital", "playful"], ["up"]),
    ("digital", "laser", "whoosh", ["digital"], ["laser"]),
    ("digital", "zap", "glitch", ["digital", "punchy"], ["zap"]),
    ("digital", "pepSound", "pop", ["playful", "digital"], ["blip"]),
    ("digital", "twoTone", "shimmer", ["digital", "playful"], ["chime"]),
    ("digital", "threeTone", "shimmer", ["digital", "playful"], ["chime"]),
    ("scifi", "forceField", "riser", ["digital", "cinematic"], ["force field"]),
    ("scifi", "laserLarge", "whoosh", ["digital", "cinematic"], ["laser"]),
    ("scifi", "laserRetro", "whoosh", ["digital", "playful"], ["laser"]),
    ("scifi", "laserSmall", "swoosh", ["digital"], ["laser"]),
    ("scifi", "explosionCrunch", "impact", ["cinematic", "punchy"], ["explosion", "boom"]),
    ("scifi", "lowFrequency_explosion", "impact", ["cinematic"], ["boom", "sub"]),
    ("scifi", "impactMetal", "impact", ["cinematic", "punchy"], ["metal"]),
    ("scifi", "computerNoise", "glitch", ["digital"], ["computer", "data"]),
    ("scifi", "doorOpen", "swoosh", ["digital", "cinematic"], ["door", "slide"]),
    ("scifi", "thrusterFire", "whoosh", ["cinematic"], ["thruster", "fire"]),
    ("casino", "card-slide", "swoosh", ["playful", "soft", "luxe"], ["card", "slide"]),
    ("casino", "card-fan", "swoosh", ["playful", "luxe"], ["cards"]),
    ("casino", "card-place", "pop", ["playful", "soft"], ["card"]),
    ("casino", "card-shove", "swoosh", ["playful"], ["card"]),
    ("casino", "chip-lay", "pop", ["luxe", "playful"], ["chip", "coin"]),
    ("casino", "chips-stack", "pop", ["luxe"], ["chips", "money"]),
    ("casino", "chips-collide", "shimmer", ["luxe", "playful"], ["chips", "money", "cash"]),
    ("rpg", "knifeSlice", "swoosh", ["punchy", "cinematic"], ["slice", "swipe"]),
    ("rpg", "drawKnife", "whoosh", ["cinematic", "punchy"], ["draw", "swipe"]),
    ("rpg", "bookFlip", "swoosh", ["soft", "luxe"], ["page", "flip"]),
    ("rpg", "cloth", "swoosh", ["soft"], ["cloth", "swish"]),
    ("rpg", "handleCoins", "shimmer", ["luxe", "playful"], ["coins", "money"]),
    ("rpg", "metalClick", "tick", ["luxe", "cinematic"], ["metal click"]),
    ("rpg", "chop", "impact", ["punchy"], ["chop"]),
]


def fetch(url, path):
    if not os.path.exists(path):
        req = urllib.request.Request(url, headers={"User-Agent": "Trimio pack builder"})
        with urllib.request.urlopen(req, timeout=300) as r, open(path + ".tmp", "wb") as f:
            f.write(r.read())
        os.replace(path + ".tmp", path)
    return path


def licence_ok(z):
    text = next((z.read(n).decode("utf-8", "replace") for n in z.namelist() if n.lower().endswith("license.txt")), "")
    return "CC0" in text and "kenney" in text.lower()


def to_wav(src_bytes, ext, out_path, work):
    tmp = os.path.join(work, "in." + ext)
    with open(tmp, "wb") as f:
        f.write(src_bytes)
    cmd = ["ffmpeg", "-v", "error", "-y", "-i", tmp, "-ac", "1", "-ar", "48000",
           "-af", "silenceremove=start_periods=1:start_threshold=-50dB,dynaudnorm=p=0.89:m=100:s=12:g=3", "-c:a", "pcm_s16le", out_path]
    subprocess.run(cmd, check=True)
    os.remove(tmp)
    return os.path.getsize(out_path)


def rule(source, name):
    for s, prefix, cue, palettes, tags in RULES:
        if s == source and name.startswith(prefix):
            return cue, palettes, tags
    return None


def build_sfx(work, out, key, version):
    wav_dir = os.path.join(work, "sfx-wav")
    os.makedirs(wav_dir, exist_ok=True)
    rows, files = [], []
    for source, url in SOURCES.items():
        if source == "jingles":
            continue
        z = zipfile.ZipFile(fetch(url, os.path.join(work, f"{source}.zip")))
        if not licence_ok(z):
            raise SystemExit(f"{source}: licence file does not say CC0")
        for n in sorted(z.namelist()):
            base = n.split("/")[-1]
            ext = base.rsplit(".", 1)[-1].lower()
            if ext not in ("ogg", "wav") or "/Preview" in n or base.startswith("Preview"):
                continue
            m = rule(source, base)
            if m is None:
                continue
            cue, palettes, tags = m
            sid = f"{source}-{re.sub(r'[^a-z0-9]+', '-', base.rsplit('.', 1)[0].lower()).strip('-')}"
            path = os.path.join(wav_dir, sid + ".wav")
            if not os.path.exists(path):
                to_wav(z.read(n), ext, path, work)
            rel = f"sound/sfx/{sid}.wav"
            rows.append({"id": sid, "cue": cue, "palettes": palettes, "file": rel, "tags": tags})
            files.append((rel, path))
    index = json.dumps({"v": 1, "sfx": rows, "music": []}, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
    cues = sorted({r["cue"] for r in rows})
    print("sfx:", len(rows), "takes for cues", cues)
    licence = b"Kenney audio packs (www.kenney.nl), Creative Commons Zero (CC0 1.0): http://creativecommons.org/publicdomain/zero/1.0/\n"
    all_files = [("pack.json", packlib.pack_json("sound.kenney-sfx", "Sound", "افکت‌های صوتی ضبط‌شده", "Recorded sound effects", len(rows), "CC0-1.0")),
                 ("sound/index.json", index), ("sound/LICENSE.txt", licence)] + files
    return packlib.build("sound.kenney-sfx", "Sound", version, all_files, out, key, "CC0-1.0",
                         titleFa="افکت‌های صوتی ضبط‌شده", titleEn="Recorded sound effects",
                         summaryFa="ضربه، ووش، کلیک و درخشش واقعی به‌جای صدای ساختگی، برای هر سبک", summaryEn="Real hits, whooshes, clicks and chimes instead of synthesised ones, per style",
                         items=len(rows), recommended=True)


def build_stings(work, out, key, version):
    wav_dir = os.path.join(work, "stings-wav")
    os.makedirs(wav_dir, exist_ok=True)
    z = zipfile.ZipFile(fetch(SOURCES["jingles"], os.path.join(work, "jingles.zip")))
    if not licence_ok(z):
        raise SystemExit("jingles: licence file does not say CC0")
    moods = {"HIT": ("energetic", ["hit", "energetic"]), "NES": ("playful", ["retro", "8-bit", "game"]), "PIZZI": ("playful", ["pizzicato", "light"]),
             "SAX": ("chill", ["sax", "jazz"]), "STEEL": ("uplifting", ["steel drum", "sunny"])}
    rows, files = [], []
    for n in sorted(z.namelist()):
        base = n.split("/")[-1]
        m = re.match(r"jingles_([A-Z]+)(\d+)\.(ogg|wav)$", base)
        if not m or m.group(1) not in moods:
            continue
        mood, tags = moods[m.group(1)]
        sid = f"sting-{m.group(1).lower()}-{m.group(2)}"
        path = os.path.join(wav_dir, sid + ".wav")
        if not os.path.exists(path):
            to_wav(z.read(n), m.group(3), path, work)
        seconds = round((os.path.getsize(path) - 44) / 96000, 2)
        rel = f"sound/music/{sid}.wav"
        rows.append({"id": sid, "title": f"{m.group(1).title()} sting {m.group(2)}", "mood": mood, "bpm": 0, "seconds": seconds, "file": rel, "tags": tags + ["sting", "jingle"]})
        files.append((rel, path))
    print("stings:", len(rows))
    index = json.dumps({"v": 1, "sfx": [], "music": rows}, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
    all_files = [("pack.json", packlib.pack_json("music.kenney-stings", "Music", "استینگ‌های موسیقی", "Music stings", len(rows), "CC0-1.0")),
                 ("sound/index.json", index)] + files
    return packlib.build("music.kenney-stings", "Music", version, all_files, out, key, "CC0-1.0",
                         titleFa="استینگ‌های موسیقی", titleEn="Music stings",
                         summaryFa="قطعه‌های کوتاه موسیقی برای شروع، رونمایی و پایان", summaryEn="Short musical stings for openings, reveals and endings",
                         items=len(rows))


def main():
    work, out = sys.argv[1], sys.argv[2]
    version = int(sys.argv[sys.argv.index("--version") + 1]) if "--version" in sys.argv else 1
    os.makedirs(work, exist_ok=True)
    key = packlib.load_key()
    build_sfx(work, out, key, version)
    build_stings(work, out, key, version)
    packlib.write_index(out)


if __name__ == "__main__":
    main()
