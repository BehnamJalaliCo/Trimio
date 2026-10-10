#!/usr/bin/env python3
"""
Builds the brand-mark packs from Simple Icons (CC0-1.0, https://simpleicons.org).

  core.json  — curated marks bundled with the app (AI and dev tools, crypto and trading,
               social platforms, big tech), with Persian and common aliases.
  full.json  — every mark, published as a downloadable pack.

Usage: build_pack.py <simple-icons package dir> <core.json out> [<full.json out>]
Brand marks are trademarks of their owners; the app shows them only in the user's own content
about those products (nominative use).
"""
import json
import re
import sys

CORE = """
claude claudecode anthropic opencode cursor githubcopilot googlegemini perplexity ollama huggingface mistralai
meta deepseek replit vercel notion figma github gitlab visualstudiocode jetbrains android apple kotlin python
javascript typescript react nodedotjs docker linux ubuntu google googlechrome firefoxbrowser x instagram telegram
whatsapp youtube youtubeshorts tiktok linkedin discord reddit facebook threads spotify netflix
bitcoin ethereum tether binance solana ripple cardano dogecoin litecoin polygon chainlink bnbchain coinbase kucoin
bybit okx tradingview metamask uniswap opensea paypal visa mastercard stripe revolut wise
""".split()

ALIASES = {
    "claude": ["کلاد"], "claudecode": ["claude code", "کلاد کد", "کلاد کود"], "anthropic": ["انتروپیک", "آنتروپیک"],
    "opencode": ["open code", "اوپن کد", "اوپن کود"], "cursor": ["کرسر"], "githubcopilot": ["copilot", "کوپایلت"],
    "googlegemini": ["gemini", "جمنای", "جمینای"], "perplexity": ["پرپلکسیتی"], "github": ["گیت‌هاب", "گیتهاب"],
    "bitcoin": ["btc", "بیت‌کوین", "بیتکوین"], "ethereum": ["eth", "اتریوم"], "tether": ["usdt", "تتر"],
    "binance": ["bnb", "بایننس"], "solana": ["sol", "سولانا"], "ripple": ["xrp", "ریپل"], "dogecoin": ["doge", "دوج"],
    "tradingview": ["تریدینگ ویو", "تریدینگ‌ویو"], "telegram": ["تلگرام"], "instagram": ["اینستاگرام", "اینستا"],
    "youtube": ["یوتیوب"], "x": ["twitter", "توییتر", "ایکس"], "whatsapp": ["واتساپ"], "tiktok": ["تیک‌تاک", "تیکتاک"],
    "google": ["گوگل"], "apple": ["اپل"], "android": ["اندروید"], "python": ["پایتون"], "docker": ["داکر"],
    "metamask": ["متامسک"], "coinbase": ["کوین‌بیس"], "kucoin": ["کوکوین"], "bybit": ["بای‌بیت"], "okx": ["اوکی‌ایکس"],
}


def expand_arcs(d: str) -> str:
    """Spells out SVG arc flags ("a.5.5 0 01.5.5") so strict path parsers read them."""
    out = []
    for cmd, args in re.findall(r"([MmZzLlHhVvCcSsQqTtAa])([^MmZzLlHhVvCcSsQqTtAa]*)", d):
        if cmd not in "Aa":
            out.append(cmd + args)
            continue
        nums = []
        s = args.strip()
        pos = 0
        num = re.compile(r"[+-]?(?:\d+\.?\d*|\.\d+)(?:[eE][+-]?\d+)?")
        k = 0
        while pos < len(s):
            if s[pos] in " ,\t\n":
                pos += 1
                continue
            if k % 7 in (3, 4):  # the two flags are single digits, possibly unseparated
                nums.append(s[pos])
                pos += 1
            else:
                m = num.match(s, pos)
                if not m:
                    raise ValueError(f"bad arc args: {args}")
                nums.append(m.group(0))
                pos = m.end()
            k += 1
        out.append(cmd + " ".join(nums))
    return "".join(out)


NUM = re.compile(r"[+-]?(?:\d+\.?\d*|\.\d+)(?:[eE][+-]?\d+)?")
ARITY = {"M": 2, "L": 2, "H": 1, "V": 1, "C": 6, "S": 4, "Q": 4, "T": 2, "A": 7, "Z": 0}


def absolute(d: str) -> str:
    """Rewrites a path in absolute commands, so it can be split into contours at every M."""
    d = expand_arcs(d)
    out = []
    x = y = sx = sy = 0.0
    for cmd, args in re.findall(r"([MmZzLlHhVvCcSsQqTtAa])([^MmZzLlHhVvCcSsQqTtAa]*)", d):
        up = cmd.upper()
        rel = cmd.islower()
        if up == "Z":
            out.append("Z")
            x, y = sx, sy
            continue
        vals = [float(v) for v in NUM.findall(args)]
        n = ARITY[up]
        first = True
        for i in range(0, len(vals), n):
            v = vals[i:i + n]
            c = up
            if up == "M" and not first:
                c = "L"  # extra pairs after a moveto are linetos
            if c in "MLT":
                nx, ny = (v[0] + x, v[1] + y) if rel else (v[0], v[1])
                out.append(f"{c}{nx:.4g} {ny:.4g}")
                x, y = nx, ny
                if c == "M":
                    sx, sy = x, y
            elif c == "H":
                x = v[0] + x if rel else v[0]
                out.append(f"H{x:.4g}")
            elif c == "V":
                y = v[0] + y if rel else v[0]
                out.append(f"V{y:.4g}")
            elif c in "CSQ":
                pts = [(v[j] + (x if rel else 0), v[j + 1] + (y if rel else 0)) for j in range(0, n, 2)]
                out.append(c + " ".join(f"{px:.4g} {py:.4g}" for px, py in pts))
                x, y = pts[-1]
            elif c == "A":
                ex, ey = (v[5] + x, v[6] + y) if rel else (v[5], v[6])
                out.append(f"A{v[0]:.4g} {v[1]:.4g} {v[2]:.4g} {int(v[3])} {int(v[4])} {ex:.4g} {ey:.4g}")
                x, y = ex, ey
            first = False
    return "".join(out)


def main():
    pkg, core_out = sys.argv[1], sys.argv[2]
    full_out = sys.argv[3] if len(sys.argv) > 3 else None
    data = json.load(open(f"{pkg}/data/simple-icons.json"))
    marks = {}
    for icon in data:
        slug = icon.get("slug") or re.sub(r"[^a-z0-9]", "", icon["title"].lower())
        try:
            svg = open(f"{pkg}/icons/{slug}.svg").read()
        except FileNotFoundError:
            continue
        path = re.search(r' d="([^"]+)"', svg).group(1)
        aka = (icon.get("aliases") or {}).get("aka", [])
        marks[slug] = {"t": icon["title"], "c": icon["hex"], "d": absolute(path), "a": sorted(set(aka + ALIASES.get(slug, [])))}
    core = {s: marks[s] for s in CORE if s in marks}
    missing = [s for s in CORE if s not in marks]
    json.dump(core, open(core_out, "w"), ensure_ascii=False, separators=(",", ":"))
    if full_out:
        json.dump(marks, open(full_out, "w"), ensure_ascii=False, separators=(",", ":"))
    print(f"core {len(core)} marks, full {len(marks)}; not in Simple Icons: {' '.join(missing)}")


if __name__ == "__main__":
    main()
