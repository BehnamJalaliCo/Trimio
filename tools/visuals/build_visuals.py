#!/usr/bin/env python3
"""
Builds the visual vocabulary: open icon sets flattened into a compact vector format the motion
engine draws directly (paths with solid or gradient paint, no SVG at run time).

Sets (all permissive licences, see tools/visuals/NOTICE.md):
  fluent  Fluent Emoji Flat (MIT)        colourful objects, food, people, animals, places, symbols
  health  Health Icons (MIT)             medicine, body, devices, conditions, care
  mdi     Material Design Icons (Apache) automotive, tools, buildings, devices, finance, …
  tabler  Tabler Icons (MIT)             outline icons for everything else

Usage: build_visuals.py <iconify-json packages dir> <out dir>
Writes one <set>.json per set: {name: {"k": keywords, "v": [w, h], "e": [element, …]}} where an
element is [d, paint, opacity, evenodd, strokeWidth] and paint is "#rrggbbaa", "cur" (current
colour) or a gradient {"t": "l"|"r", "c": coords, "m": matrix, "s": [[offset, "#rrggbbaa"], …]}.
Elements under filters (soft shadows) are dropped; icons that need masks are skipped.
Requires: pip install svgelements
"""
import json
import os
import re
import sys
import xml.etree.ElementTree as ET

import svgelements as se

SETS = {
    "fluent": "iconify-json-fluent-emoji-flat",
    "health": "iconify-json-healthicons",
    "mdi": "iconify-json-mdi",
    "tabler": "iconify-json-tabler",
}
SVGNS = "http://www.w3.org/2000/svg"
XLINK = "{http://www.w3.org/1999/xlink}href"


def hexa(color: str, opacity: float = 1.0):
    if color in (None, "none", "transparent"):
        return None
    if color == "currentColor":
        return "cur"
    c = se.Color(color)
    a = int(round(c.alpha * opacity))
    return "#%02x%02x%02x%02x" % (c.red, c.green, c.blue, a)


def num(v, default=0.0, ref=1.0):
    if v is None:
        return default
    v = str(v).strip()
    if v.endswith("%"):
        return float(v[:-1]) / 100.0 * ref
    return float(v)


def gradients(root):
    """id → raw gradient spec, following href inheritance."""
    raw = {}
    for el in root.iter():
        tag = el.tag.split("}")[-1]
        if tag in ("linearGradient", "radialGradient"):
            raw[el.get("id")] = (tag, el)

    def resolve(gid, depth=0):
        tag, el = raw[gid]
        attrs = dict(el.attrib)
        stops = [s for s in el if s.tag.split("}")[-1] == "stop"]
        href = el.get(XLINK) or el.get("href")
        if href and href[1:] in raw and depth < 4:
            parent = resolve(href[1:], depth + 1)
            merged = dict(parent["attrs"])
            merged.update(attrs)
            attrs = merged
            if not stops:
                stops = parent["stops"]
        return {"tag": tag, "attrs": attrs, "stops": stops}

    return {gid: resolve(gid) for gid in raw}


def stop_list(stops):
    out = []
    for s in stops:
        style = dict(kv.split(":", 1) for kv in (s.get("style") or "").split(";") if ":" in kv)
        color = s.get("stop-color") or style.get("stop-color", "#000")
        opacity = float(s.get("stop-opacity") or style.get("stop-opacity", 1))
        out.append([round(num(s.get("offset"), 0.0), 4), hexa(color.strip(), opacity) or "#00000000"])
    return out


def gradient_paint(spec, shape_matrix, bbox):
    a = spec["attrs"]
    units = a.get("gradientUnits", "objectBoundingBox")
    gt = se.Matrix(a["gradientTransform"]) if "gradientTransform" in a else se.Matrix()
    if spec["tag"] == "linearGradient":
        coords = [num(a.get("x1"), 0.0), num(a.get("y1"), 0.0), num(a.get("x2"), 1.0), num(a.get("y2"), 0.0)]
        if a.get("x2", "").endswith("%") or units == "objectBoundingBox" and a.get("x2") is None:
            coords[2] = num(a.get("x2"), 1.0)
    else:
        r = num(a.get("r"), 0.5)
        cx = num(a.get("cx"), 0.5)
        cy = num(a.get("cy"), 0.5)
        coords = [cx, cy, r, num(a.get("fx"), cx), num(a.get("fy"), cy)]
    m = se.Matrix(gt)
    if units == "objectBoundingBox":
        x0, y0, x1, y1 = bbox
        m = m * se.Matrix(f"matrix({x1 - x0} 0 0 {y1 - y0} {x0} {y0})")
    else:
        m = m * shape_matrix
    return {
        "t": "l" if spec["tag"] == "linearGradient" else "r",
        "c": [round(c, 4) for c in coords],
        "m": [round(v, 5) for v in (m.a, m.b, m.c, m.d, m.e, m.f)],
        "s": stop_list(spec["stops"]),
    }


