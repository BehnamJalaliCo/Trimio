package io.trimio.engine.motion

/**
 * SkSL/AGSL sources of the shader families ([ShaderFamily]). Portability rules (both dialects):
 * float literals only (1.0, not 1), constant loop bounds, `half4 main(float2)`, premultiplied
 * output. Stage fields keep their brightest light at the frame's edges and stay within a narrow
 * band of the canvas tone in the middle, where text sits.
 */
internal object FxSources {

    private const val COMMON = """
float hash(float2 p) {
    p = fract(p * float2(123.34, 456.21));
    p += dot(p, p + 45.32);
    return fract(p.x * p.y);
}
float2 hash2(float2 p) { float n = hash(p); return float2(n, hash(p + n + 17.17)); }
float noise(float2 p) {
    float2 i = floor(p);
    float2 f = fract(p);
    float2 u = f * f * (3.0 - 2.0 * f);
    return mix(mix(hash(i), hash(i + float2(1.0, 0.0)), u.x), mix(hash(i + float2(0.0, 1.0)), hash(i + float2(1.0, 1.0)), u.x), u.y);
}
float luma(float3 c) { return dot(c, float3(0.2126, 0.7152, 0.0722)); }
"""

    /** Uniforms every stage family shares: palette c0 canvas, c1 accent, c2 hot, c3 ink. */
    private const val STAGE_HEAD = """
uniform float2 uSize;
uniform float uTime;
uniform float uSeed;
uniform float uAmount;
uniform float3 c0;
uniform float3 c1;
uniform float3 c2;
uniform float3 c3;
$COMMON
"""

    private const val STAGE_MAIN = """
half4 main(float2 fc) { return half4(half3(field(fc)), 1.0); }
"""

    /** Volumetric glow: a light above the frame, rays through haze, a soft floor bounce. */
    const val glow = STAGE_HEAD + """
float3 field(float2 fc) {
    float2 uv = fc / uSize;
    float asp = uSize.x / uSize.y;
    float t = uTime;
    float2 src = float2(0.5 + 0.2 * sin(t * 0.11 + uSeed), -0.12);
    float2 d = float2((uv.x - src.x) * asp, uv.y - src.y);
    float r = length(d);
    float ang = atan(d.x, d.y);
    float rays = noise(float2(ang * 8.0 + uSeed * 3.0, t * 0.12)) * 0.6 + noise(float2(ang * 21.0 - t * 0.08, uSeed)) * 0.4;
    rays = smoothstep(0.3, 0.95, rays);
    float fall = exp(-r * 1.5);
    float core = exp(-r * r * 10.0);
    float haze = noise(uv * float2(3.0, 5.0) + float2(0.0, t * 0.05));
    float3 col = c0;
    col = mix(col, c1, clamp((rays * fall * 0.55 + core * 0.6) * uAmount, 0.0, 0.7));
    col = mix(col, c2, clamp(haze * fall * 0.18 * uAmount, 0.0, 0.3));
    col = mix(col, c1, exp(-pow((uv.y - 1.02) * 5.0, 2.0)) * 0.14 * uAmount);
    return col;
}
""" + STAGE_MAIN

    /** Light leaks: warm anamorphic blooms drifting in from the edges, film-like streaks. */
    const val leaks = STAGE_HEAD + """
float blob(float2 uv, float2 at, float2 stretch) { float2 q = (uv - at) * stretch; return exp(-dot(q, q)); }
float3 field(float2 fc) {
    float2 uv = fc / uSize;
    float t = uTime * 0.06 + uSeed;
    float2 a = float2(-0.05 + 0.22 * sin(t * 1.3), 0.18 + 0.08 * sin(t * 0.9));
    float2 b = float2(1.05 - 0.2 * sin(t * 1.1 + 2.0), 0.84 + 0.06 * cos(t * 0.7));
    float la = blob(uv, a, float2(2.0, 4.2));
    float lb = blob(uv, b, float2(2.2, 3.6));
    float streak = exp(-pow((uv.y - a.y) * 18.0, 2.0)) * smoothstep(0.9, 0.0, abs(uv.x - a.x));
    float3 col = mix(c0, c3 * 0.04 + c0 * 0.96, uv.y);
    col = mix(col, c2, clamp(la * 0.6 * uAmount, 0.0, 0.65));
    col = mix(col, c1, clamp(lb * 0.42 * uAmount, 0.0, 0.5));
    col = mix(col, mix(c2, float3(1.0, 0.95, 0.85), 0.5), clamp(streak * 0.3 * uAmount, 0.0, 0.35));
    return col;
}
""" + STAGE_MAIN

