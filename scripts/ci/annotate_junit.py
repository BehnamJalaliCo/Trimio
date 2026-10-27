#!/usr/bin/env python3
"""Emit GitHub Actions ::error annotations for failed JUnit tests and detekt findings."""
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

# detekt checkstyle/xml reports: <file name="..."><error message="..." source="detekt.Rule" line="n"/></file>
detekt_findings = []
for f in sorted(set(glob.glob(os.path.join(root, "**", "reports", "detekt", "*.xml"), recursive=True))):
    try:
        tree = ET.parse(f)
    except Exception:
        continue
    for fe in tree.getroot().iter("file"):
        fname = os.path.basename(fe.get("name") or "?")
        for err in fe.iter("error"):
            rule = (err.get("source") or "detekt").split(".")[-1]
            msg = " ".join((err.get("message") or "").split())[:200]
            line = err.get("line") or "?"
            detekt_findings.append(("%s:%s" % (fname, line), "%s — %s" % (rule, msg)))
# plain-text detekt reports (fallback)
if not detekt_findings:
    for f in sorted(set(glob.glob(os.path.join(root, "**", "reports", "detekt", "*.txt"), recursive=True))):
        try:
            with open(f, encoding="utf-8", errors="replace") as fh:
                for ln in fh:
                    ln = ln.strip()
                    if ln and not ln.startswith("-") and "detekt" in ln.lower():
                        detekt_findings.append(("detekt", ln[:240]))
        except Exception:
            pass

all_findings = [("TEST " + n, m) for n, m in fails] + [("DETEKT " + n, m) for n, m in detekt_findings]
for name, msg in all_findings[:10]:
    print("::error title=%s::%s" % (name, msg.replace("\r", " ").replace("\n", " ")))

motion = {k: v for k, v in modules.items() if "motion" in k}
print("::notice title=scan::junit-files=%d tests=%d skipped=%d test-failures=%d detekt-findings=%d motion-mods=%s" % (
    len(files), tests, skips, len(fails), len(detekt_findings), motion))
for name, msg in all_findings[10:40]:
    print("FINDING %s :: %s" % (name, msg))
if not files and not detekt_findings:
    print("::warning title=scan::no TEST-*.xml and no detekt reports under %s" % root)
