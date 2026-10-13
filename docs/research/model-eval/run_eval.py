#!/usr/bin/env python3
"""Persian director eval: the app's understanding calls on the workshop scenarios, scored.

Talks to a running llama-server (same llama.cpp revision as native/third_party) through
/completion with the app's own prompt layout and GBNF grammar. Stdlib only.

  python3 -I run_eval.py --server http://127.0.0.1:8088 --format qwen --model-name Qwen3.5-2B-Q4_K_M \
      --scenarios scenarios.json --gold gold_shows.json --only fa- [--limit 4] [--seeds 1] --out results/x.json

Metrics (per scenario and overall):
  json        calls whose reply parsed as JSON and stopped on its own (not at the token limit) / calls
  domain      domain right (planner-equivalent domains count)
  cta         action right; keyword right when the creator asked for one
  hook        the hook line holds one of the gold hook phrases
  show        gold cue lines whose show is acceptable (EQUIVALENT_SHOWS + gold alternatives)
  spell.fix   marked recogniser errors whose clean form appears in the line's `fixed`
  spell.cer   char error rate of `fixed` vs truth, against the heard line's own CER (lower is better;
              cer_heard is the do-nothing floor; a model that rewrites lines scores worse than the floor)
  fa_titles   on-screen titles (piece, hook, lines) written in Persian script (Latin allowed only for
              names that occur in the script or brief): the owner's "English text on a Persian video"
  speed       prompt and generated tokens, prompt tok/s, generation tok/s, wall seconds
"""
import argparse
import json
import os
import re
import sys
import time
import urllib.request

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import director as D  # noqa: E402

NUM = {'counter', 'meter', 'chart', 'stats', 'progress', 'countdown'}
EQUIV_SHOWS = [{'headline', 'stamp'}, NUM, {'object', 'objects'}]
EQUIV_DOMAINS = [{'tech', 'software', 'ai'}, {'crypto', 'finance', 'business'}, {'medical', 'health', 'fitness'},
                 {'beauty', 'fashion', 'lifestyle'}]
DIACRITICS = re.compile('[ً-ْٰٔ]')
FA_DIGITS = str.maketrans('۰۱۲۳۴۵۶۷۸۹٠١٢٣٤٥٦٧٨٩', '01234567890123456789')


def norm(s, keep_zwnj=True):
    s = s.replace('ي', 'ی').replace('ك', 'ک').replace('ۀ', 'ه').replace('ة', 'ه').translate(FA_DIGITS)
    s = DIACRITICS.sub('', s)
    s = re.sub(r'[.,!?؟،؛:;«»"()\[\]]', ' ', s)
    if not keep_zwnj:
        s = s.replace('‌', '')
    return ' '.join(s.lower().split())


def lev(a, b):
    prev = list(range(len(b) + 1))
    for i, ca in enumerate(a, 1):
        cur = [i]
        for j, cb in enumerate(b, 1):
            cur.append(min(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + (ca != cb)))
        prev = cur
    return prev[-1]


def cer(hyp, ref):
    r = norm(ref)
    return lev(norm(hyp), r) / max(1, len(r))


def alike(a, b, groups):
    return a == b or any(a in g and b in g for g in groups)


def show_ok(got, accepted):
    acc = set()
    for a in accepted:
        acc |= NUM if a == 'NUM' else {a}
    return got in acc or any(alike(got, a, EQUIV_SHOWS) for a in acc)


def persian_ok(text, allowed_latin):
    letters = [c for c in text if c.isalpha()]
    if not letters:
        return True
    latin_words = re.findall(r'[A-Za-z][A-Za-z0-9.\-]*', text)
    if all(w.lower() in allowed_latin for w in latin_words):
        return True
    fa = sum(1 for c in letters if '؀' <= c <= 'ۿ' or 'ﭐ' <= c <= '﻿')
    return fa / len(letters) >= 0.6


