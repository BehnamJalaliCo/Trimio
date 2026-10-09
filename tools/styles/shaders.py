"""SkSL/AGSL background shaders for style packs.

Portability rules (docs/STYLE_PACKS.md): float literals only, fixed loop bounds, premultiplied
opaque output, never smoothstep with reversed edges. Standard uniforms: iResolution, iTime,
iEnergy (0..1 voice loudness), cBase/cA/cB/cC (the palette's background colours).
"""

HEADER = """uniform float2 iResolution;
uniform float iTime;
uniform float iEnergy;
uniform float3 cBase;
uniform float3 cA;
uniform float3 cB;
uniform float3 cC;

float hash(float2 p) { return fract(sin(dot(p, float2(127.1, 311.7))) * 43758.5453); }
float noise(float2 p) {
    float2 i = floor(p);
    float2 f = fract(p);
    float2 u = f * f * (3.0 - 2.0 * f);
    return mix(mix(hash(i), hash(i + float2(1.0, 0.0)), u.x), mix(hash(i + float2(0.0, 1.0)), hash(i + float2(1.0, 1.0)), u.x), u.y);
}
// Falling edge (1 below e0, 0 above e1, with e0 < e1): smoothstep itself must never get reversed edges.
float fall(float e0, float e1, float x) { return 1.0 - smoothstep(e0, e1, x); }
float fbm(float2 p) {
    float v = 0.0;
    float a = 0.5;
    for (int i = 0; i < 4; i++) { v += a * noise(p); p = p * 2.03 + float2(1.7, 9.2); a *= 0.5; }
    return v;
}
"""


def shader(body: str) -> str:
    return (HEADER + body).strip() + "\n"


CHROME = shader("""
half4 main(float2 fragCoord) {
    float2 uv = fragCoord / iResolution;
    float2 p = (fragCoord - 0.5 * iResolution) / iResolution.y;
    float t = iTime * 0.15;
    // Liquid metal: a warped field read through a banded studio environment, like light sliding
    // over polished chrome. Bands are smooth (no derivatives), so it never shows contour artefacts.
    float2 w = float2(fbm(p * 1.3 + t), fbm(p * 1.3 - t + 4.7));
    float h = fbm(p * 1.8 + w * 1.6 + float2(0.0, t * 0.5));
    float bands = 0.5 + 0.5 * sin(h * 30.0 + p.y * 2.5 - t * 3.0);
    float sharp = pow(bands, 6.0);
    float3 col = mix(cBase, cA, smoothstep(0.15, 0.85, h));
    col = mix(col, cB, sharp * 0.9);
    col += cC * pow(bands, 28.0) * (0.35 + 0.5 * iEnergy);
    // Darker top and bottom, like a horizon reflected in the metal.
    col *= 0.55 + 0.45 * sin(uv.y * 3.1416);
    return half4(half3(col), 1.0);
}
""")

CLAY = shader("""
float blob(float2 p, float2 c, float r) { return r / max(length(p - c), 0.001); }
half4 main(float2 fragCoord) {
    float2 p = (fragCoord - 0.5 * iResolution) / iResolution.y;
    float t = iTime * 0.35;
    float f = blob(p, float2(0.32 * sin(t), 0.22 * cos(t * 1.3)), 0.16)
            + blob(p, float2(-0.28 * cos(t * 0.8), 0.3 * sin(t * 0.6)), 0.14)
            + blob(p, float2(0.18 * sin(t * 1.7 + 2.0), -0.3 * cos(t * 0.9)), 0.12 + 0.03 * iEnergy);
    float inside = smoothstep(0.95, 1.05, f);
    // Soft matte shading: light from the top-left, a warm bounce from below.
    float shade = clamp(0.55 + 0.45 * (p.y * 0.8 - p.x * 0.4) + 0.15 * (f - 1.0), 0.0, 1.0);
    float3 clay = mix(cA * 0.75, cA * 1.12, shade) + cC * 0.08 * fall(-0.4, 0.2, p.y);
    float3 floorCol = mix(cBase, cB, 0.25 + 0.25 * (1.0 - length(p)));
    float rim = smoothstep(0.85, 1.0, f) * (1.0 - inside);
    float3 col = mix(floorCol, clay, inside) - rim * 0.12;
    return half4(half3(col), 1.0);
}
""")

