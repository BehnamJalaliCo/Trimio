#!/usr/bin/env python3
"""Cost of the app's GBNF grammar on generation speed: the same prompt and seed, n tokens, with the
app grammar, with a light grammar (unbounded strings, no {0,N} repetitions), and without grammar.

  python3 -I grammar_cost.py --server URL --format qwen --n 48 [--top-k 0|40]
"""
import argparse
import json
import os
import re
import sys
import urllib.request

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import director as D  # noqa: E402


def light(g):
    # Same structure, but strings as plain `string` and no bounded repetitions on char.
    g = re.sub(r'"\\"" char\{0,\d+\} "\\""', 'string', g)
    g = g.replace('string ::= "\\"" char{0,400} "\\""', 'string ::= "\\"" char* "\\""')
    return g


def run(url, prompt, grammar, n, top_k):
    body = {'prompt': prompt, 'n_predict': n, 'temperature': 0.3, 'top_p': 0.95, 'min_p': 0.05, 'top_k': top_k,
            'seed': 1, 'cache_prompt': True, 'stream': False}
    if grammar:
        body['grammar'] = grammar
    req = urllib.request.Request(url + '/completion', json.dumps(body).encode(), {'Content-Type': 'application/json'})
    r = json.load(urllib.request.urlopen(req, timeout=3600))
    t = r['timings']
    return t['predicted_n'], t['predicted_per_second'], r['content'][:80]


if __name__ == '__main__':
    ap = argparse.ArgumentParser()
    ap.add_argument('--server', default='http://127.0.0.1:8088')
    ap.add_argument('--format', default='qwen')
    ap.add_argument('--n', type=int, default=48)
    ap.add_argument('--top-k', type=int, default=0)
    a = ap.parse_args()
    sc = [s for s in json.load(open(os.path.join(os.path.dirname(os.path.abspath(__file__)), 'scenarios.json'), encoding='utf-8'))
          if s['id'] == 'fa-crypto-bitcoin'][0]
    g = {'title': 'بیت‌کوین', 'topic': 'crypto exchange campaign', 'domain': 'crypto', 'entities': [], 'cta': {'action': 'comment', 'keyword': 'بیت'}}
    prompt = D.chat(a.format, D.LINES_SYSTEM, D.lines_message(sc, [0, 1, 2], g))
    app = D.grammar(D.lines_schema(3))
    for name, gr in (('none', None), ('app', app)):
        n, tps, txt = run(a.server.rstrip('/'), prompt, gr, a.n, a.top_k)
        print(f'{name:<6} top_k={a.top_k} tokens={n} gen_tok_s={tps:.2f}  {txt!r}', flush=True)
