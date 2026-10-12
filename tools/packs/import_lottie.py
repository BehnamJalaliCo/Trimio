#!/usr/bin/env python3
"""
Turns a folder of Lottie animations the team downloaded themselves (from LottieFiles' website by
hand, or later through their official API with our key) into a signed art pack. Nothing here
fetches from LottieFiles: their terms forbid automated access to the site.

Each `.json` / `.lottie` (dotLottie: every animation inside) under <folder> is:
  - checked with the engine's feature check (tools/packs/artcheck.py, the Lottie reader's rules):
    text and image layers, 3D, expressions, layer effects and repeaters are refused, with the reason;
  - minified (editor-only keys dropped, numbers rounded; the same drawing);
  - tagged from its file name, its layer names and, when given, a CSV `file,tags[,tags_fa]`
    (tags separated by `;` or `|`), e.g.  rocket_launch.json,rocket;launch;startup,موشک;پرتاب

Usage:
  python3 -I tools/packs/import_lottie.py <folder> [--out DIR] [--id art.team] [--version N]
      [--csv tags.csv] [--title-fa "…"] [--title-en "…"] [--licence Lottie-Simple-License] [--report report.json]

The licence must be one the app accepts (ArtLicence): CC0-1.0, MIT, … or Lottie-Simple-License
(LottieFiles' free animations: commercial use, no attribution). Unknown licences are refused here
so a pack can never carry pictures the app would drop.
"""
import csv
import json
import os
import re
import sys
import zipfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import artcheck  # noqa: E402
import packlib  # noqa: E402

ALLOWED = {"CC0-1.0", "MIT", "ISC", "Apache-2.0", "BSD-3-Clause", "BSD-2-Clause", "Lottie-Simple-License", "LicenseRef-Trimio"}
NOISE = {
    "layer", "layers", "shape", "shapes", "group", "null", "precomp", "comp", "pre", "outlines", "outline", "ellipse", "rectangle", "rect",
    "fill", "stroke", "transform", "path", "merge", "trim", "copy", "solid", "control", "controller", "obj", "lottie", "animation", "anim",
    "json", "final", "new", "untitled", "the", "and", "with", "for", "of", "a", "an", "v", "lf", "ae",
}


def words(name):
    stem = re.sub(r"\.[^.]+$", "", os.path.basename(name))
    stem = re.sub(r"([a-z])([A-Z])", r"\1 \2", stem).lower()
    return [w for w in re.split(r"[^\w]+|_|\d+", stem) if len(w) > 1 and w not in NOISE]


def layer_words(doc):
    names = [layer.get("nm", "") for layer in doc.get("layers", [])]
    for a in doc.get("assets", []) or []:
        names += [layer.get("nm", "") for layer in a.get("layers", []) or []]
    names.append(doc.get("nm", ""))
    count = {}
    for n in names:
        for w in words(str(n) + ".x"):
            count[w] = count.get(w, 0) + 1
    return [w for w, _ in sorted(count.items(), key=lambda kv: -kv[1])][:16]


def animations(folder):
    """(name, parsed doc) for every animation under folder."""
    for root, _, files in os.walk(folder):
        for f in sorted(files):
            path = os.path.join(root, f)
            ext = f.lower().rsplit(".", 1)[-1]
            if ext == "json":
                try:
                    with open(path, encoding="utf-8") as fh:
                        yield f, json.load(fh)
                except (OSError, ValueError) as e:
                    yield f, e
            elif ext == "lottie":
                try:
                    with zipfile.ZipFile(path) as z:
                        inner = [n for n in z.namelist() if n.startswith("animations/") and n.endswith(".json")]
                        for n in inner:
                            name = f[:-7] + (".json" if len(inner) == 1 else "-" + os.path.basename(n))
                            yield name, json.loads(z.read(n))
                except (OSError, ValueError, zipfile.BadZipFile) as e:
                    yield f, e


def read_csv(path):
    out = {}
    if not path:
        return out
    with open(path, encoding="utf-8") as f:
        for row in list(csv.reader(f))[1:]:
            if len(row) >= 2 and row[0].strip():
                split = lambda s: [t.strip() for t in re.split(r"[;|]", s or "") if t.strip()]  # noqa: E731
                out[os.path.basename(row[0].strip())] = (split(row[1]), split(row[2] if len(row) > 2 else ""))
    return out


def build(folder, out, pack_id, version, csv_path, title_fa, title_en, licence, key):
    if licence not in ALLOWED:
        sys.exit(f"licence {licence} is not one the app accepts: {sorted(ALLOWED)}")
    source = pack_id.split(".", 1)[-1]
    tags_csv = read_csv(csv_path)
    rows, docs, refused, keys = [], {}, {}, set()
    for name, doc in animations(folder):
        if isinstance(doc, Exception):
            refused[name] = f"unreadable: {doc}"
            continue
        problem = artcheck.lottie_problem(doc)
        if problem:
            refused[name] = problem
            continue
        base = "-".join(words(name))[:60] or "animation"
        k, n = base, 2
        while k in keys:
            k, n = f"{base}-{n}", n + 1
        keys.add(k)
        extra, extra_fa = tags_csv.get(name, ([], []))
        tags = list(dict.fromkeys([t.lower() for t in extra + words(name) + layer_words(doc)]))[:16]
        rows.append([source, k, " ".join(words(name)) or k, tags, extra_fa, 0, "imported"])
        docs[f"art/docs/{k}.json"] = json.dumps(packlib.minify_lottie(doc), separators=(",", ":")).encode("utf-8")
    if not rows:
        sys.exit(f"nothing to pack; refused: {refused}")
    index = {"v": 1, "sources": {source: {
        "title": title_en, "author": "", "licence": licence, "credit": None, "home": "", "url": f"local:{source}/{{id}}",
        "docs": "art/docs/{id}.json", "format": "lottie", "w": 512, "h": 512, "loops": True, "flavour": "emoji"}}, "art": rows}
    files = [("pack.json", packlib.pack_json(pack_id, "Art", title_fa, title_en, len(rows), licence)),
             ("art/index.json", json.dumps(index, ensure_ascii=False, separators=(",", ":")).encode("utf-8"))] + sorted(docs.items())
    m = packlib.build(pack_id, "Art", version, files, out, key, licence, titleFa=title_fa, titleEn=title_en,
                      summaryFa="انیمیشن‌های منتخب تیم برای لحظه‌های خاص ادیت", summaryEn="Animations the team picked for special moments", items=len(rows))
    print(f"{len(rows)} animations packed, {len(refused)} refused")
    return m, refused


def main():
    args = sys.argv[1:]
    if not args:
        sys.exit(__doc__)
    opt = lambda k, d=None: args[args.index(k) + 1] if k in args else d  # noqa: E731
    folder = args[0]
    out = opt("--out", "build/packs")
    _, refused = build(folder, out, opt("--id", "art.team"), int(opt("--version", "1")), opt("--csv"), opt("--title-fa", "انیمیشن‌های تیم"),
                       opt("--title-en", "Team animations"), opt("--licence", "Lottie-Simple-License"), packlib.load_key())
    for name, why in refused.items():
        print("refused:", name, "—", why)
    if opt("--report"):
        with open(opt("--report"), "w", encoding="utf-8") as f:
            json.dump(refused, f, ensure_ascii=False, indent=1)
    packlib.write_index(out)


if __name__ == "__main__":
    main()