SYNTHWAVE = shader("""
half4 main(float2 fragCoord) {
    float2 uv = fragCoord / iResolution;
    float2 p = float2((uv.x - 0.5) * iResolution.x / iResolution.y, uv.y);
    float horizon = 0.58;
    float3 col;
    if (p.y < horizon) {
        // Sky: dusk gradient, a striped sun that breathes with the voice.
        float3 sky = mix(cC, cBase, smoothstep(0.0, horizon, p.y));
        float2 sp = float2(p.x, p.y - horizon + 0.2);
        float d = length(sp);
        float sun = fall(0.235, 0.24 + 0.02 * iEnergy, d);
        float stripes = step(0.5, fract((sp.y + 0.2) * 22.0 + iTime * 0.3)) + step(0.0, sp.y + 0.05);
        sun *= clamp(stripes, 0.0, 1.0);
        col = mix(sky, mix(cB, cA, smoothstep(-0.2, 0.2, sp.y)), sun);
        col += cA * exp(-d * 4.0) * 0.25;
    } else {
        // Ground: perspective grid scrolling to the horizon.
        float z = 1.0 / max(p.y - horizon, 0.002);
        float gx = abs(fract(p.x * z * 0.6) - 0.5);
        float gz = abs(fract(z * 0.25 - iTime * 0.8) - 0.5);
        float line = fall(0.0, 0.0036 * z, min(gx, gz) / z * 4.0);
        col = mix(cBase, cA * (0.9 + 0.6 * iEnergy), clamp(line, 0.0, 1.0));
        col += cB * 0.15 * fall(horizon, horizon + 0.15, p.y);
    }
    return half4(half3(col), 1.0);
}
""")

IRIDESCENT = shader("""
half4 main(float2 fragCoord) {
    float2 p = (fragCoord - 0.5 * iResolution) / iResolution.y;
    float t = iTime * 0.25;
    float w = fbm(p * 1.6 + t) + 0.5 * fbm(p * 3.1 - t * 1.3);
    // Thin-film rainbow, the Y2K chrome-bubble sheen.
    float3 film = 0.5 + 0.5 * cos(6.2831 * (w * 1.4 + float3(0.0, 0.33, 0.67) + t * 0.2));
    float3 col = mix(cBase, mix(cA, cB, film.x), 0.35) + film * 0.25;
    for (int i = 0; i < 5; i++) {
        float fi = float(i);
        float2 c = float2(sin(t * 0.7 + fi * 2.1) * 0.55, cos(t * 0.5 + fi * 1.3) * 0.75);
        float r = 0.08 + 0.04 * fract(fi * 0.37) + 0.02 * iEnergy;
        float d = length(p - c);
        float ring = fall(r - 0.012, r, d) - fall(r - 0.03, r - 0.012, d);
        col += cC * ring * 0.7 + float3(1.0) * fall(0.0, r * 0.35, length(p - c + float2(r * 0.35))) * 0.35;
    }
    return half4(half3(col), 1.0);
}
""")

ORGANIC = shader("""
half4 main(float2 fragCoord) {
    float2 p = (fragCoord - 0.5 * iResolution) / iResolution.y;
    float t = iTime * 0.12;
    // Slow, breathing organic forms (leaf shadows / living cells).
    float2 q = float2(fbm(p * 1.4 + t), fbm(p * 1.4 - t + 3.1));
    float f = fbm(p * 1.6 + q * 1.8 + float2(t * 0.6, -t));
    float3 col = mix(cBase, cA, smoothstep(0.25, 0.75, f));
    col = mix(col, cB, smoothstep(0.55, 0.85, q.x) * 0.6);
    // Caustic light dapples.
    float c = pow(abs(sin(f * 12.0 + t * 3.0)), 18.0);
    col += cC * c * (0.12 + 0.2 * iEnergy);
    return half4(half3(col), 1.0);
}
""")

TUNNEL = shader("""
half4 main(float2 fragCoord) {
    float2 p = (fragCoord - 0.5 * iResolution) / iResolution.y;
    float r = length(p);
    float a = atan(p.y, p.x);
    // Infinite 3D tunnel, rushing forward faster when the voice gets loud.
    float z = 0.35 / max(r, 0.02) + iTime * (0.6 + 0.8 * iEnergy);
    float ribs = smoothstep(0.42, 0.5, abs(fract(z) - 0.5));
    float seams = smoothstep(0.46, 0.5, abs(fract(a * 1.909) - 0.5));
    float glow = clamp(ribs + seams * 0.6, 0.0, 1.0);
    float depth = smoothstep(0.0, 0.7, r);
    float3 col = mix(cBase, mix(cA, cB, 0.5 + 0.5 * sin(z * 0.7)), glow) * depth;
    col += cC * exp(-r * 6.0) * (0.4 + 0.6 * iEnergy);
    return half4(half3(col), 1.0);
}
""")

