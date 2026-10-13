"""The app's director calls, reproduced outside the JVM (stdlib only).

- Prompts are read at run time from Understander.kt and Understanding.kt (read-only), so the
  harness asks exactly what the app asks.
- The JSON-schema -> GBNF compiler is a line-for-line port of engine/llm/JsonSchemaGrammar.kt, so
  "JSON validity under our grammar" means the app's grammar, not llama.cpp's json_schema converter.
- Chat layouts follow engine/llm/ChatTemplates.kt (ChatML / Gemma 4) and DirectorProfile
  (Qwen: assistant prefix "<think>\\n\\n</think>\\n\\n" to keep thinking off).
"""
import json
import re
import textwrap

REPO = '/home/user/Trimio'
AUTOPILOT = REPO + '/engine/autopilot/src/commonMain/kotlin/io/trimio/engine/autopilot'


def _kotlin_list(src, name):
    m = re.search(r'val ' + name + r' = listOf\((.*?)\)\n', src, re.S)
    return re.findall(r'"([^"]+)"', m.group(1))


def _triple(src, name):
    m = re.search(r'val ' + name + r' = """(.*?)"""\.trimIndent\(\)', src, re.S)
    return textwrap.dedent(m.group(1)).strip('\n')


def load():
    u = open(AUTOPILOT + '/Understanding.kt', encoding='utf-8').read()
    s = open(AUTOPILOT + '/Understander.kt', encoding='utf-8').read()
    lists = {k: _kotlin_list(u, k) for k in ('DOMAINS', 'MOODS', 'SHOWS', 'ROLES', 'KINDS')}
    system = _triple(s, 'SYSTEM')
    line_system = _triple(s, 'LINE_SYSTEM')
    first = line_system.splitlines()[0]
    lines_system = line_system.replace(first, (
        "You are a motion designer deciding how to picture the numbered LINES of a talking video. Answer with one read per LINE, in the same order; "
        "each read is about its own LINE only, never about the others. In each read, \"the LINE\" means that read's line."), 1)
    return lists, system, lines_system


LISTS, SYSTEM, LINES_SYSTEM = load()


def _str(n):
    return {'type': 'string', 'maxLength': n}


def _enum(v):
    return {'type': 'string', 'enum': v}


def _obj(*props):
    return {'type': 'object', 'properties': dict(props)}


def _arr(item, mx):
    return {'type': 'array', 'items': item, 'maxItems': mx}


INT, NUM = {'type': 'integer'}, {'type': 'number'}

GLOBAL_SCHEMA = _obj(
    ('title', _str(48)), ('topic', _str(40)), ('domain', _enum(LISTS['DOMAINS'])), ('mood', _enum(LISTS['MOODS'])),
    ('brief', _obj(('look', _enum(['noir', 'paper', 'lumen', 'auto'])), ('energy', NUM),
                   ('pace', _enum(['fast', 'normal', 'calm', 'auto'])), ('captions', _enum(['word', 'phrase', 'none', 'auto'])),
                   ('music', _enum(['uplifting', 'energetic', 'chill', 'cinematic', 'corporate', 'tense', 'none', 'auto'])),
                   ('payoffFirst', {'type': 'boolean'}))),
    ('entities', _arr(_obj(('at', INT), ('until', INT), ('name', _str(32)), ('kind', _enum(LISTS['KINDS'])), ('visual', _str(32))), 10)),
    ('hook', _obj(('line', INT), ('title', _str(40)))),
    ('cta', _obj(('action', _enum(['comment', 'follow', 'save', 'share', 'link', 'dm', 'none'])), ('keyword', _str(20)))),
)
LINE_SCHEMA = _obj(('fixed', _str(160)), ('gist', _str(48)), ('role', _enum(LISTS['ROLES'])), ('show', _enum(LISTS['SHOWS'])),
                   ('title', _str(32)), ('visual', _str(64)), ('items', _arr(_str(24), 9)), ('energy', NUM))


def lines_schema(n):
    return {'type': 'object', 'properties': {'reads': {'type': 'array', 'items': LINE_SCHEMA, 'minItems': n, 'maxItems': n}}}


# ---- port of JsonSchemaGrammar.kt -------------------------------------------------------------
PRIMITIVES = (
    'ws ::= [ \\t\\n]{0,4}\n'
    'char ::= [^"\\\\\\x7F\\x00-\\x1F] | "\\\\" ["\\\\/nt]\n'
    'string ::= "\\"" char{0,400} "\\""\n'
    'integer ::= "-"? ([0-9] | [1-9] [0-9]{1,15})\n'
    'number ::= "-"? ([0-9] | [1-9] [0-9]{1,15}) ("." [0-9]{1,6})?\n'
    'boolean ::= "true" | "false"\n'
)


