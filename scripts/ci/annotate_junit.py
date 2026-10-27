#!/usr/bin/env python3
"""Emit GitHub Actions ::error annotations for failed JUnit tests (visible without login)."""
import glob
import os
import sys
import xml.etree.ElementTree as ET

root = sys.argv[1] if len(sys.argv) > 1 else "."
files = sorted(set(glob.glob(os.path.join(root, "**", "TEST-*.xml"), recursive=True)))

fails = []
skips = 0
tests = 0
modules = {}
for f in files:
    try:
        tree = ET.parse(f)
    except Exception:
        continue
    mod = f.split("build")[0].strip("/\\") or "."
    modules[mod] = modules.get(mod, 0) + 1
    r = tree.getroot()
    tests += int(r.get("tests") or 0)
    skips += int(r.get("skipped") or 0)
    for tc in r.iter("testcase"):
        for kind in ("failure", "error"):
            for node in tc.iter(kind):
                name = (tc.get("classname") or "?") + "." + (tc.get("name") or "?")
                msg = node.get("message") or node.text or "failed"
                msg = " ".join(msg.split())[:240]
                fails.append((name, msg))

for name, msg in fails[:10]:
    print("::error title=%s::%s" % (name, msg.replace("\r", " ").replace("\n", " ")))

motion = {k: v for k, v in modules.items() if "motion" in k}
print("::notice title=junit-scan::files=%d tests=%d skipped=%d failures=%d modules=%d motion-mods=%s" % (
    len(files), tests, skips, len(fails), len(modules), motion))
for name, msg in fails[10:40]:
    print("FAIL %s :: %s" % (name, msg))
if not files:
    print("::warning title=junit-scan::no TEST-*.xml under %s" % root)
