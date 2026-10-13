# Owner's reference films (audio-only explainers) — what the engine must learn

The reference videos themselves are never stored in the repo. These notes are the knowledge the engine
encodes as code (film grammars, primitives, transitions, art direction rules) and the jury checks.

## Reference 1 — "the difference between model tiers" (64 s, calm diagram explainer)
- Grammar **diagram chapters**: one subject per chapter, its name as the big heading, a dash mark that fills per
  chapter in the chapter's accent; warm paper background; generous whitespace; UI-like pieces (chips, tiles,
  meters, cards, bars, block rows, branches, hand-drawn tangle).
- Every sentence is a visual **metaphor**, not its words: intern → profile card with a tag; light repetitive work →
  task tiles ticked one by one; usage drains slowly → meter 100 → 98 → 95; best path → branches converging on a
  "task" chip; deep problems → tangled scribble; strongest → bars by tier with a badge; only the hardest part →
  a block row in two colours.
- The chapter's stage **persists and builds** (add, tick, fill, morph, dim); hard cuts only between chapters.
- Captions: a few words at a time in a dark pill on a thin accent band near the bottom.

## Reference 2 — "the Milli Gold case" (13 s, illustrated news explainer)
- Grammar **illustrated news**: one scene per sentence, fast (2–3 s per scene), each scene = a headline + one
  hero illustration + at most one graphic device.
- **Look**: monochrome pencil-sketch illustrations (a safe, a worried man with a phone, three people at a
  storefront) on white, with ONE brand accent (saturated yellow) and one dark (navy) — the accent fills big
  shapes (a circle, the scene background), the dark marks the key phrase.
- **Headline typography**: kicker word on top in a light weight, the main line below; words reveal one by one
  (fade/slide), the punchline sits on a navy highlight box ("ولی پولش هنوز واریز نشده!!"), a lighter grey
  second line under the headline.
- **Motion**:
  - hero objects rise from below the frame with a spring overshoot and settle;
  - a giant accent circle wipes in from the bottom and becomes the next scene's background (circle-mask
    transition);
  - the next scene pushes the previous one aside (lateral push), or the old scene peels away;
  - a speech bubble pops near the phone ("در انتظار تسویه...");
  - a dashed annotation box draws on with anchor dots, like a designer's selection box ("مهلت اعلامی برداشت
    بعد از تایید");
  - a calendar tile counts down 3 → 2 → 1 "business days" with a page-curl peel per number;
  - accent stars fly in at the corners as punctuation;
  - the subject's name appears on objects in the illustrations (sign, safe) only because the narration names it; never a watermark or credit.
- **Pacing**: a new visual idea every 1–2 s; nothing static for more than ~2.5 s.

## What the engine needs from these (encoded in code, checked by the jury)
- Several **film grammars** chosen per narration (diagram chapters, illustrated news, …), each with its own
  pacing, layout and transition rules — not one template.
- An **illustration layer**: consistent-style illustrations of people/objects/places per sentence (CC0 hand-drawn
  libraries that compose characters and poses; an on-device image generator with a commercially licensed model
  where the device can run it; the user's own photos turned into the film's style), all in the film's art style
  (e.g. pencil sketch + one accent).
- **Transitions**: circle-mask wipe, push, peel/page-curl, morph; per-grammar defaults.
- **Devices**: highlight box behind a phrase, word-by-word headline reveal, speech bubble, dashed annotation box,
  flip/countdown calendar tile, corner accents, spring rise.
- **Art direction**: brand colour pair (accent + dark) from the brief/brand when given, else from the topic.
