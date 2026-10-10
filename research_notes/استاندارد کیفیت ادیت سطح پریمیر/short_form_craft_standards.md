# Short-Form (Reels / TikTok / Shorts) Craft Standards for Talking-Head and Voice-Over Video

Research note scope: concrete, encodable rules for an automatic editor. Sourcing quality caveat up front: almost all "exact numbers" in short-form editing (cut cadence, caption px, safe-zone px, hook retention %) come from tool vendors and SEO blogs, not platforms or peer-reviewed studies. The few primary/measured sources found are: a 2026 peer-reviewed-conference pacing experiment (Dost & Huang, N=242, with a 50-TikTok field benchmark), the Netflix timed-text guides (subtitle reading speed, line rules, Arabic-script/RTL rules), Facebook IQ's 2016 attention study, Material Design / SAP Fiori motion specs, and the Adobe/Van Hurkman skin-tone guidance. Everything else is labeled as convention.

## 1. Hook structure in the first 1–3 seconds

### Takeaway
Every platform-facing and practitioner source converges on "state the proposition/payoff inside the first ~3 s, no logo or slow intro, frame already in motion," but the widely quoted retention percentages (63% CTR stat, 70% 3-s hold threshold, 50–60% of drop-off in first 3 s) are unverified vendor claims. The only solid timing data is Facebook IQ's measured mobile-feed dwell of ~1.7 s per post (2016).