class Server:
    def __init__(self, url, fmt, top_p=0.95, min_p=0.05):
        self.url, self.fmt, self.top_p, self.min_p = url.rstrip('/'), fmt, top_p, min_p
        self.stats = {'calls': 0, 'valid': 0, 'prompt_n': 0, 'prompt_ms': 0.0, 'gen_n': 0, 'gen_ms': 0.0, 'limit': 0}

    def ask(self, system, user, schema, max_tokens, temperature, seed):
        body = {'prompt': D.chat(self.fmt, system, user), 'grammar': D.grammar(schema), 'n_predict': max_tokens,
                'temperature': temperature, 'top_p': self.top_p, 'min_p': self.min_p, 'top_k': 0, 'seed': seed,
                'cache_prompt': False, 'stream': False}
        req = urllib.request.Request(self.url + '/completion', json.dumps(body).encode(), {'Content-Type': 'application/json'})
        r = json.load(urllib.request.urlopen(req, timeout=3600))
        t = r.get('timings', {})
        st = self.stats
        st['calls'] += 1
        st['prompt_n'] += t.get('prompt_n', 0)
        st['prompt_ms'] += t.get('prompt_ms', 0.0)
        st['gen_n'] += t.get('predicted_n', 0)
        st['gen_ms'] += t.get('predicted_ms', 0.0)
        limit = r.get('stop_type') == 'limit' or r.get('stopped_limit', False)
        st['limit'] += int(limit)
        try:
            obj = json.loads(r['content'])
            ok = isinstance(obj, dict) and not limit
        except ValueError:
            obj, ok = None, False
        st['valid'] += int(ok)
        return obj if isinstance(obj, dict) else {}


def find_line(sc, cue):
    c = norm(cue)
    for k, l in enumerate(sc['lines']):
        if c in norm(l['truth']):
            return k
    for k in range(len(sc['lines']) - 1):
        if c in norm(sc['lines'][k]['truth'] + ' ' + sc['lines'][k + 1]['truth']):
            return k
    return None


def understand(srv, sc, seed, temperature):
    g = srv.ask(D.SYSTEM, D.user_message(sc), D.GLOBAL_SCHEMA, 900, temperature, seed)
    reads = []
    lines = sc['lines']
    for start in range(0, len(lines), 3):
        group = list(range(start, min(start + 3, len(lines))))
        got = srv.ask(D.LINES_SYSTEM, D.lines_message(sc, group, g), D.lines_schema(len(group)), 320 * len(group),
                      temperature, seed + group[0]).get('reads') or []
        for i, _ in enumerate(group):
            reads.append(got[i] if i < len(got) and isinstance(got[i], dict) else {})
    return g, reads


def score(sc, gold, g, reads):
    out = {}
    out['domain'] = alike(sc['domain'], g.get('domain', ''), EQUIV_DOMAINS)
    cta = g.get('cta') or {}
    want = sc['cta'] or {'action': 'none', 'keyword': ''}
    out['cta_action'] = cta.get('action', 'none') == want['action']
    out['cta_keyword'] = None if not want['keyword'] else norm(cta.get('keyword', '')) == norm(want['keyword'])
    hook = (g.get('hook') or {}).get('line')
    out['hook'] = None
    if gold.get('hook') and isinstance(hook, int) and 0 <= hook < len(sc['lines']):
        t = norm(sc['lines'][hook]['truth'])
        out['hook'] = any(norm(h) in t for h in gold['hook'])
    elif gold.get('hook'):
        out['hook'] = False
    shows = []
    for cue, accepted in gold.get('shows', []):
        k = find_line(sc, cue)
        if k is None:
            continue
        got = (reads[k] or {}).get('show', '')
        shows.append({'cue': cue, 'line': k, 'got': got, 'ok': show_ok(got, accepted)})
    out['shows'] = shows
    fixes = []
    cer_fixed, cer_heard = [], []
    for k, l in enumerate(sc['lines']):
        fixed = (reads[k] or {}).get('fixed', '')
        cer_fixed.append(cer(fixed, l['truth']))
        cer_heard.append(cer(l['heard'], l['truth']))
        for clean, heard in l['errors']:
            if re.search(r'[0-9۰-۹]', clean + heard):
                continue  # digits vs words: the app treats a number in either form alike
            fixes.append({'clean': clean, 'heard': heard, 'fixed': fixed,
                          'ok': norm(clean) in norm(fixed), 'kept_wrong': norm(heard) in norm(fixed)})
    out['fixes'] = fixes
    out['cer_fixed'] = sum(cer_fixed) / max(1, len(cer_fixed))
    out['cer_heard'] = sum(cer_heard) / max(1, len(cer_heard))
    allowed = {w.lower() for w in re.findall(r'[A-Za-z][A-Za-z0-9.\-]*', sc['prompt'] + ' ' + ' '.join(l['truth'] for l in sc['lines']))}
    titles = [g.get('title', ''), (g.get('hook') or {}).get('title', '')] + [(r or {}).get('title', '') for r in reads]
    titles = [t for t in titles if t.strip()]
    out['fa_titles'] = (sum(persian_ok(t, allowed) for t in titles), len(titles)) if sc['language'] == 'fa' else None
    out['titles'] = titles
    return out