SPATIAL = shader("""
half4 main(float2 fragCoord) {
    float2 uv = fragCoord / iResolution;
    float2 p = (fragCoord - 0.5 * iResolution) / iResolution.y;
    float t = iTime * 0.2;
    // A room of soft light with floating translucent panels at different depths.
    float3 col = mix(cBase, cA, fall(0.0, 1.1, length(p - float2(0.2 * sin(t), 0.25))));
    for (int i = 0; i < 4; i++) {
        float fi = float(i);
        float depth = 0.6 + fi * 0.25;
        float2 c = float2(sin(t * (0.6 + fi * 0.2) + fi * 1.7) * 0.4, cos(t * 0.5 + fi * 2.3) * 0.5) / depth;
        float2 d = abs(p - c) - float2(0.22, 0.14) / depth;
        float box = length(max(d, float2(0.0))) + min(max(d.x, d.y), 0.0) - 0.03;
        float panel = fall(0.0, 0.004, box);
        float edge = fall(0.0, 0.006, abs(box)) * 0.8;
        col = mix(col, mix(col, cB, 0.35), panel * 0.55 / depth) + cC * edge * 0.35 / depth;
    }
    col += (hash(fragCoord + iTime) - 0.5) * 0.015;
    col *= 0.9 + 0.1 * uv.y;
    return half4(half3(col), 1.0);
}
""")

NEUMORPH = shader("""
half4 main(float2 fragCoord) {
    float2 p = (fragCoord - 0.5 * iResolution) / iResolution.y;
    float t = iTime * 0.3;
    // Soft extruded and debossed shapes from the same matte surface.
    float3 col = cBase;
    float2 c1 = float2(-0.25, 0.32 + 0.02 * sin(t));
    float2 c2 = float2(0.28, -0.28 + 0.02 * cos(t * 0.8));
    float d1 = length(p - c1) - 0.17 - 0.015 * iEnergy;
    float d2 = length(p - c2) - 0.13;
    // Light from the top-left: highlight on one side, shadow on the other.
    float l1 = length(p - c1 + float2(0.03, -0.03)) - 0.17;
    float s1 = length(p - c1 - float2(0.03, -0.03)) - 0.17;
    col += cA * 0.10 * fall(0.0, 0.06, l1) * smoothstep(-0.01, 0.0, d1);
    col -= cB * 0.10 * fall(0.0, 0.06, s1) * smoothstep(-0.01, 0.0, d1);
    float l2 = length(p - c2 - float2(0.025, -0.025)) - 0.13;
    col += cA * 0.08 * fall(-0.05, 0.0, d2) * smoothstep(-0.06, 0.02, l2);
    col -= cB * 0.06 * fall(-0.05, 0.0, d2) * fall(-0.06, 0.02, l2);
    return half4(half3(col), 1.0);
}
""")

KALEIDO = shader("""
half4 main(float2 fragCoord) {
    float2 p = (fragCoord - 0.5 * iResolution) / iResolution.y;
    float r = length(p);
    float a = atan(p.y, p.x) + iTime * 0.08;
    // Maximalism: an eight-fold kaleidoscope of saturated noise.
    float seg = 0.7854;
    a = abs(fract(a / seg) - 0.5) * seg;
    float2 q = float2(cos(a), sin(a)) * r;
    float f = fbm(q * 4.0 + iTime * 0.15);
    float3 col = mix(cA, cB, smoothstep(0.3, 0.7, f));
    col = mix(col, cC, smoothstep(0.62, 0.8, fbm(q * 7.0 - iTime * 0.2)));
    col = mix(col, cBase, smoothstep(0.75, 1.2, r));
    col *= 0.85 + 0.3 * iEnergy * fall(0.0, 0.6, r);
    return half4(half3(col), 1.0);
}
""")

GLITCH = shader("""
half4 main(float2 fragCoord) {
    float2 uv = fragCoord / iResolution;
    float t = floor(iTime * 12.0);
    // Experimental type stage: scan bands that tear sideways on loud syllables.
    float band = floor(uv.y * 48.0);
    float tear = (hash(float2(band, t)) - 0.5) * 0.12 * step(0.82 - 0.35 * iEnergy, hash(float2(t, band * 0.3)));
    float x = uv.x + tear;
    float stripes = step(0.5, fract(x * 6.0 + uv.y * 0.5));
    float3 col = mix(cBase, cA, stripes * 0.18);
    col.r += cB.r * 0.25 * step(0.97, hash(float2(band, t + 1.0)));
    col += cC * 0.35 * step(0.985, hash(float2(floor(x * 60.0), band + t)));
    col += (hash(fragCoord + t) - 0.5) * 0.06;
    return half4(half3(clamp(col, 0.0, 1.0)), 1.0);
}
""")

