package io.trimio.engine.motion

/**
 * Two-buffer transition programs in one SkSL/AGSL dialect (portability as [FxSources]: float
 * literals, constant loop bounds, `half4 main(float2)`, premultiplied out). Every port keeps its
 * gl-transitions header — author and MIT — at the top of its source, so the licence travels with
 * the program. Endpoints are exact everywhere: progress 0 draws only the outgoing frame, 1 only
 * the incoming one.
 */
internal object TransitionSources {

    /** What every blend reads: the two frames, the target size and the 0..1 progress. */
    private const val TRANSITION_HEAD = """
uniform shader fromTex;
uniform shader toTex;
uniform float2 uSize;
uniform float uProgress;
"""

    private const val NOISE = """
float hash(float2 p) {
    p = fract(p * float2(123.34, 456.21));
    p += dot(p, p + 45.32);
    return fract(p.x * p.y);
}
"""

    val fade = """
// Author: gre
// License: MIT
// Description: A linear crossfade between the two images
// (gl-transitions Fade.glsl, ported; behaviour unchanged)
$TRANSITION_HEAD
half4 main(float2 fc) {
    return mix(fromTex.eval(fc), toTex.eval(fc), half(uProgress));
}
"""

    val fadecolor = """
// Author: gre
// License: MIT
// Description: A fade through a solid color, black by default
// (gl-transitions FadeColor.glsl, ported; the vec4 colour becomes uColor + uColorPhase)
$TRANSITION_HEAD
uniform float3 uColor;
uniform float uColorPhase;
half4 main(float2 fc) {
    half4 through = half4(half3(uColor), 1.0);
    half4 a = mix(through, fromTex.eval(fc), half(smoothstep(1.0 - uColorPhase, 0.0, uProgress)));
    half4 b = mix(through, toTex.eval(fc), half(smoothstep(uColorPhase, 1.0, uProgress)));
    return mix(a, b, half(uProgress));
}
"""

    val directionalwarp = """
// Author: pschroen
// License: MIT
// Description: A diagonal wipe with a warped, smeared edge
// (gl-transitions DirectionalWarp.glsl, ported; uBlur extends it with a directional smear)
$TRANSITION_HEAD
uniform float uSmoothness;
uniform float2 uDirection;
uniform float uBlur;
half4 main(float2 fc) {
    float2 uv = fc / uSize;
    float2 v = normalize(uDirection);
    v /= abs(v.x) + abs(v.y);
    float d = v.x * 0.5 + v.y * 0.5;
    float m = 1.0 - smoothstep(-uSmoothness, 0.0, v.x * uv.x + v.y * uv.y - (d - 0.5 + uProgress * (1.0 + uSmoothness)));
    float2 fa = (uv - 0.5) * (1.0 - m) + 0.5;
    float2 fb = (uv - 0.5) * m + 0.5;
    half4 a = half4(0.0);
    half4 b = half4(0.0);
    for (int i = 0; i < 5; i++) {
        float k = (float(i) - 2.0) * 0.5;
        a += fromTex.eval((fa + v * (uBlur * k)) * uSize);
        b += toTex.eval((fb + v * (uBlur * k)) * uSize);
    }
    return mix(a * 0.2, b * 0.2, half(m));
}
"""

    val crosswarp = """
// Author: Eke Péter <peterekepeter@gmail.com>
// License: MIT
// Description: The outgoing image zooms in while the incoming one zooms out into place
// (gl-transitions CrossWarp.glsl, ported)
$TRANSITION_HEAD
half4 main(float2 fc) {
    float2 uv = fc / uSize;
    float x = smoothstep(0.0, 1.0, uProgress * 2.0 + uv.x - 1.0);
    float2 fa = (uv - 0.5) * (1.0 - x) + 0.5;
    float2 fb = (uv - 0.5) * x + 0.5;
    return mix(fromTex.eval(fa * uSize), toTex.eval(fb * uSize), half(x));
}
"""

    val lumawipe = """
// Author: gre
// License: MIT
// Description: A wipe that follows the brightness of a grayscale mask
// (gl-transitions LumaWipe.glsl, ported; the mask is the outgoing frame's own luminance)
$TRANSITION_HEAD
uniform float uSoft;
float luma(float3 c) { return dot(c, float3(0.2126, 0.7152, 0.0722)); }
half4 main(float2 fc) {
    half4 a = fromTex.eval(fc);
    half4 b = toTex.eval(fc);
    float lum = luma(float3(a.rgb));
    float m = uProgress <= 0.0 ? 1.0 : (uProgress >= 1.0 ? 0.0 : smoothstep(uProgress - uSoft, uProgress + uSoft, lum));
    return mix(b, a, half(m));
}
"""