    /** Bokeh: out-of-focus discs with brighter rims drifting slowly upward over a soft gradient. */
    const val bokeh = STAGE_HEAD + """
float3 field(float2 fc) {
    float2 uv = fc / uSize;
    float asp = uSize.x / uSize.y;
    float3 col = mix(c0, mix(c0, c2, 0.18), uv.y);
    for (int i = 0; i < 18; i++) {
        float fi = float(i);
        float2 h = hash2(float2(fi * 3.1, uSeed));
        float size = 0.05 + 0.11 * hash(float2(fi * 1.7, uSeed + 3.0));
        float2 p = float2(h.x + 0.03 * sin(uTime * 0.2 + fi), fract(h.y - uTime * 0.01 * (0.6 + size * 5.0)));
        float edge = smoothstep(0.32, 0.12, abs(p.x - 0.5)) * smoothstep(0.3, 0.1, abs(p.y - 0.5));
        float2 d = float2((uv.x - p.x) * asp, uv.y - p.y);
        float r = length(d) / size;
        float disc = smoothstep(1.0, 0.88, r);
        float ring = smoothstep(0.72, 0.97, r) * disc;
        float3 tint = hash(float2(fi, 9.0)) < 0.55 ? c1 : c2;
        float k = (disc * 0.14 + ring * 0.12) * (0.45 + 0.55 * hash(float2(fi, 5.0))) * (1.0 - 0.6 * edge);
        col = mix(col, tint, clamp(k * uAmount, 0.0, 0.4));
    }
    return col;
}
""" + STAGE_MAIN

    /** Dust: motes catching a diagonal volumetric beam, twinkling as they turn. */
    const val dust = STAGE_HEAD + """
float3 field(float2 fc) {
    float2 uv = fc / uSize;
    float asp = uSize.x / uSize.y;
    float beam = exp(-pow((uv.x - 0.72 + uv.y * 0.45) * 2.6, 2.0)) * (1.0 - uv.y * 0.5);
    float3 col = mix(c0, c1, clamp(beam * 0.16 * uAmount, 0.0, 0.25));
    for (int l = 0; l < 2; l++) {
        float fl = float(l);
        float s = 16.0 + 14.0 * fl;
        float2 g = float2(uv.x * asp, uv.y) * s + float2(uTime * 0.05 * (1.0 + fl), -uTime * (0.12 + 0.08 * fl));
        float2 cell = floor(g);
        float2 p = hash2(cell + uSeed + fl * 7.0);
        float2 off = 0.2 + 0.6 * p + 0.08 * float2(sin(uTime * 0.6 + p.x * 6.28), cos(uTime * 0.5 + p.y * 6.28));
        float dd = length(fract(g) - off);
        float m = smoothstep(0.1 - 0.03 * fl, 0.0, dd) * step(0.45, hash(cell + 3.1 + fl));
        float tw = 0.55 + 0.45 * sin(uTime * 1.7 + p.x * 40.0);
        col = mix(col, c3, clamp(m * tw * (0.18 + beam * 0.7) * uAmount, 0.0, 0.8));
    }
    return col;
}
""" + STAGE_MAIN

    /** Liquid gradient: colour bands on a domain-warped flow with a glossy sheen. */
    const val liquid = STAGE_HEAD + """
float3 field(float2 fc) {
    float2 uv = fc / uSize;
    float asp = uSize.x / uSize.y;
    float2 p = float2(uv.x * asp, uv.y) * 1.7;
    float t = uTime * 0.12 + uSeed;
    p += 0.38 * float2(sin(p.y * 2.1 + t * 1.3), cos(p.x * 1.7 - t));
    p += 0.26 * float2(sin(p.y * 3.3 - t * 0.8), cos(p.x * 2.9 + t * 1.1));
    float v = 0.5 + 0.5 * sin(p.x * 2.2 + p.y * 1.4 + t);
    float w = 0.5 + 0.5 * sin(-p.x * 1.3 + p.y * 2.6 - t * 0.7 + 1.7);
    float3 col = c0;
    col = mix(col, c1, smoothstep(0.5, 1.0, v) * 0.3 * uAmount);
    col = mix(col, c2, smoothstep(0.55, 1.0, w) * 0.26 * uAmount);
    col = mix(col, mix(c1, float3(1.0), 0.6), smoothstep(0.95, 1.0, v) * 0.16 * uAmount);
    return col;
}
""" + STAGE_MAIN

    /** Uniforms of the distortions: the content they bend, its size, time, strength and a 0..1 progress. */
    private const val FILTER_HEAD = """
uniform shader content;
uniform float2 uSize;
uniform float uTime;
uniform float uAmount;
uniform float uProgress;
uniform float uSeed;
$COMMON
half4 split(float2 p, float2 dir) {
    half4 g = content.eval(p);
    half4 r = content.eval(p + dir);
    half4 b = content.eval(p - dir);
    return half4(r.r, g.g, b.b, max(g.a, max(r.a, b.a)));
}
"""

    /** Chromatic aberration: RGB split growing toward the frame's edges. */
    const val chromatic = FILTER_HEAD + """
half4 main(float2 fc) {
    float2 d = (fc - uSize * 0.5) / uSize.y;
    float k = uAmount * uSize.y * 0.006 * length(d) * 2.0;
    return split(fc, normalize(d + float2(0.0001, 0.0)) * k);
}
"""

