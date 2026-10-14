# YouTube long-form — research and spec (phase 9.7)

Owner (14 Oct 2026): long videos (10–60 min, 16:9) need their own edit: one that only needs accurate
subtitles and no heavy editing, plus what YouTube videos carry (watermark, a subscribe ask, …).
No silence removal (the owner's rule).

## What YouTube itself requires

| item | rule | source |
|---|---|---|
| Chapters | in the description; first stamp `0:00`; at least 3, ascending; each ≥ 10 s; `M:SS` under an hour, `H:MM:SS` over; a broken rule hides them silently | YouTube Help via the guides below |
| End screen | the last 5–20 s; video ≥ 25 s; up to 4 elements, at least one video or playlist; elements keep off the edges; watermarks and overlays vanish while it shows | idem |
| Captions | SRT or WebVTT upload, or burned in | YouTube Studio |
| Frame | 16:9, 1080p or 4K; loudness around −14 LUFS (YouTube normalises louder masters down) | — |

## What professional long-form edits do (creator and editor guides, 2026)

- **A hook in the first 30 s**, then the first deliberate change around 25–35 s, where the first drop-off is.
- **A visual change every 15–30 s** for a general audience — a cut between a medium and a slightly closer framing of
  the same take (a "zoom cut"), b-roll, a text callout. Over-editing lowers retention for viewers 25+: long-form
  stays calmer than a reel.
- **Text callouts** for numbers, names and key points, appearing as they are said.
- **Sound**: a soft whoosh on a zoom cut, music ducked under the voice.
- **Chapter cards** between sections; **lower thirds** for the speaker.
- **The subscribe ask** after the first real value is delivered (not in the first seconds), short and animated, once
  mid-video at most.
- **End screen** space kept clean at the end.
- **Open loops** across sections (a teaser of what is coming).

## Spec for Trimio

Two edit kinds for a landscape source, chosen on the create screen:

1. **YouTube — subtitles only** («یوتیوب ساده»): the source untouched (no cuts, no zooms, no graphics), accurate
   captions burned in (phrase captions, the safe area above YouTube's controls) or exported as SRT/VTT, chapters for
   the description. Fast enough for 60 minutes: speech + spelling + chapters only; the director reads the whole
   piece once (chapters, title) instead of line by line.
2. **YouTube — pro** («یوتیوب حرفه‌ای»): all of the above, plus
   - channel watermark (the creator's logo, a corner, low opacity, off during the end screen),
   - one subscribe/like reminder after the first value point (≥ 60 s in, a calm moment),
   - chapter cards at chapter starts and a chapter progress bar,
   - lower third with the speaker's name when they introduce themselves,
   - zoom cuts at most every 20–30 s on emphasis (never inside a word, never at a cut),
   - text callouts for figures and names,
   - the end screen: last 20 s kept clear for YouTube's elements,
   - a title and three thumbnail lines suggested, chapter list ready to paste, and Shorts: the 3 strongest 30–60 s
     moments cut to 9:16.

Acceptance: a real 30-minute video of the owner on the phone within the 9.5 speed target, zero spelling faults on
screen, chapters valid by YouTube's rules, the jury ≥ 9.5.

## Sources

- https://blogs.dickinson.edu/mediacenter/2026/01/12/youtube-adding-video-chapters/
- https://gling.ai/blog/creating-youtube-chapters-a-complete-guide
- https://www.heygen.com/blog/youtube-end-screens-guide
- https://invideo.io/blog/youtube-end-screen/
- https://air.io/en/youtube-hacks/advanced-retention-editing-cutting-patterns-that-keep-viewers-past-minute-8
- https://tubemilestone.com/blog/edit-youtube-videos-retention
- https://joyspace.ai/pattern-interrupt-reset-attention-span
