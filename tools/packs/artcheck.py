#!/usr/bin/env python3
"""
The engine's feature check for animation documents, in Python, so pack builders and the Lottie
importer drop what the app would refuse to draw. Mirrors engine/motion/.../art/Lottie.kt
(LottieReader) and SvgMotion.kt (SvgMotionReader) — keep in step with them. The app checks every
document again with the real reader (MotionArts.readOrWhy); this only keeps refused files out of packs.

    lottie_problem(doc_dict) -> None | reason
    svg_problem(body_str)    -> None | reason
"""
import re

LOTTIE_SHAPES = {"gr", "sh", "rc", "el", "fl", "st", "gf", "gs", "tm", "mm", "tr"}
SVG_REFUSED = {"animateMotion", "use", "symbol", "image", "text", "filter", "clipPath", "linearGradient", "radialGradient", "pattern", "foreignObject", "style"}
SVG_SHAPES = {"g", "path", "circle", "ellipse", "rect", "line", "polyline", "polygon"}
SVG_SKIPPED = {"defs", "mask", "animate", "set", "animateTransform", "title", "desc", "metadata"}
SVG_ANIMATABLE = {
    "d", "opacity", "fill-opacity", "stroke-opacity", "stroke-dashoffset", "stroke-dasharray", "stroke-width",
    "r", "cx", "cy", "rx", "ry", "x", "y", "width", "height", "x1", "y1", "x2", "y2", "points", "fill", "stroke", "transform",
}
TOKEN = re.compile(r"""<(/?)([a-zA-Z][\w-]*)((?:[^>"']|"[^"]*"|'[^']*')*?)(/?)>""")
ATTR = re.compile(r"""([a-zA-Z_:][\w:.-]*)\s*=\s*"([^"]*)\"""")


def _expression(prop):
    return isinstance(prop, dict) and isinstance(prop.get("x"), str)


def _props(o):
    """Animated properties anywhere under o (dicts with a "k" and an "a"/"x")."""
    if isinstance(o, dict):
        if "k" in o and ("a" in o or "x" in o):
            yield o
        for v in o.values():
            yield from _props(v)
    elif isinstance(o, list):
        for v in o:
            yield from _props(v)


def _shapes(items):
    for it in items or []:
        if not isinstance(it, dict) or it.get("hd"):
            continue
        ty = it.get("ty")
        if ty not in LOTTIE_SHAPES:
            return f"shape item {ty}"
        if ty == "gr":
            r = _shapes(it.get("it"))
            if r:
                return r
    return None


def _layer(layer, comps):
    if layer.get("ddd") == 1:
        return "3D layer"
    if layer.get("ef"):
        return "layer effects"
    ty = layer.get("ty")
    if ty == 2:
        return "image layer"
    if ty == 5:
        return "text layer"
    if ty not in (0, 1, 3, 4):
        return f"layer type {ty}"
    if ty == 0 and layer.get("refId") not in comps:
        return f"missing precomposition {layer.get('refId')}"
    ks = layer.get("ks") or {}
    for axis in ("rx", "ry"):
        if axis in ks:
            return "3D rotation"
    for m in layer.get("masksProperties") or []:
        x = m.get("x")
        if isinstance(x, dict) and x.get("k") not in (0, None) and not (isinstance(x.get("k"), list)):
            return "mask expansion"
    if ty == 4:
        return _shapes(layer.get("shapes"))
    return None


def lottie_problem(doc):
    """None when the engine draws [doc] (a parsed Lottie dict), else the reason it refuses it."""
    if not isinstance(doc, dict) or "layers" not in doc:
        return "not a Lottie animation"
    for key in ("w", "h", "op"):
        if doc.get(key) is None:
            return f"no {key}"
    if doc.get("ddd") == 1:
        return "3D layers"
    assets = doc.get("assets") or []
    if any("layers" not in a for a in assets):
        return "image or font assets"
    if doc.get("chars") or (doc.get("fonts") or {}).get("list"):
        return "text"
    if any(_expression(p) for p in _props(doc)):
        return "expression"
    comps = {a.get("id") for a in assets}
    for layers in [doc["layers"]] + [a["layers"] for a in assets]:
        for layer in layers:
            r = _layer(layer, comps)
            if r:
                return r
    return None


def svg_problem(body):
    """None when the engine draws the animated SVG icon [body], else the reason it refuses it."""
    nodes = 0
    for m in TOKEN.finditer(body):
        close, tag, rest = m.group(1), m.group(2), m.group(3)
        if tag in SVG_REFUSED:
            return f"svg <{tag}>"
        if close == "/":
            continue
        attrs = dict(ATTR.findall(rest))
        if "transform-origin" in attrs or "clip-path" in attrs or "filter" in attrs:
            return "svg attribute"
        if tag not in SVG_SHAPES and tag not in SVG_SKIPPED:
            return f"svg <{tag}>"
        if tag in SVG_SHAPES:
            nodes += 1
        if tag in ("animate", "set", "animateTransform"):
            name = attrs.get("attributeName")
            if name and name not in SVG_ANIMATABLE:
                return f"svg animation of {name}"
            if name == "transform" and tag != "animateTransform":
                return "svg transform animation"
            for t in (attrs.get("begin", "0s"), attrs.get("dur", "1s")):
                if any(w in t for w in ("begin", "end", "click", ";")):
                    return f"svg timing {t}"
            if tag != "set" and "dur" not in attrs:
                return "svg animation without dur"
    return None if nodes else "empty svg"
