import json, sys
out = sys.argv[1]

KINETIC_STAGE = r"""
uniform float2 iResolution;
uniform float iTime;
uniform float iEnergy;
uniform float3 cBase;
uniform float3 cA;
uniform float3 cB;
uniform float3 cC;

float hash(float2 p) { return fract(sin(dot(p, float2(127.1, 311.7))) * 43758.5453); }

half4 main(float2 fragCoord) {
    float2 uv = fragCoord / iResolution;
    float aspect = iResolution.x / iResolution.y;
    float2 p = float2((uv.x - 0.5) * aspect, uv.y - 0.5);

    // A stage spotlight drifting slowly behind the type.
    float2 c = float2(0.18 * sin(iTime * 0.31), 0.12 * cos(iTime * 0.23));
    float d = length(p - c);
    float spot = exp(-d * d * 6.0);
    float3 col = mix(cBase, cA, spot * 0.9);

    // Diagonal speed lines that flare with the voice.
    float k = (p.x + p.y * 0.6) * 28.0 - iTime * 1.5;
    float lines = smoothstep(0.92, 1.0, abs(sin(k)));
    col += cC * lines * (0.04 + 0.12 * iEnergy) * (0.4 + spot);

    // A thin halo pulsing on loud syllables.
    col += cB * exp(-abs(d - 0.38) * 30.0) * (0.05 + 0.25 * iEnergy);

    float grain = hash(fragCoord + fract(iTime) * 91.0) - 0.5;
    col += grain * 0.025;
    return half4(half3(col), 1.0);
}
""".strip() + "\n"