    /** Lens: barrel distortion, edge fringing and fall-off, like a wide prime at the corners. */
    const val lens = FILTER_HEAD + """
half4 main(float2 fc) {
    float2 c = uSize * 0.5;
    float2 uv = (fc - c) / c.y;
    float r2 = dot(uv, uv);
    float2 q = c + uv * (1.0 - uAmount * 0.09 * r2) * c.y;
    half4 col = split(q, uv * uAmount * r2 * uSize.y * 0.004);
    float fall = 1.0 - uAmount * 0.22 * smoothstep(0.2, 1.4, r2);
    return half4(col.rgb * half(fall), col.a);
}
"""

    /** Glitch: horizontal slices jump sideways two frames at a time, with an RGB split. */
    const val glitch = FILTER_HEAD + """
half4 main(float2 fc) {
    float tick = floor(uTime * 12.0) + uSeed;
    float rows = 18.0 + 30.0 * hash(float2(tick, 1.0));
    float row = floor(fc.y / uSize.y * rows);
    float h = hash(float2(row, tick));
    float on = step(1.0 - 0.4 * uAmount, h);
    float shift = (hash(float2(row * 1.3, tick + 2.0)) - 0.5) * uSize.x * 0.14 * uAmount * on;
    float px = uSize.x * (0.004 * uAmount + 0.01 * on * uAmount);
    return split(fc + float2(shift, 0.0), float2(px, 0.0));
}
"""

    /** Ripple: a ring of displacement expanding from the centre as progress runs 0 → 1. */
    const val ripple = FILTER_HEAD + """
half4 main(float2 fc) {
    float2 c = uSize * 0.5;
    float2 d = fc - c;
    float dist = length(d);
    float ring = uProgress * length(uSize) * 0.62;
    float width = uSize.y * 0.12;
    float x = (dist - ring) / width;
    float wave = sin(x * 7.0) * exp(-x * x) * uAmount * uSize.y * 0.03;
    float2 dir = d / max(dist, 1.0);
    half4 col = content.eval(fc - dir * wave);
    float crest = clamp(wave / (uSize.y * 0.03 + 0.001), 0.0, 1.0) * 0.1;
    return half4(min(col.rgb + half(crest) * col.a, col.a), col.a);
}
"""

    /**
     * Halation: highlights bleed a warm red glow into their surroundings, as on film. The glow is
     * gathered from [bloom], a quarter-resolution copy of the content (wide taps cost little there).
     */
    const val halation = FILTER_HEAD + """
uniform shader bloom;
uniform float uBloomScale;
half4 main(float2 fc) {
    half4 base = content.eval(fc);
    float3 glow = float3(0.0);
    for (int i = 0; i < 8; i++) {
        float fi = float(i);
        float a = fi * 2.39996;
        float r = uSize.y * (0.004 + 0.016 * sqrt((fi + 0.5) / 8.0));
        float3 s = float3(bloom.eval((fc + float2(cos(a), sin(a)) * r) * uBloomScale).rgb);
        glow += s * smoothstep(0.55, 0.95, luma(s));
    }
    glow = glow / 8.0;
    float3 col = float3(base.rgb) + glow * float3(1.0, 0.38, 0.16) * uAmount;
    float a = max(float(base.a), clamp(luma(glow) * uAmount, 0.0, 1.0));
    return half4(half3(min(col, float3(a))), half(a));
}
"""

    /**
     * Frosted glass over a stage: the stage's already-rendered image ([stage], [uStage] px)
     * seen through a rounded panel — refracted at the rim like thick glass, averaged over a
     * disc (frost), lifted toward white. Transparent outside the panel. [uSize] is this render's size.
     */
    const val glass = """
uniform shader stage;
uniform float2 uStage;
uniform float2 uSize;
uniform float4 uPanel;
uniform float uRadius;
uniform float uFrost;
float box(float2 p, float2 b, float r) {
    float2 q = abs(p) - b + r;
    return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - r;
}
half4 main(float2 fc) {
    float2 hb = uPanel.zw * 0.5;
    float2 c = uPanel.xy + hb;
    float d = box(fc - c, hb, uRadius);
    if (d > 1.0) { return half4(0.0); }
    float band = uRadius * 1.3 + 1.0;
    float e = clamp(1.0 + d / band, 0.0, 1.0);
    float2 n = float2(box(fc + float2(1.0, 0.0) - c, hb, uRadius) - box(fc - float2(1.0, 0.0) - c, hb, uRadius),
                      box(fc + float2(0.0, 1.0) - c, hb, uRadius) - box(fc - float2(0.0, 1.0) - c, hb, uRadius)) * 0.5;
    float2 q = c + (fc - c) * 0.96 - n * e * e * band * 0.7;
    float2 k = uStage / uSize;
    float blur = uFrost * uSize.x * 0.035;
    float3 acc = float3(0.0);
    for (int i = 0; i < 9; i++) {
        float fi = float(i);
        float a = fi * 2.39996;
        float r = blur * sqrt((fi + 0.5) / 9.0);
        acc += float3(stage.eval((q + float2(cos(a), sin(a)) * r) * k).rgb);
    }
    acc = acc / 9.0;
    float3 col = mix(acc, float3(1.0), 0.07 + 0.12 * e * e);
    float cov = clamp(0.5 - d, 0.0, 1.0);
    return half4(half3(col * cov), half(cov));
}
"""
}