def flatten(body: str, width: float, height: float):
    svg_text = f'<svg xmlns="{SVGNS}" xmlns:xlink="http://www.w3.org/1999/xlink" viewBox="0 0 {width} {height}" width="{width}" height="{height}">{body}</svg>'
    root = ET.fromstring(svg_text)
    if any(el.tag.split("}")[-1] in ("mask",) for el in root.iter()):
        return None
    grads = gradients(root)
    svg = se.SVG.parse(__import__("io").StringIO(svg_text), reify=False)
    elements = []
    for el in svg.elements():
        if not isinstance(el, se.Shape) or isinstance(el, se.SVGText):
            continue
        vals = el.values
        if vals.get("filter") or "filter" in str(vals.get("style", "")):
            continue
        path = se.Path(el)
        matrix = se.Matrix(el.transform)
        path.reify()
        d = path.d()
        if not d or d in ("M0,0", ""):
            continue
        opacity = float(vals.get("opacity", 1) or 1) * float(vals.get("fill-opacity", 1) or 1)
        evenodd = 1 if vals.get("fill-rule") == "evenodd" else 0
        fill_raw = vals.get("fill")
        stroke_raw = vals.get("stroke")
        try:
            bbox = path.bbox()
        except Exception:
            bbox = None
        if fill_raw and str(fill_raw).startswith("url("):
            gid = re.search(r"#([^)]+)\)", fill_raw).group(1)
            if gid in grads and bbox:
                elements.append([d, gradient_paint(grads[gid], matrix, bbox), round(opacity, 3), evenodd, 0])
        elif fill_raw not in (None, "none") and not (fill_raw == "none"):
            paint = hexa(str(fill_raw))
            if paint:
                elements.append([d, paint, round(opacity, 3), evenodd, 0])
        if stroke_raw not in (None, "none"):
            sw = float(vals.get("stroke-width", 1) or 1) * (abs(matrix.a * matrix.d - matrix.b * matrix.c) ** 0.5 or 1)
            paint = hexa(str(stroke_raw)) if not str(stroke_raw).startswith("url(") else None
            if paint:
                elements.append([d, paint, round(float(vals.get("stroke-opacity", 1) or 1) * opacity, 3), 0, round(sw, 3)])
    return elements


def keywords(name, categories, aliases, tags=()):
    words = set(name.replace("-", " ").split())
    words.add(name.replace("-", " "))
    return sorted(words | set(categories) | set(aliases) | set(tags))


def emoji_tags(src):
    """CLDR emoji keywords (emojibase-data, MIT) by Fluent icon name: 'automobile' → car, vehicle…"""
    eb = os.path.join(src, "eb", "package", "en", "data.json")
    fluent = next((os.path.join(src, d, "package", "chars.json") for d in os.listdir(src) if d.startswith(SETS["fluent"]) and os.path.isdir(os.path.join(src, d))), None)
    if not os.path.exists(eb) or not fluent:
        return {}
    by_hex = {}
    for e in json.load(open(eb)):
        by_hex[e["hexcode"].lower()] = e.get("tags", []) + [e["label"]]
        for skin in e.get("skins", []) or []:
            by_hex[skin["hexcode"].lower()] = e.get("tags", []) + [e["label"]]
    out = {}
    for code, name in json.load(open(fluent)).items():
        tags = by_hex.get(code.lower()) or by_hex.get(code.lower().replace("-fe0f", ""))
        if tags:
            out.setdefault(name, set()).update(tags)
    return out


def main():
    src, out = sys.argv[1], sys.argv[2]
    os.makedirs(out, exist_ok=True)
    tags_of = emoji_tags(src)
    for key, prefix in SETS.items():
        pkg = next(os.path.join(src, d, "package") for d in os.listdir(src) if d.startswith(prefix) and os.path.isdir(os.path.join(src, d)))
        data = json.load(open(os.path.join(pkg, "icons.json")))
        meta = json.load(open(os.path.join(pkg, "metadata.json")))
        cat_of = {}
        for cat, names in meta.get("categories", {}).items():
            for n in names:
                cat_of.setdefault(n, []).append(cat.lower())
        alias_of = {}
        for alias, a in data.get("aliases", {}).items():
            alias_of.setdefault(a.get("parent"), []).append(alias.replace("-", " "))
        w0, h0 = data.get("width", 24), data.get("height", 24)
        icons, skipped = {}, 0
        for name, icon in data["icons"].items():
            if name.endswith("-off") or name.endswith("-filled") and key == "tabler":
                continue
            w, h = icon.get("width", w0), icon.get("height", h0)
            try:
                els = flatten(icon["body"], w, h)
            except Exception:
                els = None
            if not els:
                skipped += 1
                continue
            if key != "fluent":
                # Single-colour sets draw in the look's ink: every paint becomes the current colour.
                els = [[d, "cur" if isinstance(p, str) else p, o * (int(p[7:9], 16) / 255 if isinstance(p, str) and len(p) == 9 else 1), eo, sw]
                       for d, p, o, eo, sw in els]
            tags = tags_of.get(name, ()) if key == "fluent" else ()
            icons[name] = {"k": keywords(name, cat_of.get(name, []), alias_of.get(name, []), tags), "v": [w, h], "e": els}
        path = os.path.join(out, f"{key}.json")
        json.dump(icons, open(path, "w"), separators=(",", ":"))
        print(f"{key}: {len(icons)} icons, {skipped} skipped, {os.path.getsize(path) // 1024} KB")


if __name__ == "__main__":
    main()