PAPER = shader("""
half4 main(float2 fragCoord) {
    float2 uv = fragCoord / iResolution;
    float2 p = fragCoord / iResolution.y;
    // Craft paper: fibres, soft blotches, a vignette of handling.
    float fibres = noise(float2(p.x * 900.0, p.y * 40.0)) * 0.5 + noise(p * 300.0) * 0.5;
    float blotch = fbm(p * 3.0);
    float3 col = mix(cBase, cA, blotch * 0.35) + (fibres - 0.5) * 0.06;
    // Torn scraps of coloured paper drifting very slowly.
    for (int i = 0; i < 3; i++) {
        float fi = float(i);
        float2 c = float2(0.2 + 0.6 * fract(fi * 0.61), 0.15 + 0.7 * fract(fi * 0.37)) * float2(iResolution.x / iResolution.y, 1.0);
        c += float2(sin(iTime * 0.1 + fi), cos(iTime * 0.08 + fi)) * 0.02;
        float2 d = abs(p - c) - float2(0.16, 0.09);
        float edge = noise((p - c) * 40.0) * 0.012;
        float scrap = fall(0.0, 0.002, max(d.x, d.y) - edge);
        col = mix(col, i == 1 ? cC : cB, scrap * 0.8);
    }
    col *= 1.0 - 0.18 * length(uv - 0.5);
    return half4(half3(col), 1.0);
}
""")

CONFETTI = shader("""
half4 main(float2 fragCoord) {
    float2 p = fragCoord / iResolution.y;
    float3 col = cBase;
    // Sticker sheet: polka dots plus falling confetti that bursts with the voice.
    float2 g = fract(p * 9.0) - 0.5;
    col = mix(col, cA, fall(0.1, 0.12, length(g)) * 0.25);
    for (int i = 0; i < 24; i++) {
        float fi = float(i);
        float2 c = float2(fract(fi * 0.618) * iResolution.x / iResolution.y, 1.2 - fract(fi * 0.371 + iTime * (0.05 + 0.04 * fract(fi * 0.7)) * (1.0 + iEnergy)) * 1.4);
        float2 d = p - c;
        float ang = iTime * 2.0 + fi;
        float2 rd = float2(d.x * cos(ang) - d.y * sin(ang), d.x * sin(ang) + d.y * cos(ang));
        float piece = step(abs(rd.x), 0.012) * step(abs(rd.y), 0.006);
        float3 tint = fract(fi * 0.5) < 0.5 ? cB : cC;
        col = mix(col, tint, piece);
    }
    return half4(half3(col), 1.0);
}
""")

GRAIN_GRADIENT = shader("""
half4 main(float2 fragCoord) {
    float2 uv = fragCoord / iResolution;
    float t = iTime * 0.07;
    // A rich two-tone gradient under heavy film grain: the "noise" aesthetic.
    float g = smoothstep(0.0, 1.0, uv.y + 0.15 * sin(uv.x * 3.0 + t * 6.0) + 0.1 * fbm(uv * 3.0 + t));
    float3 col = mix(cA, cB, g);
    col = mix(col, cC, smoothstep(0.7, 1.0, fbm(uv * 2.0 - t)) * 0.5);
    col = mix(cBase, col, 0.9);
    float grain = hash(fragCoord * 1.37 + fract(iTime * 23.0) * 100.0) - 0.5;
    col += grain * (0.12 + 0.05 * iEnergy);
    return half4(half3(clamp(col, 0.0, 1.0)), 1.0);
}
""")

CHAOS = shader("""
half4 main(float2 fragCoord) {
    float2 uv = fragCoord / iResolution;
    float t = floor(iTime * 3.0);
    // Anti-design: clashing blocks that re-shuffle on a rough beat.
    float2 cell = floor(uv * float2(3.0, 5.0) + hash(float2(t, 1.0)) * 2.0);
    float r = hash(cell + t);
    float3 col = r < 0.33 ? cA : (r < 0.66 ? cB : cBase);
    float stripe = step(0.5, fract((uv.x + uv.y) * 14.0));
    col = mix(col, cC, stripe * step(0.8, hash(cell * 3.1 + t)));
    col += (hash(fragCoord + t) - 0.5) * 0.08;
    return half4(half3(clamp(col, 0.0, 1.0)), 1.0);
}
""")