### Cited Findings
- Facebook IQ (2016, review of 850+ video ads served Q4 2014–Q4 2015): people on mobile News Feed spend on average 1.7 s looking at each post vs 2.5 s on desktop — [Marketing Dive](https://www.marketingdive.com/news/facebook-why-mobile-video-ads-must-work-fast/446217/); [Social Media Today](https://www.socialmediatoday.com/news/facebook-releases-new-guide-to-help-advertisers-maximize-video-ad-performan/450818)
- Fors Marsh Group research cited by Facebook: people can recall mobile feed content at a statistically significant rate after only 0.25 s of exposure; a Nielsen study tied to Facebook/Twitter found significant mobile video ad recall at the 1-second threshold — [Marketing Dive](https://www.marketingdive.com/news/facebook-why-mobile-video-ads-must-work-fast/446217/); [ITWeb](https://www.itweb.co.za/article/mobile-content-easier-to-consume-recall/VKA3WwMdVeRvrydZ)
- TikTok's own creative best practice is quoted as "Introduce your content proposition in the first 3 seconds for better recall and awareness"; TikTok executives' ARF 2022 presentation (based on 40+ studies) listed "Maximize the first three seconds" as a top recommendation — [WARC](https://www.warc.com/content/article/event-reports/seven-ways-to-improve-the-effectiveness-of-tiktok-ads/149476)
- The "63% of top-CTR TikTok videos show the key message/product in the first 3 seconds" stat is widely repeated (sometimes as 67%) but I could not trace it to a primary TikTok study — [House of Marketers](https://houseofmarketers.com/importance-of-tiktok-ad-hooks-first-3-seconds/); [Lebesgue](https://lebesgue.io/tiktok-ads/how-to-increase-tiktok-ctr-9-creative-tips) (secondary, unverified)
- Vendor claim: 50–60% of Shorts drop-offs happen in the first 3 s; the 3-second hold is described as the distribution gate for Shorts (no YouTube primary source given) — [OpusClip](https://www.opus.pro/blog/youtube-shorts-hook-formulas); [OpusClip Shorts retention](https://www.opus.pro/blog/ideal-youtube-shorts-length-format-retention)
- Vendor claim: falling below ~70% 3-second retention "effectively kills" reach on TikTok/Reels (unsourced) — [Virlo](https://virlo.ai/blog/why-the-first-few-seconds-matter)
- YouTube Studio exposes a Shorts "Viewed vs. Swiped away" metric (share of feed impressions that chose to watch); third-party rule of thumb is ~75–80% viewed, but another source states there is no official universal threshold — [Subscribr](https://subscribr.ai/youtube-strategy/youtube-shorts-analytics-metrics-growth); [Shortimize](https://www.shortimize.com/blog/youtube-shorts-retention-rate)
- Since 31 Mar 2025, a Shorts view counts whenever a Short starts or replays (no minimum watch time); "engaged views" are the metric used for YPP/revenue — [vidIQ](https://vidiq.com/blog/post/youtube-shorts-algorithm/); [Subscribr](https://subscribr.ai/p/youtube-shorts-analytics-metrics-viral)
- Instagram (Mosseri, Jan 2025) named three key ranking signals for Reels: watch time, likes per reach, sends per reach; watch time is the top signal; sends weigh more for unconnected (non-follower) reach. No direct Mosseri statement about a "first three seconds" rule was found — [Dataslayer](https://www.dataslayer.ai/blog/instagram-algorithm-2025-complete-guide-for-marketers); [Fanpage Karma](https://www.fanpagekarma.com/insights/?p=9254)
- Hook patterns repeatedly recommended: open with a specific question, a bold claim or a curiosity gap; avoid logo/title intros; vendor claims of ~23% lift for question openings and ~60% higher retention with "strong hooks" are unsourced — [Metricool viral hooks](https://metricool.com/viral-video-hooks/); [OpusClip](https://www.opus.pro/blog/youtube-shorts-hook-formulas)
- TikTok-ad commentary warns that the early key message should sit inside a story rather than replace one (payoff-first + narrative) — [WARC](https://www.warc.com/content/article/event-reports/seven-ways-to-improve-the-effectiveness-of-tiktok-ads/149476)
- Reels B-roll guidance: first frame should already be in motion (no static open) — [Creatorflow](https://creatorflow.so/blog/b-roll-instagram-reels/)

### Inferences
- Encodable hook rule set: (a) first spoken word within ≤0.3 s of frame 0 (trim leading silence aggressively); (b) on-screen hook text visible from frame 0 (since recall registers at 0.25 s and dwell is ~1.7 s, the text must be legible in under ~1.5 s → ≤ ~6–8 words); (c) the core proposition/payoff stated before t = 3 s; (d) a visual change (punch-in, B-roll, text pop) within the first 1–1.5 s; (e) no logo bumper.
- Hook types to classify/generate: payoff-first (show result, then explain), curiosity gap/open loop (tease outcome, resolve late), bold/contrarian claim, direct question, pattern interrupt (unexpected visual/sound in frame 0). These are practitioner taxonomies, not measured categories.
- Because Instagram weights sends and YouTube now counts all starts as views, "3-second hold" is best treated as an internal proxy KPI, not a platform constant.

### Gaps
- No primary platform source publishes a 3-second-hold threshold or a measured lift for any specific hook pattern. No independent study comparing visual vs spoken hooks was found.
- The 63%/67% TikTok CTR statistic could not be traced to its original TikTok for Business report.

## 2. Pacing: cut frequency, jump cuts, silence removal, punch-ins, J/L cuts

### Takeaway
The best measured benchmark: 50 high-performing informative talking-head TikToks averaged 0.37 cuts/s (one cut every ~2.7 s; range 0.13–0.82 cuts/s), and an experiment found faster pacing beyond that reduced sustained engagement. Practitioner conventions cluster at "a visual change every 2–4 s." Silence removal defaults cluster at −30 dB threshold, 0.3–0.5 s minimum silence, 50–100 ms padding.

### Cited Findings
- Field benchmark (Dost & Huang 2026): PySceneDetect on 50 high-performing informative talking-head TikToks (top German accounts) → mean 0.37 cuts/s (SD 0.17; range 0.13–0.82). 16th/50th/84th percentiles = 0.189 / 0.331 / 0.517 cuts/s (≈ one cut per 5.3 s / 3.0 s / 1.9 s) — [Dost & Huang, Marketing Trends Congress 2026 (PDF)](https://archives.marketing-trends-congress.com/2026/pages/PDF/paper_professor_DOST_HUANG.pdf)
- Same paper, 2×3+control experiment (N=242, 20-s educational talking-head clip edited in CapCut, sound on): "seamless" jump cuts (silence/error removal preserving natural micro-gaps and word onsets) increased liking vs overlapping and unedited — but only at medium/high frequency; "overlapping" cuts (clipping onsets, erasing micro-pauses) increased sustained engagement (keep watching/rewatch) at low–medium pace, and that edge eroded at high frequency; higher pacing reduced sustained engagement overall. Recommendations: anchor pace near real-world medians, prefer seamless at medium pace for likes, don't chase speed. Limitations: convenience sample, self-reported intentions, single topic — [Dost & Huang 2026](https://archives.marketing-trends-congress.com/2026/pages/PDF/paper_professor_DOST_HUANG.pdf)
- Practitioner cadence claims (convention, vendor sources): visual change every 2–5 s for 20–60 s educational shorts (a new cut needn't be new footage) — [CapCut](https://www.capcut.com/create/b-roll-pacing-educational-videos); cut every 1–2 s for Reels — [Creatorflow](https://creatorflow.so/blog/b-roll-instagram-reels/); "top-performing Shorts average a cut every 2–4 seconds" (unsourced) — [OpusClip](https://www.opus.pro/blog/ai-b-roll-generator)
- An unsourced vendor claim of cuts every 0.5–1 s on Reels exists but could not be traced to a study — [Vidpros](https://vidpros.com/video-clip-length/) (unverified; contradicted by the 0.13–0.82 cuts/s field data above)
- Jump cuts come from trimming pauses, filler and mistakes in same-framing footage; a digital punch-in, alternate angle or meaningful framing change makes the edit read as a new shot rather than an accidental skip; zooms that add motion without supporting the point are discouraged — [Klap](https://klap.app/blog/jump-cut-definition); [Creator Essentials](https://www.creatoressentials.com/glossary/jump-cut/)
- Silence removal starting values (vendor tools): threshold −30 dB default — [AutoCut](https://www.autocut.com/en/blogs/autocut-silences-parameters/); −30 dB with 0.3 s minimum — [Blitzcut](https://blitzcutai.com/blog/how-to-remove-silence-youtube-shorts); some list −40 to −50 dB; −45 dB with 0.1 s minimum "chops the tails off words"; 0.25–0.4 s minimum silence for Shorts (shorter clips breaths); 0.3–0.5 s for natural YouTube-style speech; padding 0.05–0.1 s each side (one tool default 50 ms) — [Cutback](https://cutback.video/guide/silence-remover); [DaVinci Resolve Club](https://davinciresolveclub.com/remove-silence-davinci-resolve.md); [Auto-Editor fork README](https://github.com/MartinAparicioPons/Auto-Editor)
- Video length context (not pacing): Buffer's 1.1M-TikTok analysis — 33.7% of videos are 10–30 s, 27.3% 30–60 s, 22.2% 5–10 s; >1-min videos got 70.3% more reach than 10–30 s; FanpageKarma (32k videos) found 0–10 s highest reach per follower but 30–90 s highest interaction rate — [Socialinsider](https://socialinsider.io/blog/how-long-are-tiktok-videos); [Fanpage Karma](https://www.fanpagekarma.com/insights/optimal-tiktok-video-length/)

### Inferences
- Encodable default: target 0.30–0.40 visual changes/s (one every 2.5–3.3 s), floor 0.19/s, ceiling ~0.52/s for talking heads; "visual change" = cut, punch-in/out, B-roll, graphic or caption-layout change.
- Use "seamless" silence removal by default (keep ~50–100 ms of pre-roll before word onset and a short tail; don't clip consonant onsets). Reserve tight "overlapping" cuts for a few emphasis moments.
- Punch-in convention: no source found with measured zoom percentages. Common practice (unverified) alternates between 100% and ~110–125% scale on alternate jump cuts to disguise them; implement as alternating framing so consecutive cuts never share the same scale. Mark as convention.
- J/L cuts: no short-form-specific numbers found; standard editing practice is letting audio lead/trail the picture change by a few frames to ~0.5 s, mostly at B-roll entries/exits. Convention only.

### Gaps
- No primary source for punch-in zoom percentages or frequency, or for J/L-cut offsets in short-form.
- No large-scale shot-length dataset (Cinemetrics-style) for Reels/Shorts was found beyond the 50-video German TikTok benchmark.

## 3. Captions: style, words per line, size, stroke, highlight, placement, reading speed

### Takeaway
Short-form convention is 2–5 words per caption chunk, bold sans (often uppercase), white with black stroke/shadow, one accent color for the active/key word, placed horizontally centered roughly 60–80% down the frame. Size guidance from vendors is ~60–80 px on 1080×1920 (≈3–4% of frame height); quoted "7–9% of frame height" figures are arithmetically inconsistent. Netflix caps reading speed at 17–20 cps for adults (42 chars/line, 2 lines, ≥5/6 s duration).

### Cited Findings
- Words per caption: 3–5 words sweet spot; 2–4 for fast/energetic; 5–7 for slow/narrative; never more than one full sentence on screen — [OpenClip caption styling guide](https://openclip.app/guides/caption-styling-guide)
- Hormozi-style conventions (vendor reconstructions, not from Hormozi's team): Montserrat Black (900) all caps, white base, key words flashing yellow/green/red; one vendor uses yellow #f7c204; max 4–6 words over 2 lines vs. 2–4 words at a time in another; split the caption exactly when the keyword is spoken and make the keyword bigger; CapCut recipe: white text + black outline, highlight word +2–4 pt — [Submagic](https://www.submagic.co/blog/how-to-make-alex-hormozi-captions); [SendShort](https://sendshort.ai/guides/hormozi-captions/); [OpenClip Hormozi](https://openclip.app/use-cases/hormozi-style-captions); [Lilys CapCut notes](https://lilys.ai/en/notes/create-youtube-shorts-20260115/capcut-hormozi-captions)
- Colors: limit to two (base + one highlight on the currently spoken word); pairings white/yellow, white/cyan, black/yellow — [OpenClip](https://openclip.app/guides/caption-styling-guide)
- Size on 1080×1920: 60–75 px standard, 48–55 px minimum readable, 100 px hard maximum, 80–90 px for bold/motivational — [Blitzcut TikTok](https://blitzcutai.com/blog/best-caption-size-tiktok-2026); [Blitzcut Reels](https://blitzcutai.com/blog/best-caption-size-instagram-reels-2026). OpenClip states "7–9% of frame height ≈ 60–80 px" — internally inconsistent (60–80 px is 3.1–4.2% of 1920; 7% would be ~134 px) — [OpenClip](https://openclip.app/guides/caption-styling-guide)
- Stroke/shadow: 3–4 px black stroke on white text, optional soft drop shadow 2–3 px offset at 30–50% opacity — [Blitzcut professional captions](https://blitzcutai.com/blog/how-to-make-captions-look-professional); shadow 2–4 px blur, or semi-transparent box at 50–70% opacity on busy/light backgrounds — [OpenClip](https://openclip.app/guides/caption-styling-guide)
- Placement: horizontally centered, vertically 65–85% down the frame, keep captions in one zone (not jumping between thirds) — [OpenClip](https://openclip.app/guides/caption-styling-guide); start lower-middle, 1–2 short lines, don't cover mouth/eyes, keep off the right rail and bottom metadata zone — [Reap](https://reap.video/blog/short-form-video-safe-zones); keep captions >300 px above the bottom on Shorts — [Blitzcut Shorts](https://blitzcutai.com/blog/best-caption-size-youtube-shorts-2026)
- Netflix general requirements: subtitle event min 5/6 s, max 7 s; max 2 lines, keep to one line when possible — [Netflix Timed Text: General Requirements](https://backlothelp.netflix.com/hc/en-us/articles/215758617)
- Netflix Arabic guide (and representative of Latin-script guides): 42 characters per line; reading speed up to 20 cps adult / 17 cps children (SDH 23/20); bottom-heavy pyramid line shape; avoid a single orphan word on line 2 — [Netflix Arabic Timed Text Style Guide](https://partnerhelp.netflixstudios.com/hc/en-us/articles/215517947); Indonesian guide: 17 cps adult / 13 cps children — [Netflix Indonesian guide](https://backlothelp.netflix.com/hc/en-us/articles/216009727)
- Testing on a real phone is stressed, because desktop previews hide UI overlap — [Blitzcut](https://blitzcutai.com/blog/best-caption-size-tiktok-2026)

### Inferences
- Encodable caption defaults (1080×1920): bold sans 900 weight, 64–80 px cap size (≈3.3–4.2% of H), 1–2 lines, ≤ ~20 characters per line in big-caption styles (2–4 words), white fill + 3–6 px black stroke (≈5–8% of font size) + soft shadow; highlight active word with one accent color and/or 105–115% scale; centered baseline at ~65–75% of height (above the ~25–35% bottom UI band).
- Reading-speed guard: since word-by-word captions are synchronized with speech, cps is bounded by speech rate; for phrase captions enforce ≤17–20 cps and ≥~0.8 s on screen (Netflix 5/6 s floor) — merge chunks that would flash shorter.
- Break chunks at syntactic boundaries (Netflix "do not split" rules: subject/verb, adjective/noun, preposition/noun, number/counted noun).

### Gaps
- No source gave measured legibility data for word-by-word vs phrase captions in vertical video, nor active-word animation timings (pop duration/scale). MrBeast-style caption specs were not found in a citable form.
- BBC subtitle guidelines (WPM, size as % of screen height) could not be fetched (site blocked).

## 4. Platform safe zones for 9:16 (2025–2026)

### Takeaway
None of TikTok, Instagram or YouTube publish an official organic-post safe-zone in pixels in a form I could retrieve; all numbers are third-party measurements of current UI and they disagree, especially on the bottom (≈270–670 px). A conservative cross-platform box on 1080×1920 is roughly: top ≥ ~130–290 px, bottom ≥ ~480 px, right ≥ ~150 px (more below mid-height on TikTok), left ≥ ~60–110 px.

### Cited Findings
- Reap (Sep 2026) conservative cross-platform recommendation (explicitly "not official"): top ~15% (~288 px), bottom ~25–35% (~480–672 px), left/right ~10–15% (~108–162 px); Reels takes more width on the right, Shorts adds a bottom progress bar — [Reap](https://reap.video/blog/short-form-video-safe-zones)
- Measured estimate: ~130 px top, ~480 px bottom, ~150 px right → usable ~870×1310 center; another checker lists 150 top / 270 bottom / 40 sides — [Reap / search summary](https://reap.video/blog/short-form-video-safe-zones); [AdaptlyPost checker](https://adaptlypost.com/en/free-tools/safe-zone-checker)
- TikTok (ads, third-party): right ≈140 px for the icon column, bottom ≈480–560 px (account name, description, CTA), top ≈130 px; TikTok provides an official template in Creative Center (not retrieved) — [Spilno Agency](https://spilnoagency.com.ua/en/tiktok-ads); bottom 367 px and top 131 px per another tool — [AI Carousels](https://www.aicarousels.com/free-tools/tiktok-safe-zone-checker); right margin widens to ~300 px below y≈840, and older 250/320/420 px bottom figures reflect pre-CTA-row UI — [AdMakeAI](https://admakeai.com/free-tools/safe-zone-checker)
- Meta Reels (brand design-system reference): 48 px side margins, 260 px top type-safe inset at 1080×1920 — [Canadian Tire brand design system](https://brand-qa2.canadiantire.ca/en/ctr/design-system/omni-channel/motion/margins-and-safe-space.html)
- Roughly the top tenth of frame holds status bar and platform toggles — [Kineclip](https://kineclip.com/blog/vertical-video-dimensions-and-safe-zones-2026/)

### Inferences
- Encode a single "union" safe box (to satisfy all three platforms from one export): x ∈ [~90, ~900] px (right margin larger, ≥180 px, in the lower half), y ∈ [~250, ~1440] px. Put faces/eyes ~30–45% down the frame and captions ~65–75% down so they sit above the bottom UI band.
- Treat these as versioned config, since UI overlays change.

### Gaps
- Official TikTok Creative Center / Meta / YouTube safe-zone templates were not retrieved; values above are unverified approximations.

## 5. Typography and layout: sizes, contrast, plates, element count

### Takeaway
Use heavy-weight sans type ≥ ~48 px on 1080×1920, high contrast (WCAG ≥4.5:1, ≥3:1 for large text is the standard accessibility floor), and add a stroke, shadow or semi-opaque plate when the background is busy or light. No measured source on max elements on screen was found; convention is one text focus at a time.

### Cited Findings
- WCAG 2.2 SC 1.4.3 Contrast (Minimum): 4.5:1 for normal text, 3:1 for large text — [W3C WCAG 2.2](https://www.w3.org/TR/WCAG22/#contrast-minimum)
- Minimum readable caption 48–55 px on 1080×1920; avoid thin/light weights; bold sans-serif — [Blitzcut](https://blitzcutai.com/blog/best-caption-size-tiktok-2026); [OpenClip](https://openclip.app/guides/caption-styling-guide)
- Plates: talking-head on simple backgrounds → shadow usually enough; busy backgrounds → semi-transparent box (50–70% opacity) — [Blitzcut](https://blitzcutai.com/blog/how-to-make-captions-look-professional); [OpenClip](https://openclip.app/guides/caption-styling-guide)
- Keep captions in one zone and limit to two colors — [OpenClip](https://openclip.app/guides/caption-styling-guide)
- Netflix: font size must allow 42 chars across the screen; white text — [Netflix Arabic guide](https://partnerhelp.netflixstudios.com/hc/en-us/articles/215517947)

### Inferences
- Hierarchy for an auto-editor: max one headline/hook text block + one caption block + at most one graphic/icon simultaneously; headline 1.4–2× caption size. (Convention; no measured source.)
- Auto-check contrast of text vs sampled background luminance; if < 4.5:1, add stroke or plate automatically.

### Gaps
- No empirical source on the maximum number of on-screen elements for short-form; no measured minimum px for mobile vertical video from a standards body.

## 6. Motion design principles: easing, durations, staggering

### Takeaway
UI-motion specs give the best numbers: entrances use decelerate (ease-out) curves, exits accelerate; small moves 150–250 ms, large moves 250–500 ms; Material 3 emphasized-decelerate (0.05, 0.7, 0.1, 1.0) at ~400 ms for elements entering. 100 ms reads as "instant," ~1 s is the limit of flow.

### Cited Findings
- Material 3 emphasized decelerate curve = cubic-bezier(0.05, 0.7, 0.1, 1.0) — [Flutter API: Easing.emphasizedDecelerate](https://api.flutter.dev/flutter/material/Easing/emphasizedDecelerate-constant.html); Material 2-era easeOut = cubic-bezier(0.0, 0, 0.2, 1) — [MUI transitions source](https://app.unpkg.com/@mui/material@9.2.0/files/styles/createTransitions.mjs)
- M3 duration tokens run from 50 ms (short1) to 1000 ms (extraLong4); emphasized-decelerate enter ~400 ms, standard-decelerate ~250 ms (third-party token doc) — [KPay Flutter motion doc](https://design.kpay-group.com/docs/motion/type/flutter-type/flutter-intro)
- Material (M1) guidance: desktop animations 150–200 ms; tablet ~30% longer than mobile — [Material M1 Duration & easing](https://m1.material.io/motion/duration-easing.html)
- Material customization: standard easing + short duration = functional; emphasized easing + long duration = stylized/expressive (sample 650 ms) — [Material customization](https://material.io/design/motion/customization.html)
- SAP Fiori: animations 0–500 ms; small moves 150–250 ms; large moves 250–500 ms; cites NN/g 0.1 s as the "direct causation" limit — [SAP Fiori Motion Design](https://www.sap.com/design-system/fiori-design-web/v1-120/foundations/visual/motion-design)
- NN/g-derived: 100 ms feels instant, 1 s is the upper limit for flow; 500 ms ceiling for UI animation is an inference (half of 1 s); mobile sweet spot 200–300 ms — [Val Head](https://valhead.com/?p=2978); [Parachute Design](https://parachutedesign.ca/blog/ux-animation/)

### Inferences
- Encodable motion defaults for social graphics at 30/60 fps: text/sticker entrances 200–350 ms with ease-out (M3 emphasized-decelerate or cubic-bezier(0.0,0,0.2,1)); exits 150–200 ms with ease-in (exits ~25–35% shorter than entrances); overshoot "pop" (scale 0 → 110% → 100%, back-out curve) for emphasis words/emojis; per-word/letter stagger 30–60 ms for kinetic type. These are conventions extrapolated from UI specs; social graphics typically run at the expressive end (longer/overshoot) of UI ranges.
- Active-word highlight should change within ≤100 ms of the word onset to read as synchronized (NN/g instant threshold).

### Gaps
- No School of Motion or kinetic-typography source with quantified durations/stagger was retrieved; official M3 token page did not render. Values for standard (0.2,0,0,1) and emphasized-accelerate curves are from memory of the M3 spec and should be verified before encoding.

## 7. B-roll, graphics, emojis and visual metaphors

### Takeaway
Conventions for talking-head shorts: ~3–6 cutaways of 2–4 s each per 60 s, returning to the face between them, triggered by specific claims, numbers or products rather than blanket coverage. All sources here are vendors; no measured study found.

### Cited Findings
- 3–6 cutaways of 2–4 s each in a 60-s talking-head clip, face on camera between them; long-form interviews by contrast cut away every 20–40 s — [CapCut B-roll pacing](https://www.capcut.com/create/b-roll-pacing-educational-videos); [Argil](https://argil.ai/blog/what-is-b-roll-the-complete-guide-for-video-creators-in-2026)
- Reels B-roll clips of 2–5 s; cutaways reset attention and hide jump cuts; cut to B-roll on a specific claim, number or product — [Creatorflow](https://creatorflow.so/blog/b-roll-instagram-reels/); [Cutback A/B/C-roll](https://cutback.video/blog/a-roll-vs-b-roll-whats-the-difference)
- A widely repeated "talking-head retention drops after 10–15 s" claim is attributed only to unnamed internal data — [Viberoll checklist](https://viberoll.questera.ai/learn/talking-head-video-editing-checklist/) (unverified)

### Inferences
- Encodable trigger rules: insert B-roll/graphic when the transcript contains a concrete noun/number/named entity/product/screen action; length 2–4 s; never two cutaways back-to-back without returning to the face (unless voice-over-only); B-roll share ≈ 15–35% of runtime for talking heads, higher for pure voice-over.
- Emoji/icon stickers act as low-cost "visual changes" that count toward the 0.3–0.4 changes/s budget.

### Gaps
- No sourced data on emoji/meme frequency, stock-footage source preferences, or screen-recording usage in high-performing shorts.

## 8. Color grading and lighting for social talking heads

### Takeaway
Skin should sit on the vectorscope skin-tone (I-) line between red and yellow regardless of ethnicity; recommended workflow is white balance/exposure → skin on line → gentle S-curve contrast → vibrance rather than global saturation; judge on a phone-size preview.

### Cited Findings
- Human skin of all ethnicities falls along a red–yellow diagonal on the vectorscope (the skin-tone line); sample a non-made-up skin area (neck/arm) by cropping to it — [Adobe Premiere Pro: correct skin tones](https://helpx.adobe.com/ro/premiere-pro/how-to/correct-skin-tones.html); [Larry Jordan](https://larryjordan.com/?p=148592)
- Skin saturation reference ranges (Alexis Van Hurkman via Larry Jordan): roughly 35–40% for Caucasian, 30–45% male Hispanic, 15–35% Black subjects — "guidelines, not absolutes"; a commenter argues ~30% looks better on calibrated Rec.709 — [Larry Jordan](https://larryjordan.com/?p=148592)
- Contrast first, then saturation; use S-curve but avoid steep curves that break footage; vibrance boosts muted colors without making skin unnatural; oversaturation looks unnatural — [Uppbeat](https://fastly-f.uppbeat.io/blog/motion-graphics/color-grading/how-to-color-grade-your-videos); [Storyblocks](https://www.storyblocks.com/resources/?p=26160)

### Inferences
- Auto-grade pipeline: auto white balance → exposure normalization (face luminance ≈ 55–70 IRE is a common broadcast convention, unverified here) → hue-rotate skin cluster toward the skin-tone line (~123° in the YUV vectorscope convention, unverified) → mild S-curve → vibrance +5–15. Clamp skin saturation within the Van Hurkman ranges.

### Gaps
- No social-specific grading data (e.g., whether higher saturation/contrast measurably improves retention) was found; lighting-compensation numbers not found.

## 9. Persian / RTL caption and typography considerations

### Takeaway
No Persian-specific short-form caption guide was found; the Netflix Arabic Timed Text Style Guide is the closest authoritative RTL/Arabic-script source (42 chars/line, 20 cps adult, no italics, kashida handling, specific line-break no-split rules). Vazirmatn (OFL) is the de-facto open Persian UI typeface and its maintainers note Arabic-script fonts have taller vertical footprints than Latin.

### Cited Findings
- Netflix Arabic: 42 chars/line, 2 lines max (one line unless >42 chars), 20 cps adult / 17 children (SDH 23/20), bottom-heavy pyramid, avoid a lone word on line 2; do not break between subject/verb, particle/verb, adjective/noun, construct (مضاف/مضاف‌إليه), preposition/noun, number/counted noun — [Netflix Arabic Timed Text Style Guide](https://partnerhelp.netflixstudios.com/hc/en-us/articles/215517947)
- Netflix Arabic: italics are not used; white proportional sans (Arial placeholder); no space before punctuation; don't combine ?! ; use U+2026 ellipsis; use kashida (U+0640) before quotes/brackets following "ال"; don't put Latin letters for URLs/emails in subtitles; transliterate acronyms; examples use Western digits (guide doesn't rule on Arabic-Indic vs Western) — [Netflix Arabic guide](https://partnerhelp.netflixstudios.com/hc/en-us/articles/215517947)
- A Netflix Persian (Farsi) guide exists on the partner help center but was not retrievable (404 at the tried URL) — [Netflix partner help (search)](https://backlothelp.netflix.com/hc/en-us/articles/215758617)
- Vazirmatn: "simple and legible Persian/Arabic typeface" for web/apps, based on DejaVu Sans with Roboto Latin; OFL since v27; Arabic-script fonts have larger vertical footprints (ascenders/descenders/diacritics) and a reduced-height UI variant exists — [Vazirmatn docs](https://docsearch.algolia.com/mcp/docs/repo/rastikerdar/vazirmatn); W3C i18n thread: Persian baseline/ascender ratios differ from Latin, causing uneven line heights in mixed text — [W3C i18n list](https://lists.w3.org/Archives/Public/public-i18n-translation/2025AprJun/0009.html)

### Inferences
- For Persian captions: use a Persian-designed face (Vazirmatn, or heavy-weight Persian display faces) rather than Montserrat-style Latin fonts; no uppercase concept exists, so emphasize via weight, color and scale instead of ALL CAPS; never render word-by-word by splitting a word's letters (cursive joining) — animate whole words; ensure ZWNJ (U+200C, نیم‌فاصله) is preserved in tokenization so compound words aren't split across caption chunks; increase line height (~1.4–1.6×) vs Latin; right-align/centered with bidi isolation for embedded Latin/numbers; choose one digit system consistently (Persian ۰–۹ typical for Persian audiences — convention, not sourced).
- Word-by-word highlight order must follow RTL reading direction; slide/stagger animations should enter from right to left.
- Persian words are on average shorter in characters but taller; budget ~16–20 cps as with Arabic.

### Gaps
- No Persian-specific short-form caption study, Persian Netflix guide text, or legibility research on Arabic-script captions at mobile sizes was retrieved. Digit-system and ZWNJ guidance above is inference/convention.
