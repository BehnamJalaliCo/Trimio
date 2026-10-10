# Professional Audio for Short-Form Social Video (Reels / TikTok / Shorts): Voice, Music Beds, Ducking, SFX, Beat Sync, Loudness

Scope note: these notes come from about 16 search and fetch calls (Oct 2026). Many available sources are vendor or creator blogs, not platform documentation or standards bodies. The confidence of each claim is marked. Where no source gave a number, it is listed under Gaps instead of being invented.

---

## 1. Loudness targets per platform, true peak, dialogue normalization, how platforms normalize

### Takeaway
None of TikTok, Instagram Reels or YouTube Shorts publishes an official integrated-LUFS delivery target. The working convention for 2025–2026 is about **-14 LUFS integrated, true peak ≤ -1 dBTP**. A more conservative variant is -16 LUFS / -1 dBTP. Masters louder than about -12 LUFS gain nothing and risk being turned down and sounding smaller. Speech-led content in audio-only standards sits about 2 dB lower (-18 LUFS in AES TD1008) than music (-16 LUFS).

### Cited Findings
- Consensus from third-party guides: about -14 LUFS integrated with a true-peak ceiling of about -1 to -1.5 dBTP for Shorts, TikTok and Reels. One guide lists exactly -14 LUFS / -1.0 dBTP max for all three. — [ClickyApps LUFS targets 2025](https://clickyapps.com/creator/video/guides/lufs-targets-2025); [VloggingPro loudness helper](https://vloggingpro.com/tools/audio-loudness/)
- Some guides give a range: -14 to -16 LUFS integrated, or an "acceptable" range of -12 to -16 LUFS. — [InfluenceFlow 2026 specs guide](https://influenceflow.io/resources/the-ultimate-social-media-video-specs-guide-2026-edition/)
- Conflicting claim: OpusClip says Instagram and TikTok "prefer louder mixes closer to -12 to -10 LUFS" while YouTube targets about -14. No official source supports it. — [OpusClip blog](https://www.opus.pro/blog/best-loudness-normalizers); contradicted by [APU Software](https://apu.software/tiktok-instagram-reels-loudness/) and [Mr. Vocal](https://mrvocal.com/posts/loudness-for-shorts)
- APU Software states that "TikTok and Instagram do not publish -16 LUFS as a universal delivery requirement". It recommends **-16 LUFS integrated / -1 dBTP max** as a *conservative production convention* for mobile intelligibility and codec headroom, not as a platform rule. It notes that platforms transcode audio and can change playback behavior over time. — [APU Software](https://apu.software/tiktok-instagram-reels-loudness/)
- A TikTok-focused guide says TikTok has not published an official loudness standard. — [MagicMaster TikTok mastering](https://magicmaster.pro/blog/tiktok-mastering-loudness)
- A practitioner who mixes about 200 short-form tracks a year recommends -14 LUFS / -1.0 dBTP. He says -7 to -9 LUFS masters are "counterproductive" and that his -8 LUFS master sounded smaller after platform processing. In his experience YouTube Shorts is the strictest, Reels tolerates "a touch more" loudness, and genre-heavy music (hyperpop, drill) can go to about -12 LUFS. Verification method: upload a private draft, screen-record phone playback, measure the rendered LUFS, and match within 1 dB of a reference. *(Experience-based, no platform documentation cited.)* — [Mr. Vocal, Loudness for Shorts](https://mrvocal.com/posts/loudness-for-shorts)
- AES TD1008 (2021, audio-only streaming) recommends a distribution loudness of **-18 LUFS for speech-only and "assorted" content** (podcasts mixing speech, music and FX), interstitials and ads. For music it recommends **-16 LUFS**, or the loudest track at -14 LUFS for album normalization. Speech is often balanced 2–3 dB quieter than full-band music. Most streaming services still normalized to about -14 LUFS as of 2021, and Spotify lowered its default from -11 to -14 LUFS. — [Production Advice on TD1008](https://productionadvice.co.uk/td1008/)
- TD1008 is explicitly *not* intended for sound-with-picture content. — [Telos Alliance, Understanding Loudness for Streaming](https://docs.telosalliance.com/docs/understanding-loudness-for-streaming-audio); [Mix Online](https://www.mixonline.com/sfp/aes-makes-streaming-loudness-recommendations)
- YouTube switched to LUFS-based normalization, with a widely reported reference of about -14 LUFS. Production Advice links a page titled with a "loudness reference to 14 LUFS". *(YouTube has no official documentation of the value.)* — [Production Advice](https://productionadvice.co.uk/td1008/)
- True-peak ceiling: EBU R128 distribution practice recommends **-3 dBTP** maximum true peak for lossy-codec delivery. That is stricter than the -1 dBTP in the 2015 AES streaming guidance, and one commentator argues -3 dBTP is the better choice for lossy codecs. — [Grimm Audio on TD1008](https://www.grimmaudio.com/news/aes-td1008-ends-the-loudness-war/)

### Inferences
- **Recommended default for an automatic editor:** normalize the final mix to **-14 LUFS integrated (±1 LU)** with a **true-peak limiter at -1.0 dBTP**. Use -1.5 to -2 dBTP if the export goes through AAC at ≤128 kbps, because AAC/Opus encoding can produce inter-sample overs. Offer a "conservative/speech" preset at -16 LUFS. Never exceed about -12 LUFS.
- Platforms appear to turn loud content down, and it is unclear whether they turn quiet content up. So a mix at -18 LUFS or quieter will probably play quieter than neighboring videos in the feed. -14 is the safe middle.
- For voice-led content, measure the **dialogue-gated loudness** of the voice stem in the -16 to -14 LUFS range (dialogue-anchored normalization). The music and SFX are then balanced relative to the voice, as in sections 3 and 4.
- Use a two-pass loudness measurement such as FFmpeg `loudnorm` two-pass (I=-14, TP=-1, LRA≈7–11). It gives linear gain plus limiting rather than dynamic gain riding. *(This is a tooling inference. LRA values for social content were not found in sources.)*

### Gaps
- No official LUFS or true-peak numbers were found from TikTok, Meta (Instagram/Facebook Reels) or YouTube (Shorts) documentation. All platform numbers are community-measured or conventional.
- It is unconfirmed whether TikTok or Reels apply upward gain (turning quiet content up) or only attenuation.
- No source was found that specifies a loudness range (LRA) target for social video.
- EBU R128 broadcast (-23 LUFS) and ATSC A/85 (-24 LKFS) are well known but were not fetched in this session. They are broadcast-TV targets and not relevant to social delivery except as references.

---

## 2. Voice chain: noise reduction, EQ, de-essing, compression, AI speech enhancement, room tone and crossfades at jump cuts

### Takeaway
The standard podcast and VO chain is **HPF (80–100 Hz) → noise reduction → corrective EQ → compression (2:1–3:1, threshold about -18 to -24 dB) → de-esser (4–8 kHz) → limiter → loudness normalize**. AI enhancers like Adobe Enhance Speech v2 should be blended, not used at 100%, because high strength sounds robotic. Jump-cut joins need short crossfades of a few ms to about 10 ms at zero crossings. Use longer crossfades or room-tone fill when the noise floor differs between the two sides.

### Cited Findings
- High-pass filter: most guides use **80–100 Hz**, with 80 Hz for deeper voices and 100 Hz for higher voices. One recommends a **12 dB/octave slope at 100 Hz** per vocal channel. — [Ruah Creative House, podcast mixing](https://ruahcreativehouse.org/blog/podcast-audio-mixing/); [B&H podcast team tips](https://www.bhphotovideo.com/explora/pro-audio/tips-and-solutions/7-audio-tips-from-the-bh-podcast-team); [Tella HPF definition](https://www.tella.com/definition/high-pass-low-pass-filter)
- Compression: ratio **2:1 or 3:1**, threshold about **-18 to -24 dB**. This is a starting point, not a standard. — [Streamline Feed, Audacity voice settings](https://streamlinefeed.co.ke/news/four-free-audacity-settings-that-deliver-studio-quality-voice-today)
- De-esser band: sibilance typically sits between **5 and 8 kHz** per one glossary, or **4–7 kHz** per RØDE. Over-de-essing causes a lisping, unnatural sound. — [IRPR Sound glossary](https://sounddesign.irpr.agency/glossary/de-esser/); [RØDE, audio processing for podcasting](https://rode.com/en/blog/all/audio-processing-and-fx-for-podcasting)
- Chain order commonly given: HPF → noise reduction → EQ → compression → de-esser → limiter → loudness normalization (that guide targets -16 LUFS). Another source argues for de-essing after compression and EQ, because those stages can exaggerate sibilance. — [Ruah Creative House](https://ruahcreativehouse.org/blog/podcast-audio-mixing/); [IRPR Sound](https://sounddesign.irpr.agency/glossary/de-esser/)
- Adobe Enhance Speech v2: Adobe describes the **Strength** slider as balancing speech clarity against ambient noise, with no recommended value. A Nov 2025 forum post describes a newer two-slider UI (Voice / Background) with a **default of 90/10**. Users report that high settings sound robotic and suggest about 70% instead of 100%. v2 also removes laughter. Early v2 (Dec 2024) had a bug where 1% sounded like 100%, which Adobe said it fixed. *(Forum and anecdotal.)* — [Adobe Community: Enhance Speech v2 is here](https://community.adobe.com/t5/adobe-podcast-discussions/enhance-speech-v2-is-here/td-p/14992650/highlight/true/page/2); [Adobe Community: v2 strength](https://community.adobe.com/t5/adobe-podcast-discussions/enhance-speech-v2-strength/td-p/15022192); [Adobe Community: right settings for Enhance v2](https://community.adobe.com/questions-514/the-right-settings-for-enhance-v2-1498738)
- Clicks at edits come from cutting where the waveform is away from zero. Fixes are to move the cut to a zero crossing and add a fade of **a few milliseconds**. An Audacity forum user reports needing a **~10 ms** crossfade to avoid clicks. — [Mubert Cast, click where I cut](https://mubert.com/tools/cast/docs/click-where-i-cut); [Audacity forum](https://forum.audacityteam.org/t/making-cut-paste-edits-in-my-audio-creates-artificial-clicks/58596)
- A join between two different room tones causes a "bump". Fix it with a longer crossfade or matched room tone under the join. For similar room tones, use a short crossfade and match levels. Don't cover every pop with a long fade, because that dulls consonants and transients. Start with the smallest fade that works. If a click persists, the cut may fall inside a consonant or breath, so nudge the cut into the silence between words. — [IRPR Sound, dialogue popping on edits](https://sounddesign.irpr.agency/solutions/dialogue-popping-on-edits/); [IRPR Sound, dialogue edit points audible](https://sounddesign.irpr.agency/solutions/dialogue-edit-points-audible/); [Mubert Cast, what is crossfade](https://mubert.com/tools/cast/docs/what-is-crossfade)

### Inferences
- **Implementable defaults:**
  - HPF at 80 Hz (male or low voice) to 100 Hz (female or high voice), 12–18 dB/oct.
  - Optional low-mid cut of 2–3 dB around 200–400 Hz for boxiness and a +2–3 dB presence shelf or bell at 3–5 kHz. *(General practice. No session source gave these exact numbers.)*
  - Compressor at 3:1, attack 5–15 ms, release 50–150 ms, aiming for 3–6 dB of gain reduction. *(Attack, release and gain-reduction figures were not sourced in this session.)*
  - De-esser centered at 5–7 kHz, with ≤4–6 dB of reduction.
- AI enhancement (Adobe Enhance, or open models such as DeepFilterNet or Resemble Enhance) should run first, before EQ and compression, and be wet/dry blended at about 60–90% wet to avoid "robot" artifacts. *(Open-model names are an inference. Their licenses were not verified this session.)*
- Jump-cut join rule for an auto-editor:
  1. Snap each cut to the nearest zero crossing within ±5 ms.
  2. Apply an equal-power crossfade of 5–10 ms by default.
  3. If the noise floor differs by more than about 3 dB across the cut, extend to 20–40 ms, or lay a continuous room-tone or noise bed under the voice track.
  4. Keep breaths only if they are under about 150–200 ms, or attenuate them by 6–10 dB.

  *(The 20–40 ms and breath thresholds are inference, not sourced.)*

### Gaps
- Compressor attack and release, presence-boost frequencies and AI-enhancer wet/dry percentages had no authoritative 2023–2026 source in this session.
- No official Adobe documentation of recommended Enhance Speech strength values was found.

---

## 3. Music: level under dialogue, ducking, choosing by mood and BPM, cutting music to the edit, royalty-free sources and licensing

### Takeaway
No universal "music = X dB under voice" standard exists. The only hard benchmark is the WCAG 2.2 AAA accessibility criterion: background **≥ 20 dB below foreground speech**. Creator-tool guidance for ducking is **6–12 dB** of reduction (up to 15–25 dB in some guides), **attack 10–80 ms, release 200–700 ms**. Licensing for bundling is the hardest part: Pixabay and Sonniss allow use in projects but forbid standalone redistribution, and MusicGen weights are non-commercial.

### Cited Findings
- No general rule: an experienced post engineer said you can't state "music should be X dB below dialogue" as a general rule (2006 thread, old). — [Creative Cow forum](https://creativecow.net/?p=2139815)
- WCAG 2.2 SC 1.4.7 (Level AAA): in speech-primary prerecorded audio, background sounds must be **at least 20 dB lower than foreground speech**, or absent, or able to be turned off. — [WCAG 1.4.7 summary](https://www.disabilityworld.org/toolkit/standards/wcag/1-4-7-low-or-no-background-audio/)
- Ducking in CapCut's guide: starting depth **6–12 dB**, attack **~30–80 ms**, release **~250–700 ms**, to keep speech clear without pumping. — [CapCut audio ducking guide](https://www.capcut.com/create/audio-ducking-for-clear-dialogue-in-video)
- Ducking in OpenClip's guide: depth **~15–25 dB**, attack **10–30 ms**, release **200–500 ms**, with music fading back in gradually. — [OpenClip, audio ducking explained](https://openclip.app/learn/audio-ducking)
- Sidechain-ducking parameters: -6 to -12 dB is a "gentle dip" and -30 dB or more nearly removes the music. A short release recovers between words but can pump on continuous music. A longer release holds the music down across pauses. — [Vindral Composer docs, sidechain ducking](https://composer-docs.vindral.com/operators/operators-sidechain-ducking.html)
- Premiere's auto-ducking requires the voice clip to be tagged as a Dialogue track type. — [Adobe Community](https://community.adobe.com/t5/premiere-pro-discussions/help-with-audio-settings-for-voice-over-and-music/m-p/12039621/highlight/true)
- Licensing:
  - **Pixabay** content is free, needs no attribution and may be modified. It **cannot be sold or distributed on a "Standalone" basis**, meaning in substantially original form with no creative effort applied. — [Pixabay license summary](https://pixabay.com/service/license-summary/)
  - **Sonniss GDC bundles** are royalty-free with no attribution and lifetime use. Individual sounds **cannot be redistributed standalone or in competing SFX libraries**, only embedded in a game, film or application. There is a **no-AI-training** carve-out. *(These are aggregator summaries. The official license is at sonniss.com/gdc-bundle-license.)* — [LicenseOrg Sonniss guide](https://licenseorg.com/guide/music-audio/sonniss); [Cinevva free SFX guide 2026](https://app.cinevva.com/guides/free-sound-effects-music)

### Inferences
- **Implementable music-bed defaults for a voice-led short:**
  - Pre-normalize the music bed so that under speech it sits **15–20 dB below the voice's short-term loudness**. Example: voice at -14 LUFS-S means music at about -30 to -34 LUFS-S while speaking.
  - Between phrases the music can rise to about 6–10 dB below the voice level.
  - With no speech (intro, outro, B-roll montage), music can come up to roughly the program target (-14 to -16 LUFS).

  The 20 dB WCAG figure is the accessibility ceiling. The 6–12 dB creator figures are the minimum audible duck. A professional sound sits in between.
- **Ducking defaults:** sidechain keyed by VAD or the voice envelope, depth 10–15 dB, attack 20–50 ms, release 300–500 ms. Add a hold of about 200–300 ms so the music does not swell in short inter-word gaps.
  - Lookahead is better: since the editor knows the transcript timestamps in advance, start the duck ramp about 100–200 ms *before* the speech onset.
  - Ramp the music back up over about 400–800 ms after the phrase ends.

  *(Hold, lookahead and ramp numbers are inference, consistent with the cited attack and release ranges.)*
- Music selection: tag tracks by mood, energy and BPM. Typical mapping is 70–90 BPM for calm, explainer or emotional content, 100–120 for vlog or lifestyle, and 120–140+ for hype, sports or tech. This is consistent with the 70–140 BPM range in section 5. Mood-to-BPM mapping lacked an authoritative source.
- Endings: cut music to end on a phrase boundary (4 or 8 bars) or on a "button" (final hit or sting) aligned with the last frame or end card. Otherwise use a 0.5–1.5 s fade only if no button exists. *(Craft inference. No numeric source found.)*
- For an app bundle, prefer **CC0** (Kenney audio packs and Freesound's CC0 subset) or self-owned or commissioned music. Pixabay and Sonniss content can probably be *used in exported user videos*. Bundling the raw files inside the app binary may count as "standalone redistribution" and should be cleared legally or by written permission.

### Gaps
- No primary-source (2023–2026) numeric guideline was found for music-bed LUFS under dialogue from broadcasters, Epidemic Sound, Artlist or Soundly.
- Licenses were not fetched in this session for YouTube Audio Library (believed restricted to YouTube use), Free Music Archive, Incompetech (CC-BY), Uppbeat or Epidemic Sound API, and Artlist.
- Content ID false claims on Pixabay music are a known community issue but were not verified this session.

---

## 4. Sound effects: types, layering, levels relative to voice, timing to visual events, density limits

### Takeaway
Pro practice is to match each SFX to the motion it accompanies rather than pasting a generic sweep on every cut:
- **whoosh or swoosh** for movement or camera moves, peaking on the cut
- **riser** leading into a cut or reveal, which can stop dead at the peak
- **impact or hit** for landings
- **pop or click** for UI and text appearances
- **transition sound** for scene changes with no visible moving object

Frame-level timing and density numbers are not documented by authoritative sources. The numbers below are inference.

### Cited Findings
- A convincing whoosh follows the motion's **speed, mass, direction and stopping point**. It should not be a generic sweep on every cut. Mark where the movement accelerates and lands, then shape the attack, body and tail around those points. Use a light swoosh for small fast gestures, an **impact** for landings and a **transition sound** for scene changes with no visible moving object. — [Sonilo whoosh sound effect guide](https://sonilo.com/ai-music/whoosh-sound-effect-guide)
- The whoosh should **peak on the cut**, and its length should match the length of the move. A short whoosh peaking at the edit "carries the eye across the cut". A **riser that stops dead at its peak** leaves silence where the cut lands and can hit harder than a covering whoosh. — [Morphic whoosh sound effect](https://morphic.com/resources/sounds/whoosh-sound-effect)

### Inferences
- **Timing rules for an automatic editor** *(inference, not sourced numerically)*:
  - **Whoosh or swoosh:** align the waveform's *peak* (not its start) to the cut frame. The file therefore starts about 0.2–0.5 s before the cut, depending on asset length.
  - **Riser:** end exactly on the cut or reveal frame, optionally with a hard stop (1–3 frames of silence) before an impact.
  - **Impact, hit, pop or click:** transient on the exact frame of the visual event, or 0–1 frame early (≈0–33 ms at 30 fps). Humans tolerate sound slightly *late* better than early, but a pop for text appearing is usually placed on the first visible frame.
  - **Text pops:** one per text-block entrance, not per word, unless the style is a kinetic per-word "karaoke" caption.
- **Level relative to voice** *(inference)*:
  - Transitional whooshes peak about 6–12 dB below dialogue peaks.
  - UI pops and clicks sit about 10–15 dB below the voice.
  - Impacts on non-speech moments can approach voice level.
  - Never let an SFX transient mask a word: if an SFX overlaps speech, attenuate it an extra 6 dB or shift it into a speech gap.
- **Layering** *(inference)*: a "hit" is often low thump plus mid crack plus high shimmer or tail. A whoosh into an impact into a short reverb tail is a standard transition stack. Keep layers on a bus with gentle compression and an HPF on non-bass layers so the stack doesn't muddy the voice (≤200 Hz content mainly from one layer).
- **Density and fatigue** *(inference)*:
  - Cap transition whooshes at about 1 every 2–3 s on average.
  - Avoid using the same SFX asset twice in a row by randomizing among 3–5 variants and applying ±1–2 semitone pitch or ±1–2 dB gain variation.
  - Keep SFX total under about 15–25% of runtime.
  - Skip SFX on every jump cut. Use them on *visual* events such as zooms, B-roll entries and graphics, not on plain dialogue jump cuts.

### Gaps
- No authoritative source (Soundly, Epidemic or Artlist blogs, post-audio pros) was found in this session giving **frame offsets**, **dB levels relative to voice** or **density limits** for SFX in short-form video. All such numbers above are unsourced inference and should be tuned by listening tests.

---

## 5. Beat sync conventions: cutting on beats and downbeats, syncing text and graphics hits

### Takeaway
Beat interval in seconds = 60 / BPM, and frames per beat = (60 / BPM) × fps; 120 BPM at 30 fps gives 15 frames per beat. Pros don't cut on every downbeat. They follow musical phrases and energy changes, use longer holds in intros and faster cuts at drops or choruses, and mix in syncopated or off-beat cuts to avoid predictability.

### Cited Findings
- Cut interval = 60 / BPM × beat multiple. At 120 BPM a 2-beat cut lands about once per second. At 120 BPM and 30 fps, one beat = **15 frames (0.5 s)**. — [CapCut beat-detection workflow](https://www.capcut.com/create/beat-detection-workflow-goal-compilation-edits); [BeatSync PRO BPM clip finder](https://beatsyncpro.ai/tools/bpm-clip-finder.html)
- Manual workflow: tap markers on each beat while playing, then choose to cut on every beat, two beats per measure, or whatever suits the edit. — [VEGAS Creative Software guide](https://www.vegascreativesoftware.com/in/post-production/how-to-edit-video-footage-to-the-beats-of-music)
- Don't cut only on the first beat of a bar, because it gets predictable. Cut on the "lift" just before a downbeat, skip the first downbeat to cut on the second, or use syncopated chords and percussion as edit points. — [Filmdaft, editing to the beat](https://filmdaft.com/how-to-edit-video-clips-to-the-beat-of-music-the-easy-way/)
- Strong edits follow musical phrases, transitions, drops and energy changes rather than treating every beat equally. Use longer holds in the intro and faster cuts in the chorus or drop. — [invideo FAQ, AI cut to beat](https://invideo.io/faq/can-ai-automatically-cut-a-video-to-the-beat-of-music/)
- Most music videos use 70–140 BPM. Half-time editing (1 cut per 2 beats) suits cinematic or trap visuals. *(Vendor source.)* — [BeatSync PRO](https://beatsyncpro.ai/blog/beat-synced-video-clips-free-download-pillar.html)
- A claim that BPM-aligned cutting boosts watch time "by up to 23%" cites no source and should not be relied on. — [Artfolio](https://www.artfolio.com/article/synced-cuts-and-bpm-brief-templates-that-align-artists-with-music-video-crews)

### Inferences
- **Algorithm for voice-led edits:** dialogue cuts are driven by speech, so don't force them onto the beat. Instead:
  1. Snap *B-roll inserts, zooms, text or graphic entrances and transitions* to the nearest beat when within about ±2–3 frames (±1 frame for hits). Otherwise leave them unsnapped.
  2. Place the strongest visual event (title card, reveal, punch-in) on a bar downbeat or on the drop.
  3. Text animations should "land" (reach full scale or opacity) on the beat, which means starting the animation 3–6 frames before it.
- **Cut density by BPM:** below about 90 BPM, cut every 2–4 beats. From 90 to 130 BPM, cut every 2 beats. Above 130 BPM, use half-time (every 2 beats, or 4 at 160+) so shots stay ≥0.7–1 s.
- Use beat and downbeat tracking such as librosa or madmom (BSD-licensed, on-device capable) to get beat, downbeat and phrase-boundary estimates. Prefer 4- or 8-bar phrase boundaries for music edit points (loops and cuts) so the music cut is inaudible. *(Tool names are inference. Licenses were not verified this session.)*

### Gaps
- No authoritative pro-editor source was found on tolerance windows (how many frames off-beat is perceptible), or on SFX and text hits relative to beats.

---

## 6. Open-source and royalty-free SFX/music libraries for bundling; procedural and AI music generation

### Takeaway
For bundling inside an app, only **CC0** (or explicitly redistributable) assets are low-risk: Kenney packs and Freesound's CC0-filtered subset. Freesound files are licensed per sound (CC0, CC-BY or CC-BY-NC), so filter carefully. Pixabay and Sonniss forbid standalone redistribution. For AI music:
- **MusicGen** weights are CC-BY-NC (non-commercial, unusable commercially).
- **Stable Audio Open** is under the Stability AI Community License (commercial use under a revenue threshold).
- **ACE-Step** is reported as **Apache 2.0** (commercial OK).

Verify each model card before shipping.

### Cited Findings
- Freesound licenses are set **per sound**: CC0, CC-BY (credit required) or CC-BY-NC (no commercial use). Filtering for CC0 gives no-attribution files. — [Cinevva free SFX and music guide 2026](https://app.cinevva.com/guides/free-sound-effects-music); [bugnet.io](https://bugnet.io/blog/where-to-find-free-sound-effects-for-games)
- Kenney audio packs are **CC0**, and attribution is optional. — [gtstu, Kenney CC0](https://gtstu.com/?p=5011); [Cinevva](https://app.cinevva.com/guides/free-sound-effects-music)
- CC0 places works in the public domain and allows commercial use. A pack's own terms may still add redistribution restrictions. — [RouteNote Licensing blog](https://licensing.routenote.com/blog/?p=8051)
- Sonniss GDC bundles (2015–2026) are royalty-free for commercial use with no attribution. There is **no standalone redistribution**, only embedding in a project, and **no AI training**. — [LicenseOrg](https://licenseorg.com/guide/music-audio/sonniss); [Rekkerd, GDC 2026 bundle](https://rekkerd.org/sonniss-releases-gdc-2026-game-audio-bundle/); [GameFromScratch GDC 2024](https://gamefromscratch.com/sonniss-27-5gb-sound-effect-giveaway-at-gdc-2024/)
- Pixabay audio and content may not be distributed "Standalone", meaning unmodified. — [Pixabay license](https://pixabay.com/service/license-summary/)
- **MusicGen** (Meta AudioCraft): code under MIT, **weights under CC-BY-NC 4.0**. The MIT code license does not extend commercial rights to the weights. — [Traeai learn: music generation & licensing](https://learn.traeai.com/t/ai-engineering/phases/06-speech-and-audio/09-music-generation); [Sonilo AudioCraft builder guide](https://sonilo.com/blog/guides/audiocraft-musicgen-audiogen-encodec-builder-guide)
- **Stable Audio Open** was trained on nearly 500k recordings licensed CC0, CC-BY or CC-Sampling+. Its weights are under the **Stability AI Community License**, which allows commercial use below a revenue threshold. Check the model card. — [Stability AI research post](https://stability.ai/news/stable-audio-open-research-paper); [arXiv 2407.14358](https://arxiv.org/html/2407.14358v2); [Spheron 2026 deploy guide](https://www.spheron.network/blog/deploy-open-source-ai-music-generation-gpu-cloud-2026/)
- **ACE-Step** is listed as **Apache 2.0** with commercial use permitted. An open 4B "XL" release in April 2026 is reported, and terms may differ by version. — [Spheron 2026](https://www.spheron.network/blog/deploy-open-source-ai-music-generation-gpu-cloud-2026/); [Traeai](https://learn.traeai.com/t/ai-engineering/phases/06-speech-and-audio/09-music-generation)
- A "Stable Audio 3" open-weight model aimed at content creators is reported by a third-party blog. Its license and specs are unverified. — [MindStudio blog](https://www.mindstudio.ai/blog/stable-audio-3-open-weight-music-generation-content-creators)

### Inferences
- **Bundling strategy:**
  1. Ship a curated CC0 core: Kenney UI, clicks and pops plus Freesound CC0 whooshes, hits and risers. Store a per-file manifest of source URL, author, license and download date.
  2. Fetch larger or non-CC0 catalogs (Pixabay, Freesound CC-BY with auto-attribution) on demand from their APIs rather than embedding them.
  3. Treat Sonniss as "embed in exported projects only" pending legal review.
- **Music strategy:** a server-side ACE-Step (Apache 2.0) or Stable Audio Open (Community License, revenue-capped) generator is the commercially viable open option. MusicGen is research-only. For on-device use, a procedural or stem-based approach is lighter: pre-made CC0 or commissioned loops and stems recombined by mood and BPM, time-stretched to the edit length and ended on a bar boundary. Large diffusion models are impractical on mobile. *(On-device feasibility is inference.)*
- Generated music should still be loudness-normalized (to about -16 to -14 LUFS before ducking) and beat-analyzed like library music.

### Gaps
- Primary license texts (Sonniss official page, Hugging Face model cards for Stable Audio Open and ACE-Step, Freesound's terms on bundling sounds in apps) were not fetched directly. The findings rely on aggregators.
- No reliable information was found on small on-device music models (e.g. quantized MusicGen-small or Magenta RT) with commercial licenses. This needs follow-up.
- BBC Sound Effects (RemArc, non-commercial), Epidemic Sound or Artlist API licensing and the YouTube Audio Library terms were not researched in this session.
