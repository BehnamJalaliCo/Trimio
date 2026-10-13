"""Workshop scenarios -> eval items (stdlib only).

Reads engine/autopilot/src/jvmTest/.../workshop/Scenarios.kt (read-only) and rebuilds, per scenario,
what the recogniser heard and the ground truth from the script marks:
  {clean|heard}  a recogniser mistake,  ~word  a filler,  *word  a stressed word.
Only the marked mistakes are reproduced (the Kotlin Recogniser also adds random sound-alike swaps
at `noise`); the Kotlin harness variant uses the real Scenario objects instead.

Lines follow the app's Lines.split rules approximately (sentence ends close a line, lines over 12
words break at the comma nearest the middle, scraps under 3 words merge into a neighbour).
"""
import json
import re
import sys

LIT = re.compile(r'"""(.*?)"""|"((?:[^"\\]|\\.)*)"', re.S)
END = set('.!?؟')
SOFT = set('،,;:؛')


def _unescape(s):
    return s.replace("${'$'}", "$").replace('\\$', '$').replace('\\"', '"').replace('\\n', '\n')


def parse(path):
    src = open(path, encoding='utf-8').read()
    body = src[src.index('private val SPECS'):]
    out = []
    for block in body.split('Spec(')[1:]:
        block = block.split('\n        ),')[0]
        lits = []
        for m in LIT.finditer(block):
            raw = m.group(1) if m.group(1) is not None else m.group(2)
            lits.append((m.start(), _unescape(raw)))
        if len(lits) < 5:
            continue
        cta_m = re.search(r'Cta\("([a-z]+)"(?:,\s*"([^"]*)")?\)|,\s*null,', block)
        cta_pos = cta_m.start() if cta_m else len(block)
        sid, lang, domain = lits[0][1], lits[1][1], lits[2][1]
        prompt = '\n'.join(l.strip() for l in lits[3][1].strip().splitlines())
        script = ''.join(t for p, t in lits[4:] if p < cta_pos)
        cta = None
        if cta_m and cta_m.group(1):
            cta = {'action': cta_m.group(1), 'keyword': cta_m.group(2) or ''}
        out.append(build(sid, lang, domain, prompt, script, cta))
    return out


def units(script):
    """[(heard_words, truth_words, error_pair_or_None)] in script order."""
    res = []
    for m in re.finditer(r'\{([^|}]*)\|([^}]*)\}|(\S+)', script):
        if m.group(3) is not None:
            w = m.group(3)
            if w.startswith('~'):
                res.append(([w[1:]], [], None))  # heard filler, absent from truth
            else:
                w = w.lstrip('*')
                res.append(([w], [w], None))
        else:
            clean, heard = m.group(1), m.group(2)
            res.append((heard.split(), clean.split(), (clean, heard)))
    return res


def build(sid, lang, domain, prompt, script, cta):
    us = units(script)
    lines, cur = [], []
    for u in us:
        cur.append(u)
        last = (u[0] or u[1] or [''])[-1]
        if last and last[-1] in END:
            lines.append(cur)
            cur = []
    if cur:
        lines.append(cur)
    lines = [p for l in lines for p in _break(l)]
    lines = _merge(lines)
    words, out_lines = [], []
    for l in lines:
        first = len(words)
        for h, _, _ in l:
            words.extend(h)
        out_lines.append({
            'first': first, 'last': len(words) - 1,
            'heard': ' '.join(w for h, _, _ in l for w in h),
            'truth': ' '.join(w for _, t, _ in l for w in t),
            'errors': [e for _, _, e in l if e],
        })
    return {'id': sid, 'language': lang, 'domain': domain, 'prompt': prompt, 'cta': cta,
            'words': words, 'lines': out_lines}


def _n(line):
    return sum(len(h) for h, _, _ in line)


def _break(line, max_words=12):
    if _n(line) <= max_words:
        return [line]
    mid = _n(line) / 2
    best, pos, count = None, None, 0
    for i, (h, _, _) in enumerate(line[:-1]):
        count += len(h)
        if h and h[-1][-1] in SOFT and 2 <= count <= _n(line) - 2:
            d = abs(count - mid)
            if best is None or d < best:
                best, pos = d, i
    if pos is None:
        count, pos = 0, 0
        for i, (h, _, _) in enumerate(line):
            count += len(h)
            if count >= mid:
                pos = i
                break
    return _break(line[:pos + 1], max_words) + _break(line[pos + 1:], max_words)


def _merge(lines, min_words=3, max_merged=16):
    out = []
    for l in lines:
        if not l:
            continue
        if out and (_n(l) < min_words or _n(out[-1]) < min_words) and _n(out[-1]) + _n(l) <= max_merged:
            out[-1] = out[-1] + l
        else:
            out.append(l)
    return out


if __name__ == '__main__':
    items = parse(sys.argv[1])
    json.dump(items, open(sys.argv[2], 'w', encoding='utf-8'), ensure_ascii=False, indent=1)
    print(f"{len(items)} scenarios, {sum(len(i['lines']) for i in items)} lines, "
          f"{sum(len(l['errors']) for i in items for l in i['lines'])} marked recogniser errors")
