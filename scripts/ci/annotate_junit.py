#!/usr/bin/env python3
"""Emit GitHub Actions ::error annotations for failed JUnit tests (visible without login)."""
import glob
import os
import sys
import xml.etree.ElementTree as ET

root = sys.argv[1] if len(sys.argv) > 1 else "."
roots = [root] if os.path.isdir(root) else ["."]
files = []
for r in roots:
    files += glob.glob(os.path.join(r, "**", "TEST-*.xml"), recursive=True)
    files += glob.glob(os.path.join(r, "TEST-*.xml"))
files = sorted(set(files))

fails = []
for f in files:
    try:
        tree = ET.parse(f)
    except Exception:
        continue
    for tc in tree.getroot().iter("testcase"):
        for kind in ("failure", "error"):
            for node in tc.iter(kind):
                name = (tc.get("classname") or "?") + "." + (tc.get("name") or "?")
                msg = node.get("message") or node.text or "failed"
                msg = " ".join(msg.split())[:240]
                fails.append((name, msg))

for name, msg in fails[:10]:
    safe = msg.replace("\r", " ").replace("\n", " ")
    print("::error title=%s::%s" % (name, safe))

print("::notice title=junit-scan::files=%d failures=%d" % (len(files), len(fails)))
for name, msg in fails[10:40]:
    print("FAIL %s :: %s" % (name, msg))
if not files:
    print("::warning title=junit-scan::no TEST-*.xml under %s" % root)
