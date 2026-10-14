# Subtitles — research and the styles in the app (phase 9.9)

Owner's order (14 Oct 2026): the best subtitle styles and their animations, for YouTube and for videos that need only
subtitles; a plain reel (subtitles with the silences cut, no motion graphics); the app's own brand faces in them.

## What the field does (2026)

- Short-form leaders: word-by-word or short-phrase captions, high contrast with an outline, lower-middle of the
  frame; the word being said highlighted ("karaoke"). Auto-caption tools sell these as templates: CapCut
  (highlight/karaoke templates animated to the audio), Submagic (20+ animated word styles), Captions, Opus Clip,
  VEED, Descript. Sources: blitzcutai.com/blog/best-caption-style-tiktok,
  kapwing.com/resources/how-to-get-capcut-caption-styles-and-best-alternatives,
  fluxnote.io/guides/submagic-vs-capcut-captions-2026 (vendor blogs: directional).
- Readability rules (broadcast and streaming guides as summarised by convertaudiototext.com, syllaby.io,
  subanana.com, capcut.com/create/vertical-video-subtitles-placement-tips): at most two lines, ~37–42 characters a
  line; 15–20 characters a second; on screen at least ~1 s, at most ~7 s, a little after speech ends; font between
  1/20 and 1/10 of the frame height; inside the title-safe area, clear of faces and of the platform's buttons on
  vertical video (bottom-centre by default).
- Jump cuts of silence removal read as edits when the frame alternates tight and wide at each cut (the compiler's
  punch-in already does this).

## The styles (engine/motion `SubtitleStyle`, recipes in `SubtitleRecipes`)

| Style | Words a line | Treatment | Face |
|---|---|---|---|
| کلاسیک (classic) | up to 12, two lines | calm fade in/out, soft guard; YouTube's default | the brand text face |
| جعبه‌ای (box) | up to 8 | each line on a dark rounded box (the platforms' own caption look) | the brand text face |
| کارائوکه (karaoke) | up to 7 | the line up at once, each word taking the accent as it is said | the brand expressive face |
| پاپ درشت (bold pop) | 1–3 | heavy outlined words popping in as said, the word being said in the accent; reels' default | the brand display face |
| هایلایت (highlight) | up to 5 | the word being said on an accent block that moves with the voice | the brand accent face |
| مینیمال (minimal) | up to 10 | small, light, low | the brand accent face (light) |

Sizes: on a 9:16 frame a share of the caption band's width (classic ≈ 65 px on 1080 wide, pop ≈ 104 px); on a
16:9 frame a share of the band's height up to 54–92 px. Ink and blocks end when the next word starts
(`Decoration.Ink.until`, `Decoration.Block.until`).

## The plain reel (`EditKind.Subtitles`, `SubtitleReel`)

Silences cut (`SpeechAnalysis`, as the reel), the frame tight/wide at each cut, every word on screen in the picked
style, the voice mastered; no graphics, no music, no cold open. The director only proofreads what goes on screen.
The create screen: «ریلز ساده» beside «ریلز / یوتیوب / یوتیوب حرفه‌ای», and «سبک زیرنویس» for every subtitled kind.

## Seen on the owner's 155 s talk

29 silences cut (155 s → 142 s). Frames of every style reviewed (`SubtitleProofTest`). Found and fixed: a word joined
by a ZWNJ («می‌خوره») was half highlighted in bold pop while a later word was still hidden — word boxes are now the
word's whole outline (`TextLayout.wordBoxes`). Sizes raised for 9:16 after the first look (they read small on a phone).
