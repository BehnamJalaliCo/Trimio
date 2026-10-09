package io.trimio.core.designsystem.shader

/**
 * Shader sources shared by AGSL and SkSL. Rules for portability:
 * float literals only (1.0 not 1), constant loop bounds, premultiplied output.
 */
object ShaderSources {

    private const val NOISE = """
float hash(float2 p) { return fract(sin(dot(p, float2(127.1, 311.7))) * 43758.5453); }

float noise(float2 p) {
    float2 i = floor(p);
    float2 f = fract(p);
    float2 u = f * f * (3.0 - 2.0 * f);
    return mix(mix(hash(i), hash(i + float2(1.0, 0.0)), u.x),
               mix(hash(i + float2(0.0, 1.0)), hash(i + float2(1.0, 1.0)), u.x), u.y);
}

float fbm(float2 p) {
    float v = 0.0;
    float a = 0.5;
    for (int i = 0; i < 5; i++) {
        v += a * noise(p);
        p = p * 2.03 + float2(1.7, 9.2);
        a *= 0.5;
    }
    return v;
}
"""

    /**
     * Slow-moving aurora bands over a dark base, with vignette and film grain.
     * `iEnergy` (0..1) brightens the third band — wire it to audio loudness for an audio-reactive canvas.
     */
    val aurora: String = """
uniform float2 iResolution;
uniform float iTime;
uniform float iEnergy;
uniform float3 cBase;
uniform float3 cA;
uniform float3 cB;
uniform float3 cC;
$NOISE
half4 main(float2 fragCoord) {
    float2 uv = fragCoord / iResolution;
    float aspect = iResolution.x / iResolution.y;
    float2 p = float2(uv.x * aspect, uv.y);
    float t = iTime * 0.05;

    // Two curtain layers: noise bands warped by slow noise. fbm clusters around 0.5,
    // so a narrow smoothstep window turns it into defined light streaks.
    float warp = fbm(float2(p.x * 2.0 + t, p.y * 0.8 - t * 0.6));
    float bandsA = fbm(float2(p.x * 4.0 + warp * 2.5 - t * 1.4, p.y * 0.5 + t * 0.2));
    float bandsB = fbm(float2(p.x * 3.0 - warp * 2.0 + t * 1.1 + 7.3, p.y * 0.4 - t * 0.15));
    float top = 1.0 - smoothstep(0.0, 1.1, uv.y + (warp - 0.5) * 0.6);
    float curtainA = smoothstep(0.45, 0.62, bandsA) * top;
    float curtainB = smoothstep(0.47, 0.64, bandsB) * (0.4 + 0.6 * top);
    float haze = smoothstep(0.38, 0.66, warp);

    float3 col = cBase;
    col += cA * haze * 1.3;
    col += cB * curtainA * 2.4;
    col += cC * curtainB * (1.2 + 1.4 * iEnergy);
    col += (cB + cC) * 0.5 * exp(-abs(uv.y - 0.8 + warp * 0.25) * 5.0) * haze;

    // Edges reversed via 1 - smoothstep: smoothstep with edge0 > edge1 is undefined in GLSL/AGSL.
    float vignette = 1.0 - smoothstep(0.25, 1.3, length((uv - 0.5) * float2(1.0, 0.8)) * 1.5);
    col *= mix(0.45, 1.0, vignette);
    col = col / (1.0 + col * 0.35);

    float grain = hash(fragCoord + fract(iTime) * 113.0) - 0.5;
    col += grain * 0.03;
    return half4(half3(col), 1.0);
}
"""

    /**
     * A glass sphere filling with liquid up to `iProgress` (0..1): two travelling waves on the
     * surface, a bright meniscus, depth gradient and a glass rim. Transparent outside the circle.
     */
    val liquidOrb: String = """
uniform float2 iResolution;
uniform float iTime;
uniform float iProgress;
uniform float3 cDeep;
uniform float3 cSurface;
uniform float3 cGlow;

half4 main(float2 fragCoord) {
    float2 uv = fragCoord / iResolution;
    float r = length(uv - 0.5);
    float body = 1.0 - smoothstep(0.49, 0.5, r);
    if (body <= 0.0) { return half4(0.0); }

    float level = 1.0 - iProgress;
    float calm = 1.0 - abs(iProgress - 0.5) * 1.2;
    float wave = (0.020 * sin(uv.x * 11.0 + iTime * 2.3) + 0.012 * sin(uv.x * 23.0 - iTime * 3.4)) * max(calm, 0.15);
    float surface = level + wave;

    float liquid = smoothstep(surface - 0.006, surface + 0.006, uv.y);
    float depth = clamp((uv.y - surface) / max(1.0 - surface, 0.001), 0.0, 1.0);
    float3 col = mix(cSurface, cDeep, depth);
    float meniscus = exp(-abs(uv.y - surface) * 70.0);
    col += cGlow * meniscus * 0.8;

    // Caustic shimmer inside the liquid.
    float shimmer = sin(uv.x * 40.0 + iTime * 1.7) * sin(uv.y * 34.0 - iTime * 1.3);
    col += cGlow * max(shimmer, 0.0) * 0.06 * liquid;

    // Glass: faint fill, bright rim, specular highlight top-left.
    float rim = smoothstep(0.43, 0.5, r) * body;
    float spec = exp(-length(uv - float2(0.33, 0.28)) * 9.0) * 0.55;
    float glassAlpha = 0.025 + rim * 0.5 + spec;
    float3 glass = mix(float3(1.0), cSurface, 0.35);

    float alpha = max(liquid * body, glassAlpha * body);
    float3 outCol = mix(glass, col, liquid);
    outCol += float3(1.0) * spec * (1.0 - liquid * 0.5);
    return half4(half3(outCol * alpha), half(alpha));
}
"""
}