def _lit(text):
    return '"' + text.replace('\\', '\\\\').replace('"', '\\"') + '"'


def _q(s):
    return '"' + s.replace('\\', '\\\\').replace('"', '\\"') + '"'


def _rule(raw):
    return re.sub(r'[^A-Za-z0-9-]', '-', raw)


def _rep(mn, mx):
    if mx is None:
        return '*' if mn == 0 else '{%d,}' % mn
    return '{%d,%d}' % (mn, mx)


def _visit(schema, name, rules):
    if 'enum' in schema:
        return '(' + ' | '.join(_lit(_q(v)) for v in schema['enum']) + ')'
    t = schema.get('type')
    if t == 'object':
        props = schema.get('properties', {})
        if not props:
            body = '"{" ws "}"'
        else:
            parts = []
            for i, (k, sub) in enumerate(props.items()):
                v = _visit(sub, _rule(f'{name}-{k}'), rules)
                parts.append(('' if i == 0 else '"," ws ') + _lit(_q(k)) + ' ws ":" ws ' + v)
            body = '"{" ws ' + ' '.join(parts) + ' ws "}"'
        rules[name] = body
        return name
    if t == 'array':
        item = _visit(schema.get('items', {}), _rule(f'{name}-item'), rules)
        mn, mx = schema.get('minItems', 0), schema.get('maxItems')
        more = f'("," ws {item})'
        if mx == 0:
            body = '"[" ws "]"'
        elif mn == 0:
            body = f'"[" ws ({item} {more}{_rep(0, None if mx is None else mx - 1)})? ws "]"'
        else:
            body = f'"[" ws {item} {more}{_rep(mn - 1, None if mx is None else mx - 1)} ws "]"'
        rules[name] = body
        return name
    if t == 'string':
        n = schema.get('maxLength')
        return '"\\"" char{0,%d} "\\""' % n if n is not None else 'string'
    if t in ('integer', 'number', 'boolean'):
        return t
    raise ValueError(f'unsupported {t} at {name}')


def grammar(schema):
    rules = {}
    root = _visit(schema, 'root', rules)
    if root != 'root':
        rules['root'] = root
    out = 'root ::= ' + rules.pop('root') + '\n'
    for k, v in rules.items():
        out += f'{k} ::= {v}\n'
    return out + PRIMITIVES


# ---- messages (Understander.userMessage / linesMessage) ----------------------------------------
def user_message(sc):
    w = sc['words']
    out = 'Brief:\n' + (sc['prompt'].strip() or '(none: decide everything)') + '\n\nWords:\n'
    out += ''.join(f'{i}:{x} ' for i, x in enumerate(w))
    out += f"\n\nLines ({len(sc['lines'])}):\n"
    out += ''.join(f"{k}: {l['heard']}\n" for k, l in enumerate(sc['lines']))
    return out


def lines_message(sc, group, g):
    lines = sc['lines']
    out = 'Brief: ' + (sc['prompt'].strip()[:700] or '(none)') + '\n'
    out += f"Video: {g.get('title', '')} ({g.get('topic', '')}, {g.get('domain', '')})\n"
    if group[0] > 0:
        out += 'Previous line: ' + lines[group[0] - 1]['heard'] + '\n'
    out += f'\nLINES ({len(group)}):\n'
    ents = g.get('entities') or []
    for i, k in enumerate(group):
        out += f"{i + 1}: {lines[k]['heard']}\n"
        names = [e['name'] for e in ents if isinstance(e.get('at'), int) and lines[k]['first'] <= e['at'] <= lines[k]['last']]
        if names:
            out += '   names in this line: ' + ', '.join(names) + '\n'
        cta = g.get('cta') or {}
        if k == len(lines) - 1 and cta.get('action', 'none') != 'none':
            out += '   this is the last line; the call to action keyword is: ' + cta.get('keyword', '') + '\n'
    return out


QWEN_NO_THINK = '<think>\n\n</think>\n\n'


def chat(fmt, system, user):
    if fmt == 'gemma4':
        return f'<|turn>system\n{system.strip()}<turn|>\n<|turn>user\n{user.strip()}<turn|>\n<|turn>model\n'
    prefix = QWEN_NO_THINK if fmt == 'qwen' else ''
    return f'<|im_start|>system\n{system}<|im_end|>\n<|im_start|>user\n{user}<|im_end|>\n<|im_start|>assistant\n{prefix}'


if __name__ == '__main__':
    print(grammar(lines_schema(3))[:600])
    print(LISTS['SHOWS'])
