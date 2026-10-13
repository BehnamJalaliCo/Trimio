// Renders every react-peeps piece to plain SVG markup (ink #000, paper #fff). Usage: node peeps.js <peeps lib dir> <out.json>
const Module = require('module');
const path = require('path');
const fs = require('fs');
const lib = path.resolve(process.argv[2]);
const attr = (k) => ({ fillRule: 'fill-rule', clipRule: 'clip-rule', strokeWidth: 'stroke-width', strokeLinecap: 'stroke-linecap',
  strokeLinejoin: 'stroke-linejoin', strokeMiterlimit: 'stroke-miterlimit', fillOpacity: 'fill-opacity', className: 'class' }[k] || k);
const React = {
  createElement(tag, props, ...kids) {
    if (typeof tag === 'function') return tag(Object.assign({}, props, { children: kids }));
    const a = Object.entries(props || {}).filter(([k, v]) => v !== undefined && v !== null && k !== 'children' && k !== 'style')
      .map(([k, v]) => ` ${attr(k)}="${String(v)}"`).join('');
    const inner = kids.flat(Infinity).filter((k) => k !== false && k !== null && k !== undefined && k !== '').join('');
    return `<${tag}${a}>${inner}</${tag}>`;
  },
  useMemo: (f) => f(),
};
React.default = React;
const orig = Module._load;
Module._load = function (req, parent, isMain) { if (req === 'react') return React; return orig.apply(this, arguments); };
const out = {};
const groups = { hair: 'hair', face: 'face', facialHair: 'facialHair', accessories: 'accessories', 'pose/bust': 'bust', 'pose/standing': 'standing', 'pose/sitting': 'sitting' };
for (const [dir, key] of Object.entries(groups)) {
  for (const f of fs.readdirSync(path.join(lib, dir))) {
    if (!f.endsWith('.js') || f.startsWith('index') || f.startsWith('z_')) continue;
    const name = f.replace('.js', '');
    const m = require(path.join(lib, dir, f));
    const fn = m[name] || Object.values(m).find((v) => typeof v === 'function');
    if (!fn) { console.error('skip', dir, f); continue; }
    out[`${key}/${name}`] = fn({ strokeColor: '#000', backgroundColor: '#fff' });
  }
}
fs.writeFileSync(process.argv[3], JSON.stringify(out));
console.log(Object.keys(out).length, 'pieces', JSON.stringify(out).length, 'bytes');
