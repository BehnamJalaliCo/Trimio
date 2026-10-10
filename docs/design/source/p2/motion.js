// Deterministic motion engine for review renders: render(t) positions every animated element.
// data-in="kind delayMs durMs" ; kinds: rise | mask | draw | fade | pop | slide
const ease = x => x >= 1 ? 1 : 1 - Math.pow(2, -10 * x);           // expo-out (Signature entrance)
const spring = x => { if (x >= 1) return 1; return 1 - Math.exp(-6 * x) * Math.cos(10 * x); }; // soft overshoot
function prog(t, d, u) { return Math.max(0, Math.min(1, (t - d) / u)); }
window.render = function (t) {
  document.querySelectorAll('[data-in]').forEach(el => {
    const [k, d, u] = el.dataset.in.split(' '); const p = prog(t, +d, +u); const e = ease(p);
    if (k === 'rise') { el.style.transform = `translateY(${(1 - e) * 26}px)`; el.style.opacity = e; }
    if (k === 'mask') { el.style.clipPath = `inset(0 0 ${(1 - e) * 100}% 0)`; el.style.transform = `translateY(${(1 - e) * 40}px)`; }
    if (k === 'draw') { el.style.transformOrigin = 'right center'; el.style.transform = `scaleX(${e})`; }
    if (k === 'fade') { el.style.opacity = e; }
    if (k === 'pop') { const s = spring(p); el.style.transform = `scale(${0.9 + 0.1 * s})`; el.style.opacity = Math.min(1, p * 3); }
    if (k === 'slide') { el.style.transform = `translateY(${(1 - e) * 120}px)`; el.style.opacity = Math.min(1, p * 2); }
  });
  if (window.custom) window.custom(t);
};
