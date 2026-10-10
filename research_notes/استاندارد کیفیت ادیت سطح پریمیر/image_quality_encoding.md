# Image Quality and Encoding for 9:16 Short-Form Video (Rendering, Encoding, Platform Delivery)

Context for the report writer: the engine renders frames with Skia in sRGB 8-bit, adds procedural film grain, renders some gradients at 1/8 resolution and upscales them, uses multi-sample accumulation blur, and encodes with x264 CRF 17–24 via ffmpeg at 1080x1920 or 720x1280. Items under "Inferences" are engineering conclusions drawn from the cited findings plus general domain knowledge. They are not separately sourced and should be presented as recommendations, not as quoted facts.

## 1. Gradient banding vs dithering in 8-bit; does film grain help or hurt after recompression?

### Takeaway
Banding comes from too few code values (8-bit gives 256 per channel) combined with the encoder's quantization, which rounds away the faint variation that holds a gradient together. Dither or grain hides banding in the master, but dither or debanding applied just before a lossy encode usually does not survive it. Platform recompression at low bitrates strips fine noise and brings the bands back, or turns the grain into blocks. The robust fixes are to render gradients at high precision, quantize late with dither, keep the noise at a scale the codec can carry, and encode at higher precision where you can.

### Cited Findings
- 8-bit gives 256 levels per channel, 10-bit 1024 and 12-bit 4096. Worked example: a sky ramping from code value 90 to 110 over 400 px makes 20-px bands in 8-bit and about 5-px bands in 10-bit. — [Fora Soft: Banding](https://www.forasoft.com/learn/video-quality/articles-vqm/banding-gradient-steps)
- Quantization can add banding to 8-bit content that looked smooth at the source, because the encoder throws away the subtle variation. Film grain and sensor noise act as natural dither. Denoising, aggressive encoding or low bit depth remove that noise and expose the contours. — [Fora Soft: Banding](https://www.forasoft.com/learn/video-quality/articles-vqm/banding-gradient-steps)
- The eye amplifies straight, low-contrast edges (Mach bands), so a one-code-value step is clearly visible. PSNR, SSIM and legacy VMAF largely miss banding. Netflix's CAMBI detector targets it: about 5 is "slightly annoying" and about 24 is the worst observed. — [Fora Soft: Banding](https://www.forasoft.com/learn/video-quality/articles-vqm/banding-gradient-steps); [Netflix CAMBI blog](https://netflixtechblog.com/cambi-a-banding-artifact-detector-96777ae12fe2)
- Quote: "do not dither or deband just before a lossy encode and expect it to survive." The advice is to add grain as close to the final bitstream as possible, or to raise the bit depth. FFmpeg's `gradfun` filter (default strength 1.2, radius 16) interpolates and dithers, but its docs warn against using it before lossy compression. — [Fora Soft: Banding](https://www.forasoft.com/learn/video-quality/articles-vqm/banding-gradient-steps); [FFmpeg gradfun](https://ffmpeg.org/ffmpeg-filters.html#gradfun)
- Encoding 8-bit SDR in 10-bit moves the codec's internal rounding onto a finer grid. The article says Netflix encodes SDR AV1 in 10-bit and reports less banding and slightly smaller files. The article gives no primary Netflix citation for that specific claim. — [Fora Soft: Banding](https://www.forasoft.com/learn/video-quality/articles-vqm/banding-gradient-steps)
- Removing source noise before encoding can expose banding that the noise was hiding. A 2026 paper (Norkin) combines film grain synthesis with debanding for this reason. — [DCC 2026 FGS with debanding (PDF)](https://norkin.org/pdf/DCC_2026_FGS_with_debanding.pdf)
- Skia: `SkPaint::setDither` "requests, but does not require" distributing color error across smooth transitions, so it is opt-in per paint. Skia's own GM calls `gradientPaint.setDither(true)` before drawing gradients. A Skia maintainer noted that the CPU backend uses a fixed 2x2 dither cell, while the GPU backend is likely doing something fancier and can look smoother. Older raster-pipeline paths did not dither 2-point gradients until a generic dither stage was added. — [Skia SkPaint API](https://api.skia.org/classSkPaint.html); [Skia imagedither GM](https://skia.googlesource.com/skia/+/chrome/m144/gm/imagedither.cpp); [Skia issue 40036877](https://issues.skia.org/40036877); [Skia issue 40037425](https://issues.skia.org/issues/40037425)
- Triangular-PDF (TPDF) dither, the sum of two uniform randoms, gives mean-zero noise with no brightness shift. One GPU gradient shader uses ±1/255 amplitude. — [Zed gpui dithering commit](https://git.secluded.site/zed/commit/a46858ac21ec43b1af94d8b2b9a5a8fde4ac4f01)
- Patents describe encoder-aware dithering: one applies dither only along quantization contours using a noise map (US 8270498), and another weights pre-encode dither by local detail (US 2009/0180555). — [USPTO 8270498](https://image-ppubs.uspto.gov/dirsearch-public/print/downloadPdf/8270498); [Justia US20090180555](https://patents.justia.com/patent/20090180555)

### Inferences
- **The 1/8-resolution gradient path is a likely banding and blockiness source.** If the low-res buffer is 8-bit, each quantization step is stretched 8x on upscale, so a 1-code-value step becomes an 8-px-wide hard edge, and a bilinear upscale adds visible piecewise-linear "diamond" or blocky artifacts. Recommended fix: evaluate gradients analytically at full resolution, which Skia shaders already do cheaply. If a low-res pass is needed for blur or glow, store it in RGBA F16 (`kRGBA_F16_SkColorType`), upscale in float with bicubic, and apply dither only at the final 8-bit quantization.
- **Dither once, at the very end.** Render the composite in F16 or float, apply mean-zero TPDF or blue-noise dither of about ±1 LSB (8-bit) during the final float-to-8-bit conversion, then convert to YUV.
- **Prefer a temporally static dither pattern** (a fixed blue-noise texture, not re-randomized each frame). Inter-frame prediction can carry a static pattern almost for free, while per-frame random dither looks like new detail every frame and eats bitrate. This is inferred from how inter-frame codecs work; I found no direct test of it.
- **Dither alone will not survive platform recompression** at low single-digit Mbps (see section 5), so the design should not depend on it. Mitigations:
  - Avoid large, slow, dark gradients spanning few code values: dark near-black vignettes and subtle sky ramps are the worst case.
  - Increase gradient contrast, or add structured texture with real amplitude.
  - Where the upload pipeline allows it (YouTube, and HEVC Main10 for Instagram), deliver a 10-bit encode, for example x265 Main10 or x264 High10. Instagram's documented 10-bit path is for HDR (see section 5), and SDR 10-bit acceptance by the platforms is not confirmed.
- **Is grain worth it?** Grain is a net positive only if it is coarse and low-amplitude enough to survive the platform encode without turning into macroblocks. See section 2.

### Gaps
- I found no published test that pushes an 8-bit, TPDF-dithered gradient through Instagram, TikTok or YouTube recompression and measures the residual banding (for example with CAMBI).
- Whether TikTok and Instagram accept and preserve 10-bit SDR H.264 or HEVC (rather than only 10-bit HDR) is undocumented.

## 2. Grain/noise and compression: how noise consumes bitrate and causes blockiness; what platforms do to grain

### Takeaway
Grain is random, uncorrelated detail that changes every frame. Motion compensation cannot predict it, so it either consumes most of the bitrate or the encoder smooths it away. At the low delivery bitrates platforms use, the usual result is smeared, blocky flat areas, "boiling" noise and muddy skin tones, plus re-exposed banding. Streaming services that care about grain (Netflix on AV1) strip it before encoding and re-synthesize it in the decoder. There is no evidence that Instagram, TikTok or YouTube Shorts do this for uploads.

### Cited Findings
- Compression saves space by discarding information that doesn't change much between frames. Grain changes constantly, so the encoder blends areas of similar color together, producing banding, blockiness and muddy skin tones. Quote: "Organic, natural-looking grain becomes artificial, digital noise." — [FilmConvert: Avoiding noise in your YouTube uploads](https://www.filmconvert.com/blog/wp-json/wp/v2/posts/742)
- FilmConvert recommends rendering a "bigger container" master (10-bit ProRes) and uploading that rather than H.264, so the platform has more data to work with. — [FilmConvert](https://www.filmconvert.com/blog/wp-json/wp/v2/posts/742)
- Forum reports (anecdotal): uploading noisy footage at 35 Mbps still produced blocking on YouTube, and exporting at 4K reduced the artifacts because it earned a higher-bitrate stream. — [Blackmagic forum](https://forum.blackmagicdesign.com/viewtopic.php?p=404725); [VideoHelp forum](https://forum.videohelp.com/showthread.php?p=2778685)
- Grain is hard to compress because its random structure doesn't map well onto conventional coding tools. With AV1 Film Grain Synthesis (FGS), Netflix denoises the source, encodes the clean picture, and sends a parametric grain model (an autoregressive pattern plus an intensity scaling function) for the decoder to re-apply. — [IBC](https://ibc.org/content-management/news/netflix-rolls-out-av1-film-grain-synthesis-for-classic-movies/22018); [Norkin et al., DCC 2018 AV1 film grain (PDF)](https://norkin.org/pdf/DCC_2018_AV1_film_grain.pdf)
- Netflix reported a 36% average bitrate reduction at 1080p and above across about 300 titles, but only about 10% below 1080p, because downscaling already filters the noise. A later Netflix TechBlog summary cited a 31.6% average and a 24% starting-bitrate reduction. The two figures come from different reporting and are not directly comparable. — [IBC](https://ibc.org/content-management/news/netflix-rolls-out-av1-film-grain-synthesis-for-classic-movies/22018); [Broadband TV News, Jul 2025](https://www.broadbandtvnews.com/2025/07/04/netflix-film-grain-synthesis-saves-on-the-bitrate/)
- The pre-deployment AV1 FGS study reported up to 50% savings on heavily grained test sequences. — [DCC 2018 paper (PDF)](https://norkin.org/pdf/DCC_2018_AV1_film_grain.pdf)
- x264 `--tune grain` changes these settings: `--aq-strength 0.5 --no-dct-decimate --deadzone-inter 6 --deadzone-intra 6 --deblock -2:-2 --ipratio 1.1 --pbratio 1.1 --psy-rd <unset>:0.25 --qcomp 0.8`. It preserves grain by spending far more bits on it. — [x264 --fullhelp (gist)](https://gist.github.com/875122)
- Instagram serves lower-quality encodes for videos with few views. Adam Mosseri said in October 2024 that videos unwatched for a long time move to a lower-quality version, are re-rendered at higher quality if views return, and that higher quality is biased toward creators who drive views, on a sliding scale. — [Tubefilter](https://tubefilter.com/2024/10/28/instagram-lowers-video-quality-creators-adam-mosseri/); [Notebookcheck](https://www.notebookcheck.net/Instagram-ties-video-quality-to-view-count.909654.0.html)

### Inferences
- **Procedural grain as typically applied** (per-pixel, per-frame random, 1-px scale) is close to the worst case for platform encoders. On upload it either destroys efficiency in the master or is smeared away by the platform encode, leaving blotchy or blocky flats. The 1080p-and-up versus below-1080p gap in Netflix's numbers also shows that downscaling, which platforms do for low tiers, filters grain anyway.
- **Recommended grain policy for social delivery:**
  1. Make grain optional or subtle by default: luma-only, low amplitude (around 1–2 8-bit code values RMS), with no grain on chroma.
  2. Use a larger grain size (about 1.5–2.5 px at 1080 wide, generated at lower resolution and smoothly upscaled) so it survives 4:2:0 and transform quantization as texture rather than speckle.
  3. Optionally animate grain at a lower rate (for example 12 Hz) or reuse a small set of grain frames, so inter prediction can partly reuse it.
  4. Do not apply grain over text or UI layers. Composite grain under the graphics so glyph edges stay clean.
- **For the engine's own master**, `-tune grain` or `-tune film` at a low CRF preserves grain. The platform will then re-encode at much lower bitrate (see section 5), so the master's grain fidelity is largely wasted on Instagram and TikTok.

### Gaps
- No primary source documents whether YouTube, Instagram or TikTok apply AV1 FGS or any denoise-plus-resynthesis to user uploads.
- I found no measured study of what the platforms do specifically to synthetic grain in motion graphics.

## 3. Chroma subsampling (4:2:0) artifacts on saturated text and edges; mitigations

### Takeaway
All three platforms require or deliver 4:2:0: color is stored at half resolution in each dimension, one chroma sample per 2x2 pixel block. Edges defined mainly by color difference rather than brightness difference, such as red on black, red on blue, or lime on white, get smeared, fringed and "pixelated". The fix is to make edges carry luma contrast (outlines, shadows, luma-separated color choices), keep strokes and features well above 2 px, and, where the platform rewards it (YouTube), upload at higher resolution so the chroma planes are effectively full 1080p resolution.

### Cited Findings
- YouTube's upload spec calls for 4:2:0 chroma subsampling. Meta's Reels spec says "HEVC or H264, progressive scan, closed GOP, 4:2:0 chroma subsampling." — [YouTube recommended upload encoding settings](https://support.google.com/youtube/answer/1722171?hl=en); [Meta IG Graph API: IG User Media](https://developers.facebook.com/docs/instagram-platform/instagram-graph-api/reference/ig-user/media)
- 4:2:0 keeps full brightness detail but shares one color sample across each 2x2 block, which creates color fringes and bleeding on hard saturated edges. A common example is red text on a green background. — [Fora Soft glossary: 4:2:0](https://www.forasoft.com/learn/video-encoding/glossary/terms/420); [Display Ninja](https://www.displayninja.com/chroma-subsampling/)
- Saturated red text and graphics in particular look blurry or "pixelated" under chroma subsampling. — [PCWorld](https://www.pcworld.com/article/1671259/seeing-blurry-distorted-patches-of-red-on-your-monitor-try-this-fix.html); [VEGAS forum "Red gets pixelated"](https://www.vegascreativesoftware.info/us/forum/red-get-pixelated--127309)
- Citrix notes that 4:2:0 screen encoding degrades high-contrast details such as text, making them fuzzy. — [Citrix Thinwire color space docs](https://docs.citrix.com/en-us/citrix-virtual-apps-desktops/graphics/thinwire/colorspace)
- TestUFO offers a browser test pattern for chroma resolution. — [TestUFO chroma test](https://testufo.com/chroma)
- On YouTube, one 2026 VMAF test found a 1080p upload scored about 97.3, a 1440p upload about 99.95 and a 4K upload about 99.92. Most of the gain came from moving 1080p to 1440p (single study by Zeb Gardner, as reported). The same source states YouTube Shorts playback tops out at 1080p, and that 4K uploads do nothing for TikTok or Instagram. The TikTok and Instagram claims are the author's assertions without test data. — [The Post Flow: export settings](https://thepostflow.com/post-production/post-production-workflows/export-settings-youtube-instagram-tiktok/)

### Inferences
- **Why pure red is the classic offender.** In BT.709, Y' = 0.2126R' + 0.7152G' + 0.0722B', so pure red (255,0,0) has a luma of only about 54/255. On a dark background the glyph's edge is mostly a chroma edge, which 4:2:0 halves in resolution and the encoder then quantizes heavily. Pure lime (0,255,0) has luma of about 182/255. It is fine on dark backgrounds but has weak luma contrast on white or yellow.
- **Practical mitigations for the engine:**
  1. Give every colored text or icon a luma-contrasting edge: a dark stroke of at least 3–4 px at 1080 width for saturated light text, a light stroke for saturated dark text, or a soft drop shadow.
  2. Choose text and background pairs by luma contrast (for example a WCAG-style contrast ratio of 4.5:1 or more), not by hue contrast alone.
  3. Avoid thin strokes, fine serifs or hairlines in saturated colors. Keep the minimum stroke width at 3 px or more and caption font sizes large (at 1080 wide, roughly 48 px cap height or more is a sensible floor).
  4. Slightly desaturate or lift the luma of pure primaries (for example use #FF3B30-style reds rather than #FF0000).
- **Control the RGB-to-YUV conversion yourself.** By default, ffmpeg's swscale converts RGB to yuv420p with a simple chroma decimation and, unless told otherwise, the BT.601 matrix. Recommended: `-vf "scale=out_color_matrix=bt709:out_range=tv:flags=lanczos+accurate_rnd+full_chroma_int"`, or `zscale`, with a proper chroma downsampling filter. This is domain knowledge that should be verified against the FFmpeg docs (I could not fetch the FFmpeg wiki).
- **"Upload in 4K" for chroma.** Rendering at 2160x3840 and encoding 4:2:0 gives chroma planes at 1080x1920, which are effectively 4:4:4 at 1080p. This is useful on YouTube, where higher upload resolution also unlocks higher-bitrate (VP9/AV1) ladders. On Instagram and TikTok there is no evidence of benefit, and the platform's own downscale may be lower quality than yours, so deliver exactly 1080x1920.

### Gaps
- No platform documents its internal chroma downsampling or upscaling filters.
- No controlled test was found comparing saturated red text legibility across 1080p and 4K uploads on Instagram or TikTok.

## 4. Linear vs gamma-space blending and antialiasing; supersampling; text sharpness; scaling filters; upscaling 720p

### Takeaway
Blending, antialiasing coverage, blur and resampling done on sRGB-encoded values produce darkened halos, muddy color mixes, text that looks too heavy or too thin, and dark fringes on colored edges. Physically correct results come from doing these operations in linear light and re-encoding afterwards, with a weight correction for text so glyphs don't look too light. For scaling: bilinear is soft, bicubic is a good default, and Lanczos is sharpest but can ring. FFmpeg's default `-s` (bicubic) leaves measurable quality on the table. Upscaling 720p to 1080p cannot add detail, so render natively at 1080x1920.

### Cited Findings
- Coverage-based antialiasing assumes pixel values are linear, but sRGB and gamma 2.2 displays are not. A 50% coverage value therefore looks too dark, and a value near 186 is needed for 50% perceived brightness. The result is blotchy dark-on-light text, thin light-on-dark text, and dark halos around colored text on colored backgrounds. The fix is to blend in linear space, then gamma-encode. — [FreeType mailing list, Mar 2021](https://lists.libreplanet.org/archive/html/freetype/2021-03/msg00007.html)
- Ghostty added a weight-correction step so that linearly blended text keeps about the same apparent thickness as traditional, gamma-incorrect blended text, because correct linear blending can make text look lighter. — [Ghostty commit diff](https://git.uoc.run.place/Applied-Software/ghostty/commit/5c8f984ea157bd40da631a17ddafcbf07a5b04db.diff)
- Unity's docs: linear color space gives more accurate rendering than gamma space, and gamma-space blending produces over-bright, over-saturated results. — [Unity Manual: Linear or gamma workflow](https://docs.unity3d.com/ru//Manual/LinearRendering-LinearOrGammaWorkflow.html)
- FFmpeg's scaler defaults to `bicubic`. Lanczos has a default width (alpha) of 3, adjustable via `param0`, and `spline` is a natural bicubic spline. Only one algorithm should be selected. — [FFmpeg scaler docs](https://roundup.ffmpeg.org/ffmpeg-scaler.html)
- A NETINT/Streaming Learning Center test found that scaling with ffmpeg's default `-s` loses roughly 10% on VMAF or SSIM (much less on PSNR) compared with better methods, and that `fast_bilinear` loses quality without a meaningful speed gain. — [Streaming Learning Center](https://streaminglearningcenter.com/?p=17419)
- A third-party guide recommends Lanczos for upscaling and bicubic for general downscaling. This is a secondary source. — [Apidog guide](https://apidog.com/de/blog/upscale-enhance-video-quality-ffmpeg/)

### Inferences
- **Skia configuration.** Composite on a linear-light, higher-precision surface: an `SkSurface` with `kRGBA_F16_SkColorType` and `SkColorSpace::MakeSRGBLinear()`. Draw sRGB assets into it (Skia color-manages them), then convert to sRGB 8-bit only at the end, with dither.
  - Benefits: correct alpha blending of glows and light leaks, correct gradient interpolation, a physically plausible accumulation motion blur (averaging must be done in linear light, or fast bright objects look too dark and dim when blurred), and correct downsampling.
  - Gradient interpolation caveat: designers often prefer perceptual interpolation (Oklab) for color-to-color gradients over linear-light interpolation, which can look washed out mid-ramp. Skia supports interpolation color spaces for gradients in newer versions; this needs verifying against the Skia version in use.
  - Text caveat: linear blending makes dark-on-light text look thinner. Apply a small gamma or contrast boost to glyph coverage, or a slight emboldening, as Ghostty and FreeType-based renderers do.
- **Supersampling and text.**
  - Draw text as outlines with antialiasing, not subpixel LCD AA (video gets resampled and YUV-converted, so LCD color fringing becomes chroma noise).
  - Disable hinting for animated text. Hinting snaps outlines to the pixel grid, which causes jitter when text scales or moves.
  - Enable subpixel positioning (`SkFont::setSubpixel(true)`) for smooth motion.
  - For moving or scaling vector graphics, 2x2 supersampling (render at 2x, box or bicubic downsample in linear light) noticeably reduces crawling and shimmer on thin lines.
- **Scaling filters.**
  - Downscaling (for example a 2160x3840 master to 1080x1920): use Lanczos (a=3) or bicubic in linear light. Lanczos is sharper but rings around high-contrast text. Bicubic with B=0, C=0.5 (Catmull-Rom) or Mitchell (B=C=1/3) is a safer compromise for graphics.
  - Upscaling low-res buffers (glows, blurs, 1/8-res gradients): use bicubic or bilinear in float. Bilinear on smooth signals is fine if the buffer is high precision, and Lanczos ringing is undesirable on smooth gradients.
  - Never use `fast_bilinear` or point sampling.
  - For ffmpeg: `-vf scale=1080:1920:flags=lanczos+accurate_rnd+full_chroma_int` (or `spline`). For user footage, prefer `zscale` with linear-light conversion when quality matters.
- **The 720x1280 output tier** is a clear source of "soft" results. Platforms deliver up to 1080 wide for 9:16. A 720p upload will be upscaled by the player and will look soft, especially text. Render graphics natively at 1080x1920 even when the source footage is 720p: upscale the footage layer only (Lanczos or bicubic, optionally with mild sharpening) and draw text and vectors at full output resolution. Keep 720p only as a low-power fallback.

### Gaps
- I found no authoritative quantitative comparison of linear-light versus sRGB-space downscaling artifacts for video graphics.
- I did not verify which Skia version introduced gradient interpolation-space options, or confirm the default dither behavior in the GPU (Graphite or Ganesh) backends.

## 5. Recommended export settings for Instagram Reels, TikTok and YouTube Shorts (2025–2026), and how each platform recompresses

### Takeaway
Deliver 1080x1920, 9:16, progressive, constant frame rate (30 fps unless the content is 60), H.264 High profile, 4:2:0, closed GOP, MP4 with faststart (moov atom first), AAC 48 kHz, and BT.709 primaries, transfer and matrix in limited (TV) range, correctly tagged.
- Instagram: VBR at or below 25 Mbps (300 MB API cap). Instagram recompresses to roughly 720p–1080p at low single-digit Mbps, and quality also depends on view count.
- TikTok: H.264 recommended; no official bitrate; around 1080p delivery.
- YouTube: 1080p at 30 fps is about 8 Mbps minimum. Uploading at 1440p or 4K earns higher-quality encodes for normal videos, but Shorts playback reportedly caps at 1080p.

### Cited Findings
**YouTube (official)**
- MP4 with no edit lists and the moov atom at the front (fast start). H.264, progressive, High Profile, 2 consecutive B-frames, closed GOP with a GOP of half the frame rate, CABAC, VBR with no bitrate limit required, 4:2:0. Upload at the frame rate the content was recorded at. Audio AAC-LC or Opus at 48 kHz, stereo 384 kbps. — [YouTube recommended upload encoding settings](https://support.google.com/youtube/answer/1722171?hl=en)
- SDR bitrates (24–30 fps / 48–60 fps): 2160p 35–45 / 53–68 Mbps; 1440p 16 / 24 Mbps; 1080p 8 / 12 Mbps; 720p 5 / 7.5 Mbps. HDR 1080p is 10 / 15 Mbps. — [YouTube recommended upload encoding settings](https://support.google.com/youtube/answer/1722171?hl=en)
- SDR color: BT.709 for transfer characteristics, primaries and matrix (H.273 value 1). Don't use an RGB matrix. Full-range color is converted to limited range during processing. 4K uploads play in 4K only where VP9 is supported. — [YouTube recommended upload encoding settings](https://support.google.com/youtube/answer/1722171?hl=en)
- Creator test (2026): 1080p upload VMAF about 97.3, 1440p about 99.95, 4K about 99.92. The source recommends 1440p at 24 Mbps target (30 max) or 4K at 45 target (60 max). Shorts are 1080x1920 and playback tops out at 1080p. — [The Post Flow](https://thepostflow.com/post-production/post-production-workflows/export-settings-youtube-instagram-tiktok/)

**Instagram Reels (official Meta API spec)**
- Quote: "Container: MOV or MP4 (MPEG-4 Part 14), no edit lists, moov atom at the front of the file"; "Video codec: HEVC or H264, progressive scan, closed GOP, 4:2:0 chroma subsampling"; "Frame rate: 23-60 FPS"; "Maximum columns (horizontal pixels): 1920"; aspect ratio 0.01:1 to 10:1 but "we recommend 9:16"; "Video bitrate: VBR, 25Mbps maximum"; "Audio codec: AAC, 48khz sample rate maximum"; "Audio bitrate: 128kbps"; "Duration: 15 mins maximum, 3 seconds minimum"; "File size: 300MB maximum". — [Meta IG Graph API: IG User Media (Reel specifications)](https://developers.facebook.com/docs/instagram-platform/instagram-graph-api/reference/ig-user/media)
- Conflict: one third-party blog states the maximum width is 1080 px and that "Instagram does not support 4K playback" without citing Meta. Meta's API spec says the maximum is 1920 columns. — [Mallary.ai](https://www.mallary.ai/blog/instagram-reel-resolution) vs [Meta](https://developers.facebook.com/docs/instagram-platform/instagram-graph-api/reference/ig-user/media)
- Observed delivery: a 1080x1920 source typically plays back at 720p–1080p at low single-digit Mbps. HDR works via HEVC Main10 with an HLG tag, and PQ uploads play back as SDR in practice. The source recommends enabling "Upload at highest quality" in the app, and an 8–12 Mbps VBR export. These are observations and recommendations, not Meta specs. — [The Post Flow](https://thepostflow.com/post-production/post-production-workflows/export-settings-youtube-instagram-tiktok/)
- A third-party claim attributed to InVideo creator tests: controlled 5–8 Mbps 1080p exports showed 20–30% higher perceived sharpness than very high bitrate uploads. This is unverified, and the methodology is unknown. — [Mallary.ai](https://www.mallary.ai/blog/instagram-reel-resolution)
- Quality depends on view count (Mosseri, Oct 2024). — [Tubefilter](https://tubefilter.com/2024/10/28/instagram-lowers-video-quality-creators-adam-mosseri/)

**TikTok (official Content Posting API)**
- Formats: "MP4 (recommended)", "WebM", "MOV". Codecs: "H.264 (recommended)", "H.265", "VP8", "VP9". Frame rate: minimum 23 and maximum 60 FPS. Picture size: minimum 360 px and maximum 4096 px for both height and width. Maximum file size 4 GB. API uploads are up to 10 minutes. — [TikTok Content Posting API media transfer guide](https://developers.tiktok.com/doc/content-posting-api-media-transfer-guide)
- TikTok publishes no recommended bitrate. Third-party recommendations for 1080p at 30 fps range from about 5 Mbps to 8–12 Mbps to 10–15 Mbps. One source warns that 60 fps leads to heavier compression and that HEVC uploads compress harder. In-app uploads are capped at around 287 MB, and the "Upload HD" toggle should be enabled. — [BigMotion](https://www.bigmotion.ai/blog/the-ultimate-guide-to-tiktok-video-size); [RenderCut](https://rendercut.io/high-quality-upload-on-tiktok); [The Post Flow](https://thepostflow.com/post-production/post-production-workflows/export-settings-youtube-instagram-tiktok/)
- TikTok publishes no HDR, color-space or bit-depth documentation, and the source recommends SDR Rec.709 only. — [The Post Flow](https://thepostflow.com/post-production/post-production-workflows/export-settings-youtube-instagram-tiktok/)

**Color tagging pitfall**
- Rec.709 gamma 2.4 exports carry an NCLC 1-2-1 tag (transfer "unspecified"), while platforms and macOS expect 1-1-1. The mismatch lifts blacks and flattens contrast. Tag 1-1-1, and tagging as sRGB is the wrong fix. — [The Post Flow](https://thepostflow.com/post-production/post-production-workflows/export-settings-youtube-instagram-tiktok/)

### Inferences
- **A single delivery profile serves all three platforms.** Recommended ffmpeg output for the engine (H.264, 30 fps):

  ```
  -vf "scale=out_color_matrix=bt709:out_range=tv,format=yuv420p"
  -c:v libx264 -preset slow -crf 16 -maxrate 20M -bufsize 40M
  -profile:v high -level:v 4.2 -g 60 -keyint_min 30 -bf 2 -pix_fmt yuv420p
  -color_primaries bt709 -color_trc bt709 -colorspace bt709 -color_range tv
  -movflags +faststart
  -c:a aac -b:a 192k -ar 48000
  ```

  - CRF and the VBV cap keep complex scenes high while respecting Instagram's 25 Mbps cap. At 300 MB, Instagram's API limit also caps duration × bitrate: 20 Mbps allows about 2 minutes.
  - At 60 fps use level 4.2, a 25 Mbps cap and `-g 120`.
  - Using the same RGB-to-YUV matrix (BT.709) for conversion as for tagging is critical. A BT.601-converted file tagged as 709, or vice versa, causes hue and saturation shifts.
  - Keep the input sRGB render: the sRGB and BT.709 primaries are identical. Many pipelines tag `color_trc bt709` for sRGB-mastered graphics, which is the convention platforms expect. Strictly, sRGB's piecewise curve and BT.709's OETF differ slightly in the darks, but tagging sRGB (trc 13) is poorly supported by players.
- **The engine's CRF 17–24 range is too wide for an upload master.** Everything is recompressed by the platform, so the upload should be near-transparent: CRF 14–18 with preset slow, otherwise generation loss compounds. CRF 22–24 is acceptable only for local previews.
- **Per-platform resolution.** Use 1080x1920 for Instagram and TikTok. For YouTube Shorts, 1080x1920 is the safe default. A 1440x2560 upload may yield a better 1080p encode, but this is unverified specifically for Shorts.
- **Frame rate.** Prefer 30 fps for motion graphics unless motion is very fast. Platforms re-encode 60 fps at a similar total bitrate, so the per-frame quality is lower.

### Gaps
- None of the three platforms publish the bitrates of their delivered renditions. Figures such as "low single-digit Mbps" for Instagram are observational.
- No primary source confirms whether YouTube Shorts serves above 1080p or whether a 1440p or 4K Shorts upload improves the delivered 1080p encode.
- TikTok's official bitrate guidance does not exist publicly. Third-party numbers conflict (5 to 15 Mbps).

## 6. x264/x265 parameters for high-quality social delivery, and mobile hardware encoders (MediaCodec)

### Takeaway
For upload masters use x264 preset slow (or slower) at CRF of about 16–18, with a tune matched to content: `animation` for flat motion graphics and `film` for live action. Use `grain` only when grain must be preserved in a master, since it inflates bitrate. Cap peaks with VBV for Instagram's 25 Mbps limit. On Android, MediaCodec's constant-quality mode is rarely supported for H.264, so use VBR at about 10–16 Mbps for 1080p at 30 fps (more at 60) and check the device's capabilities.

### Cited Findings
- x264 defaults: CRF 23.0; aq-mode 1 (variance AQ); aq-strength 1.0; psy-rd 1.0:0.0; deblock 0:0; keyint 250; min-keyint auto; scenecut 40. — [x264 --fullhelp (gist)](https://gist.github.com/875122)
- Tunes:
  - film: `--deblock -1:-1 --psy-rd <unset>:0.15`
  - animation: `--bframes {+2} --deblock 1:1 --psy-rd 0.4:<unset> --aq-strength 0.6 --ref {double if >1 else 1}`
  - grain: `--aq-strength 0.5 --no-dct-decimate --deadzone-inter 6 --deadzone-intra 6 --deblock -2:-2 --ipratio 1.1 --pbratio 1.1 --psy-rd <unset>:0.25 --qcomp 0.8`
  - stillimage: `--aq-strength 1.2 --deblock -3:-3 --psy-rd 2.0:0.7`
  - psnr: `--aq-mode 0 --no-psy`
  - ssim: `--aq-mode 2 --no-psy`

  Only one psy tune can be used at a time. — [x264 --fullhelp (gist)](https://gist.github.com/875122)
- Presets:
  - slow: `--b-adapt 2 --direct auto --me umh --rc-lookahead 50 --ref 5 --subme 8`
  - slower: adds `--partitions all --rc-lookahead 60 --ref 8 --subme 9 --trellis 2`
  - veryslow: `--bframes 8 --merange 24 --ref 16 --subme 10`

  — [x264 --fullhelp (gist)](https://gist.github.com/875122)
- YouTube's ingest spec asks for 2 consecutive B-frames, closed GOP, CABAC and High Profile. — [YouTube](https://support.google.com/youtube/answer/1722171?hl=en)
- Android MediaCodec bitrate modes are CQ (constant quality, API 21), VBR, CBR and CBR_FD. `getQualityRange()` (API 28) is implementation-specific, where higher means better quality. — [Android MediaCodecInfo.EncoderCapabilities](https://developer.android.com/reference/android/media/MediaCodecInfo.EncoderCapabilities)
- An FFmpeg developer ticket reports that no Android H.264 encoder they knew of supported CQ, only VBR or CBR, and that 1080p needed at least about 10 Mbit/s for acceptable quality in their experience. This is anecdotal. — [FFmpeg trac ticket 10689](https://trac.ffmpeg.org/ticket/10689)

### Inferences
**Recommended x264 settings for the engine (master for upload):**
- `-preset slow` (or `slower` when render time allows), `-crf 16` for graphics-heavy content (14–18 range).
- `-tune animation` for flat-shaded motion graphics and text: lower psy-rd and more deblocking reduce ringing and mosquito noise around flat fills. Use `-tune film` when the frame is mostly camera footage.
- Do not combine `-tune grain` with procedural grain unless the deliverable is an archival master.
- Consider `-x264-params aq-mode=3` (auto-variance AQ with a dark-scene bias, available in x264 builds since about 2015). It spends more bits in dark flat areas, which helps banding in dark gradients. Verify it in your x264 build.
- GOP: `-g` at 1–2 seconds of frames (30–60 at 30 fps). This is more conservative than x264's default of 250. Platforms re-encode anyway, but closed, regular GOPs match the YouTube and Meta specs. Add `-flags +cgop` if open-GOP is enabled.
- Two-pass ABR is only needed when you must hit a size target, such as the 300 MB Instagram API cap or the about 287 MB TikTok in-app cap. Otherwise CRF plus `-maxrate`/`-bufsize` (capped VBR) is simpler and gives equal or better quality per bit.

**x265 / HEVC:**
- Only Instagram explicitly lists HEVC as a recommended codec, and TikTok accepts H.265. One TikTok-focused source warns HEVC uploads "compress harder". H.264 is the safest universal choice.
- If HEVC is used: `-c:v libx265 -preset slow -crf 18 -tag:v hvc1 -x265-params "aq-mode=3:no-sao=1:deblock=-1,-1"`, plus the same color tags. `hvc1` is needed for Apple and QuickTime playback. Main10 (`-pix_fmt yuv420p10le`) reduces banding.

**MediaCodec (on-device export):**
- Use VBR (or CBR where VBR is poorly implemented).
- 1080x1920 at 30 fps: 12–16 Mbps. At 60 fps: 20–25 Mbps. 720x1280 at 30 fps: 6–8 Mbps.
- Set profile High (`AVCProfileHigh`) and level 4.1/4.2. Set `KEY_I_FRAME_INTERVAL` to 1–2 s.
- Set `KEY_COLOR_STANDARD = COLOR_STANDARD_BT709`, `KEY_COLOR_TRANSFER = COLOR_TRANSFER_SDR_VIDEO` and `KEY_COLOR_RANGE = COLOR_RANGE_LIMITED`.
- Probe `isBitrateModeSupported(BITRATE_MODE_CQ)` and use CQ with a high quality value only when it is supported.
- Hardware encoders are typically worse than x264 slow per bit, so they need noticeably more bitrate for equivalent quality.

### Gaps
- I could not access the official FFmpeg H.264 wiki (bot-protected) to quote its CRF guidance verbatim, such as "visually lossless" around CRF 17–18.
- I did not fetch the Android CDD's minimum encoder bitrate requirements.
- No source quantified x264 `aq-mode 3` benefits for motion graphics.

## 7. Motion blur and temporal aliasing best practices

### Takeaway
Motion with no blur at 24–30 fps strobes, which reads as "choppy" and "cheap". The 180-degree shutter convention (exposure = half the frame interval, 1/60 s at 30 fps) is the neutral default for natural-looking motion. Larger angles feel faster and more kinetic, and smaller ones feel staccato. Accumulation blur must use enough sub-samples that per-sample displacement is about 1 px, and should be averaged in linear light. Note that motion blur also lowers high-frequency detail, which helps the codec.

### Cited Findings
- The 180-degree rule sets shutter speed to double the frame rate: 1/48 at 24 fps and 1/60 at 30 fps. It originates from rotating film shutters. — [StudioBinder](https://www.studiobinder.com/blog/what-is-the-180-degree-shutter-rule/); [DIYPhotography](https://www.diyphotography.net/?p=309010)
- Smaller shutter angles produce more pronounced strobing, which is very noticeable in pans. Larger angles produce more blur. Pushing past 180 degrees gives "a heightened sense of speed and energy". The 180-degree rule suits walking and talking at 24–30 fps but gives too much blur for fast subjects, where doubling the frame rate is the usual fix. — [ProVideo Coalition: The 180 shutter angle rule is broken](https://provideocoalition.com/the-180-shutter-angle-rule-is-broken); [No Film School](https://nofilmschool.com/shutter-drag-action); [Pixelsham](https://www.pixelsham.com/?p=4690)

### Inferences
- **Engine defaults:**
  1. Use a 180-degree shutter (sample over half the frame interval, centered on the frame time or trailing it). Expose the shutter angle per style, for example 90° for punchy kinetic typography and 270–360° for whip transitions.
  2. Pick the sub-sample count adaptively: N ≈ ceil(max on-screen displacement per frame × shutter fraction / 1 px), clamped to about 8–64. With a fixed small N, fast-moving objects show discrete "ghost copies" (stepping) instead of a smooth smear, and the codec reads those copies as detail, increasing blockiness.
  3. Accumulate in linear-light float (F16) and quantize with dither once at the end. Accumulating in sRGB 8-bit both darkens blurred highlights and adds rounding error proportional to N.
  4. Exclude static layers (resting text) from accumulation, or skip it when velocity is 0, to save render time and keep text razor-sharp when still.
  5. Use eased motion and avoid tiny 1-px-per-frame drifts of fine text or lines, which cause shimmer (temporal aliasing) as edges cross pixel boundaries. Supersampling (section 4) plus subpixel text positioning mitigates this.
- **Frame rate:** 30 fps with a 180-degree blur is the efficient default for platform delivery. Use 60 fps only for very fast camera-like motion or gameplay, since the platform's per-frame bitrate is lower at 60 fps.
- **Temporal grain and dither interact with blur.** Apply grain after motion blur, as a final pass, not inside the accumulation loop. Otherwise it averages out and wastes compute.

### Gaps
- No authoritative source was found on recommended sub-sample counts for accumulation motion blur in motion-graphics renderers, or on how motion-blurred versus unblurred graphics affect platform-encoded quality (for example measured VMAF or bitrate). The guidance above is inferred.
