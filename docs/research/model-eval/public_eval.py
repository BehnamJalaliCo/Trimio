#!/usr/bin/env python3
"""Public Persian checks, grammar-constrained (stdlib only).

  fetch:  python3 -I public_eval.py fetch --n 120            (writes data/*.jsonl, a local cache: never commit)
  run:    python3 -I public_eval.py run --server URL --format qwen --model-name NAME --out results/x-public.json

Sets (licences checked on the HF dataset cards, 2026-10-12):
  global_mmlu_fa  CohereLabs/Global-MMLU, config fa, test       Apache-2.0      Persian knowledge/understanding (4-way MCQ)
  parsinlu_nli    persiannlp/parsinlu_entailment, test          CC-BY-NC-SA-4.0 Persian NLI (e/n/c). Internal eval only.
Not wired by default (licence or gating; see README): raia-center/khayyam-challenge (gated, CC-BY-ND-4.0),
MCINext/persian-ifeval (CC-BY-NC-ND-4.0, needs an IFEval checker), ParsiAI/FarsInstruct (Apache-2.0, training data).

Each answer is forced by a GBNF grammar ({"answer":"A"}), so the score measures Persian
understanding and grammar-constrained decoding together; the subject mix is stratified.
"""
import argparse
import json
import os
import random
import sys
import time
import urllib.parse
import urllib.request

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from run_eval import Server  # noqa: E402

HERE = os.path.dirname(os.path.abspath(__file__))
DATA = os.path.join(HERE, 'data')
ROWS = 'https://datasets-server.huggingface.co/rows?'


def rows(dataset, config, split, offset, length):
    q = urllib.parse.urlencode({'dataset': dataset, 'config': config, 'split': split, 'offset': offset, 'length': length})
    return [r['row'] for r in json.load(urllib.request.urlopen(ROWS + q, timeout=120))['rows']]


def fetch(n):
    os.makedirs(DATA, exist_ok=True)
    rnd = random.Random(7)
    # Global-MMLU fa test has 14042 rows; sample evenly by offset so all subject groups appear.
    total, picked = 14042, []
    for off in sorted(rnd.sample(range(0, total - 1), n)):
        picked += rows('CohereLabs/Global-MMLU', 'fa', 'test', off, 1)
    with open(os.path.join(DATA, 'global_mmlu_fa.jsonl'), 'w', encoding='utf-8') as f:
        for r in picked:
            f.write(json.dumps({k: r[k] for k in ('sample_id', 'subject_category', 'question', 'option_a', 'option_b',
                                                  'option_c', 'option_d', 'answer')}, ensure_ascii=False) + '\n')
    nli = []
    for off in range(0, 1675, 100):
        nli += rows('persiannlp/parsinlu_entailment', 'parsinlu-repo', 'test', off, 100)
    rnd.shuffle(nli)
    with open(os.path.join(DATA, 'parsinlu_nli.jsonl'), 'w', encoding='utf-8') as f:
        for r in nli[:n]:
            f.write(json.dumps(r, ensure_ascii=False) + '\n')
    print(f'fetched {len(picked)} Global-MMLU fa and {min(n, len(nli))} ParsiNLU NLI items into {DATA}')


MCQ_SYSTEM = 'You answer multiple-choice questions written in Persian. Reply with JSON only: {"answer": "<letter>"}.'
NLI_SYSTEM = ('You read two Persian sentences. Reply with JSON only: {"label": "e"} if the second follows from the first, '
              '{"label": "c"} if it contradicts it, {"label": "n"} otherwise.')


def run(a):
    srv = Server(a.server, a.format)
    out = {'model': a.model_name}
    mmlu = [json.loads(x) for x in open(os.path.join(DATA, 'global_mmlu_fa.jsonl'), encoding='utf-8')][:a.n]
    t0, ok = time.time(), 0
    schema = {'type': 'object', 'properties': {'answer': {'type': 'string', 'enum': ['A', 'B', 'C', 'D']}}}
    for r in mmlu:
        user = f"{r['question']}\nA) {r['option_a']}\nB) {r['option_b']}\nC) {r['option_c']}\nD) {r['option_d']}"
        got = srv.ask(MCQ_SYSTEM, user, schema, 12, 0.0, 1).get('answer')
        ok += int(got == r['answer'].strip().upper())
    out['global_mmlu_fa'] = {'acc': ok / max(1, len(mmlu)), 'n': len(mmlu), 'seconds': time.time() - t0}
    nli = [json.loads(x) for x in open(os.path.join(DATA, 'parsinlu_nli.jsonl'), encoding='utf-8')][:a.n]
    t0, ok = time.time(), 0
    schema = {'type': 'object', 'properties': {'label': {'type': 'string', 'enum': ['e', 'n', 'c']}}}
    for r in nli:
        got = srv.ask(NLI_SYSTEM, f"1: {r['sent1']}\n2: {r['sent2']}", schema, 10, 0.0, 1).get('label')
        ok += int(got == r['label'])
    out['parsinlu_nli'] = {'acc': ok / max(1, len(nli)), 'n': len(nli), 'seconds': time.time() - t0}
    st = srv.stats
    out['json'] = f"{st['valid']}/{st['calls']}"
    out['prompt_tps'] = st['prompt_n'] / max(1e-9, st['prompt_ms'] / 1000)
    json.dump(out, open(a.out, 'w', encoding='utf-8'), ensure_ascii=False, indent=1)
    print('RESULT', json.dumps(out, ensure_ascii=False))


if __name__ == '__main__':
    ap = argparse.ArgumentParser()
    ap.add_argument('cmd', choices=['fetch', 'run'])
    ap.add_argument('--n', type=int, default=120)
    ap.add_argument('--server', default='http://127.0.0.1:8088')
    ap.add_argument('--format', choices=['qwen', 'chatml', 'gemma4'], default='qwen')
    ap.add_argument('--model-name', default='model')
    ap.add_argument('--out', default=os.path.join(HERE, 'results', 'public.json'))
    a = ap.parse_args()
    fetch(a.n) if a.cmd == 'fetch' else run(a)
