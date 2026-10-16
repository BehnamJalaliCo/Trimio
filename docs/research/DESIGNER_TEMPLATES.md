# Designers' work in the edit — round 10

The owner's verdict on the storyboard (1.0.30): captions heavy and badly placed, words misspelled, motion not
smooth or clean — and, underneath, that an engine of hand-written rules draws like a programmer. What a
professional edit has that ours did not is **work made by designers in After Effects**. This round lets the
engine perform designers' work instead of only drawing its own, and fixes what the owner named.

## 1. What could be measured from here

- **Viral reels**: YouTube and TikTok refuse downloads from this cloud machine ("confirm you're not a bot"), so
  the 100-reel frame measurement could not run here. What the engine does measure is the owner's own reference
  edit (`REFERENCE_EDL.md`: the cut, the cards, a bed ~10 dB under the voice that never drops out).
- **Designers' animations**: the open Lottie library (After Effects exports) answers its public search with no key.
  50 searches (accents, titles, lower thirds, transitions, money, crypto…) gave 1 747 small documents.
  The engine's reader draws **1 124** of them faithfully (64 %); the rest use image layers (234), layer effects (179),
  expressions (73), polystars, repeaters or 3D. Text templates are rare (≈5 %) and most are number counters;
  real lower thirds and title packs live in paid After Effects formats the engine cannot read.

## 2. What the engine does with them

- **Text slots** (`Lottie.kt`, `LottiePainter`, `MotionRenderer.drawSlot`): a template's text layers are slots the
  engine fills with the edit's own words, set in the brand's type at the designer's size, colour and justification
  (smaller when longer, so they keep to the designer's space), moved **word by word** by the template's own range
  selectors (a Persian word never breaks into letters). The designer's placeholder is never drawn.
- **Rest and stretch** (`TemplateRecipes.clock`): a template's rest is found (a marker, else its longest stillness)
  and stretched so it builds in as the words start and leaves as they end.
- **Accents** (`TemplateRecipes.accent`, `BeatScore.accent`): placed from where the beat really landed — an
  underline under a title, a ring round a number, an arrow on the reading side (mirrored in Persian), a burst at the
  corner, a hand tapping the ask, a tick/cross/warning beside a gain or a loss.
- **The shelf** (`TemplateShelf`, `files/templates/curated.json`): 50 accents chosen by eye from contact sheets over
  the owner's footage (8 roles), fetched on demand from the library's CDN and kept on the device.
- **The editor** (`Dresser`): dresses the beats that carry the piece by what they are (recipe and meaning, never the
  model's mood), at most six, never two within four seconds, one accent per role through the whole piece.
- **Page memory**: what an edit wore (accents and caption style) is remembered; the next edits wear others.

## 3. What the owner named

- **Captions**: three words, lighter (smaller, no plate: the word that matters inked by its meaning), never ending
  on a word that leans on the next («همه‌ی», «یا», «به») nor opening on one that leans back («رو», «هم»); a verb is
  never the highlighted word. Three caption styles rotate across the page's edits (phrase, bold pop, highlight).
- **Empty chat window**: it opens as its first message is asked.
- **Sound**: the bed sits 14 dB under the voice and only breathes up 3 dB between phrases (it used to sink 20 dB
  under every word and swell in every gap).
- **The cut**: a line whose gist (the reading's English summary) an earlier line already said, and whose words
  partly repeat it, goes. On the owner's reel the director left most of the long rant without a gist, so the cut
  still keeps it: reaching the reference's 97 s needs a reading that understands every line.