def mean(xs):
    xs = [x for x in xs if x is not None]
    return sum(xs) / len(xs) if xs else None


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--server', default='http://127.0.0.1:8088')
    ap.add_argument('--format', choices=['qwen', 'chatml', 'gemma4'], default='qwen')
    ap.add_argument('--model-name', required=True)
    ap.add_argument('--scenarios', default='scenarios.json')
    ap.add_argument('--gold', default='gold_shows.json')
    ap.add_argument('--only', default='fa-')
    ap.add_argument('--ids', default='')
    ap.add_argument('--limit', type=int, default=0)
    ap.add_argument('--seeds', default='1')
    ap.add_argument('--temperature', type=float, default=0.3)
    ap.add_argument('--out', required=True)
    a = ap.parse_args()
    here = os.path.dirname(os.path.abspath(__file__))
    scs = json.load(open(os.path.join(here, a.scenarios), encoding='utf-8'))
    gold = json.load(open(os.path.join(here, a.gold), encoding='utf-8'))
    scs = [s for s in scs if s['id'].startswith(a.only)]
    if a.ids:
        scs = [s for s in scs if s['id'] in a.ids.split(',')]
    if a.limit:
        scs = scs[:a.limit]
    srv = Server(a.server, a.format)
    runs = []
    t_all = time.time()
    for seed in [int(x) for x in a.seeds.split(',')]:
        for sc in scs:
            t0 = time.time()
            calls0 = dict(srv.stats)
            g, reads = understand(srv, sc, seed, a.temperature)
            s = score(sc, gold.get(sc['id'], {}), g, reads)
            s.update(id=sc['id'], seed=seed, seconds=time.time() - t0,
                     calls=srv.stats['calls'] - calls0['calls'], valid=srv.stats['valid'] - calls0['valid'],
                     prompt_n=srv.stats['prompt_n'] - calls0['prompt_n'], gen_n=srv.stats['gen_n'] - calls0['gen_n'],
                     understanding=g, reads=reads)
            runs.append(s)
            sh = s['shows']
            fx = s['fixes']
            print(f"{sc['id']:<26} json {s['valid']}/{s['calls']} domain {int(s['domain'])} cta {int(s['cta_action'])}/{s['cta_keyword']} "
                  f"hook {s['hook']} show {sum(x['ok'] for x in sh)}/{len(sh)} fix {sum(x['ok'] for x in fx)}/{len(fx)} "
                  f"cer {s['cer_fixed']:.3f} (heard {s['cer_heard']:.3f}) fa_titles {s['fa_titles']} "
                  f"tok {s['prompt_n']}+{s['gen_n']} {s['seconds']:.0f}s", flush=True)
    st = srv.stats
    sh = [x for r in runs for x in r['shows']]
    fx = [x for r in runs for x in r['fixes']]
    fa = [r['fa_titles'] for r in runs if r['fa_titles']]
    summary = {
        'model': a.model_name, 'scenarios': len(scs), 'runs': len(runs),
        'json': f"{st['valid']}/{st['calls']}", 'json_rate': st['valid'] / max(1, st['calls']), 'hit_token_limit': st['limit'],
        'domain': mean([float(r['domain']) for r in runs]), 'cta_action': mean([float(r['cta_action']) for r in runs]),
        'cta_keyword': mean([None if r['cta_keyword'] is None else float(r['cta_keyword']) for r in runs]),
        'hook': mean([None if r['hook'] is None else float(r['hook']) for r in runs]),
        'show': sum(x['ok'] for x in sh) / max(1, len(sh)), 'show_n': len(sh),
        'spell_fix': sum(x['ok'] for x in fx) / max(1, len(fx)), 'spell_kept_wrong': sum(x['kept_wrong'] for x in fx) / max(1, len(fx)),
        'spell_n': len(fx), 'cer_fixed': mean([r['cer_fixed'] for r in runs]), 'cer_heard': mean([r['cer_heard'] for r in runs]),
        'fa_titles': sum(x[0] for x in fa) / max(1, sum(x[1] for x in fa)),
        'prompt_tokens': st['prompt_n'], 'gen_tokens': st['gen_n'],
        'prompt_tps': st['prompt_n'] / max(1e-9, st['prompt_ms'] / 1000), 'gen_tps': st['gen_n'] / max(1e-9, st['gen_ms'] / 1000),
        'wall_s': time.time() - t_all,
    }
    os.makedirs(os.path.dirname(os.path.abspath(a.out)), exist_ok=True)
    json.dump({'summary': summary, 'runs': runs}, open(a.out, 'w', encoding='utf-8'), ensure_ascii=False, indent=1)
    print('RESULT ' + ' '.join(f'{k}={v:.3f}' if isinstance(v, float) else f'{k}={v}' for k, v in summary.items()))


if __name__ == '__main__':
    main()