packs = {
  "kinetic-typography": {
    "schema": 1, "id": "kinetic-typography", "version": "1.0.0",
    "nameFa": "کینتیک تایپوگرافی", "nameEn": "Kinetic Typography",
    "descriptionFa": "کلمه‌های غول‌پیکر و پرانرژی که هم‌زمان با ضرب صدا روی صفحه می‌کوبند.",
    "descriptionEn": "Giant, punchy words that slam onto the screen on the beat of the voice.",
    "family": "Typography", "cost": "Light",
    "tags": ["energetic", "bold", "hype", "motivation", "ad", "kinetic", "typography", "پرانرژی", "انگیزشی", "تبلیغاتی", "هیجانی", "تایپوگرافی"],
    "spec": {
      "id": "kinetic-typography", "family": "Typography",
      "palette": {"background": ["#0A0A0D", "#1C1C26", "#FFD400", "#FF2E63"], "text": "#F6F4EE", "accent": "#FFD400",
                  "accent2": "#FF2E63", "emphasisText": "#FFD400", "shadow": "#000000CC"},
      "captions": {"mode": "SingleWord", "size": 0.13, "weight": 900, "uppercase": True, "letterSpacingEm": -0.03,
                   "maxWordsPerLine": 1, "anchor": "Center", "entry": "slam", "exit": "shrink", "box": "None",
                   "strokeWidth": 0.0, "shadowBlur": 0.025,
                   "emphasis": {"scale": 1.3, "colorRole": "accent", "box": "None", "threshold": 0.6}},
      "background": {"preset": "shader:kinetic-stage", "audioReactive": True},
      "overlay": {"grain": 0.035, "vignette": 0.5, "letterbox": 0.0},
      "elements": {"card": "outline", "cornerRadius": 0.01},
      "motion": {"energy": 0.9, "cameraPunch": True},
      "sfx": {"caption.emphasis": "sfx/impact", "caption.word": "sfx/tick", "element.enter": "sfx/whoosh"},
      "audioOnly": {"captionAnchor": "Center", "captionScale": 1.0, "visualizer": "none"},
      "shaders": {"kinetic-stage": KINETIC_STAGE}
    }
  },
  "neobrutalism": {
    "schema": 1, "id": "neobrutalism", "version": "1.0.0",
    "nameFa": "نئوبروتالیسم", "nameEn": "Neobrutalism",
    "descriptionFa": "قاب‌های تخت با حاشیهٔ ضخیم مشکی، سایهٔ سخت و رنگ‌های جسور؛ خوانا و بازیگوش.",
    "descriptionEn": "Flat cards with thick black borders, hard offset shadows and bold colours: loud, legible, playful.",
    "family": "Vector2D", "cost": "Light",
    "tags": ["playful", "bold", "flat", "education", "tutorial", "startup", "بازیگوش", "آموزشی", "جسور", "فان"],
    "spec": {
      "id": "neobrutalism", "family": "Vector2D",
      "palette": {"background": ["#FFF4E0", "#1B1B1B", "#FF6B6B", "#4D96FF"], "text": "#111111", "accent": "#FF6B6B",
                  "accent2": "#4D96FF", "emphasisText": "#111111", "shadow": "#00000000"},
      "captions": {"mode": "Phrase", "size": 0.088, "weight": 900, "uppercase": False, "letterSpacingEm": 0.0,
                   "maxWordsPerLine": 3, "anchor": "BottomCenter", "entry": "pop", "exit": "fall", "box": "Brutal",
                   "boxColor": "#FFD93D", "strokeWidth": 0.0, "shadowBlur": 0.0,
                   "emphasis": {"scale": 1.12, "colorRole": "accent", "box": "Highlight", "threshold": 0.6}},
      "background": {"preset": "grid", "audioReactive": True},
      "overlay": {"grain": 0.0, "vignette": 0.0, "letterbox": 0.0},
      "elements": {"card": "brutal", "cornerRadius": 0.012},
      "motion": {"energy": 0.7, "cameraPunch": True},
      "sfx": {"caption.emphasis": "sfx/pop", "element.enter": "sfx/click"},
      "audioOnly": {"captionAnchor": "Center", "captionScale": 1.2, "visualizer": "bars"}
    }
  },
  "liquid-glass": {
    "schema": 1, "id": "liquid-glass", "version": "1.0.0",
    "nameFa": "لیکوئید گلس", "nameEn": "Liquid Glass",
    "descriptionFa": "شیشهٔ مایع روی نورهای شفق؛ لوکس، آرام و سینمایی.",
    "descriptionEn": "Liquid glass floating over aurora light: premium, calm and cinematic.",
    "family": "ShaderFx", "cost": "Heavy",
    "tags": ["premium", "luxury", "calm", "cinematic", "tech", "crypto", "finance", "لوکس", "سینمایی", "آرام", "کریپتو", "فارکس", "مالی"],
    "spec": {
      "id": "liquid-glass", "family": "ShaderFx",
      "palette": {"background": ["#05050A", "#2B1A7A", "#0A5C78", "#B0249C"], "text": "#FFFFFF", "accent": "#3DE8FF",
                  "accent2": "#FF4FD8", "emphasisText": "#3DE8FF", "shadow": "#00000099"},
      "captions": {"mode": "BuildUp", "size": 0.1, "weight": 800, "uppercase": False, "letterSpacingEm": -0.01,
                   "maxWordsPerLine": 4, "anchor": "BottomCenter", "entry": "pop", "exit": "fade", "box": "Glass",
                   "strokeWidth": 0.0, "shadowBlur": 0.012,
                   "emphasis": {"scale": 1.22, "colorRole": "accent", "box": "None", "threshold": 0.6}},
      "background": {"preset": "aurora", "audioReactive": True},
      "overlay": {"grain": 0.025, "vignette": 0.35, "letterbox": 0.0},
      "elements": {"card": "glass", "cornerRadius": 0.04},
      "motion": {"energy": 0.6, "cameraPunch": True},
      "sfx": {"caption.emphasis": "sfx/shimmer", "element.enter": "sfx/whoosh-soft"},
      "audioOnly": {"captionAnchor": "Center", "captionScale": 1.25, "visualizer": "ring"}
    }
  },
}