    val pixelize = """
// Author: gre
// License: MIT
// Description: Both images pixelate, crossfade, then sharpen again
// (gl-transitions Pixelize.glsl, ported)
$TRANSITION_HEAD
uniform float uSquares;
uniform float uSteps;
half4 main(float2 fc) {
    float2 uv = fc / uSize;
    float d = min(uProgress, 1.0 - uProgress);
    float dist = uSteps > 0.0 ? ceil(d * uSteps) / uSteps : d;
    float2 squareSize = 2.0 * dist / float2(uSquares, uSquares);
    float2 p = dist > 0.0 ? (floor(uv / squareSize) + 0.5) * squareSize : uv;
    return mix(fromTex.eval(p * uSize), toTex.eval(p * uSize), half(uProgress));
}
"""

    val circleopen = """
// Author: gre
// License: MIT
// Description: A soft-edged circle opens from the center to reveal the incoming image
// (gl-transitions CircleOpen.glsl, ported)
$TRANSITION_HEAD
uniform float uSmoothness;
uniform float uOpening;
half4 main(float2 fc) {
    float2 uv = fc / uSize;
    float x = uOpening > 0.5 ? uProgress : 1.0 - uProgress;
    float m = smoothstep(-uSmoothness, 0.0, 1.414213562373 * distance(float2(0.5, 0.5), uv) - x * (1.0 + uSmoothness));
    return mix(fromTex.eval(fc), toTex.eval(fc), half(uOpening > 0.5 ? 1.0 - m : m));
}
"""

    val noisedissolve = """
// Author: towrabbit
// License: MIT
// Description: The incoming image appears pixel by pixel in random noise
// (gl-transitions NoiseDissolve.glsl, ported)
$TRANSITION_HEAD
uniform float uNoiseScale;
uniform float uSeed;
float random(float2 st) {
    return fract(sin(dot(st, float2(12.9898, 78.233))) * 43758.5453123);
}
half4 main(float2 fc) {
    float2 uv = fc / uSize;
    float pick = floor(random(uv * uNoiseScale + uSeed) + uProgress);
    return mix(fromTex.eval(fc), toTex.eval(fc), half(pick));
}
"""

    // Trimio (in-house): a whip pan — the frames slide past each other under a directional smear.
    val whip = """
$TRANSITION_HEAD
uniform float2 uDir;
uniform float uBlur;
half4 main(float2 fc) {
    float p = uProgress;
    float2 outSlide = uDir * p;
    float2 inSlide = uDir * (1.0 - p);
    half4 a = half4(0.0);
    half4 b = half4(0.0);
    for (int i = 0; i < 5; i++) {
        float k = (float(i) - 2.0) * 0.5;
        float2 j = float2(uBlur * k, 0.0);
        a += fromTex.eval(fc + outSlide + j);
        b += toTex.eval(fc - inSlide + j);
    }
    return mix(a * 0.2, b * 0.2, half(p));
}
"""

    // Trimio (in-house): a zoom cut — the outgoing rushes past while the incoming settles from a step in.
    val zoom = """
$TRANSITION_HEAD
uniform float uZoom;
uniform float uBlur;
half4 main(float2 fc) {
    float2 c = uSize * 0.5;
    float p = uProgress;
    float2 pf = (fc - c) / (1.0 + p * uZoom) + c;
    float2 pt = (fc - c) / (1.0 + (1.0 - p) * uZoom) + c;
    half4 a = half4(0.0);
    half4 b = half4(0.0);
    for (int i = 0; i < 5; i++) {
        float k = (float(i) - 2.0) * 0.5;
        float2 j = float2(uBlur * k, uBlur * k * 0.6);
        a += fromTex.eval(pf + j);
        b += toTex.eval(pt + j);
    }
    return mix(a * 0.2, b * 0.2, half(p));
}
"""

    // Trimio (in-house): the glitch cut's picture — RGB split and slice offsets, strongest mid-cut.
    val glitchlite = """
$TRANSITION_HEAD
uniform float uAmount;
uniform float uSeed;
$NOISE
half4 main(float2 fc) {
    float2 uv = fc / uSize;
    float p = clamp(uProgress, 0.0, 1.0);
    float burst = uAmount * sin(p * 3.14159265);
    float rows = 14.0 + 18.0 * hash(float2(uSeed, 1.0));
    float row = floor(uv.y * rows);
    float tick = floor(p * 4.0) + uSeed;
    float on = step(0.55, hash(float2(row, tick)));
    float shift = (hash(float2(row * 1.3, tick + 2.0)) - 0.5) * uSize.x * 0.12 * on * burst;
    float2 base = fc + float2(shift, 0.0);
    half4 mid = mix(fromTex.eval(base), toTex.eval(base), half(p));
    float split = uSize.x * 0.012 * burst;
    half4 splitR = mix(fromTex.eval(base + float2(split, 0.0)), toTex.eval(base + float2(split, 0.0)), half(p));
    half4 splitB = mix(fromTex.eval(base - float2(split, 0.0)), toTex.eval(base - float2(split, 0.0)), half(p));
    return half4(splitR.r, mid.g, splitB.b, max(mid.a, max(splitR.a, splitB.a)));
}
"""
}
