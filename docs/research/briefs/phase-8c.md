# Phase 8c — audio-only explainer films (shared brief)

Read first: scratchpad/AGENT_BRIEF.md (rules still apply: Gradle only via `timeout <s> flock <lock> ./gradlew ...`,
detekt limits, no commits, no fonts talk, never commit user media/models/keys) and docs/ROADMAP.md "فاز ۸ج".
Version 1.0.14 (phase 8b) is released; the tree is green (all jvmTest, golden archive, detekt).

## The owner's goal (verbatim spirit)
"If you give it a thousand different voices, it should make a thousand different films — each a cinematic
explainer, so good that nobody can tell Claude didn't edit it." Input: an audio-only narration (Persian first,
also English). Output: a vertical (and 16:9) explainer film like the owner's reference.

## The reference (owner's video, NEVER commit or copy into the repo)
/root/.claude/uploads/7e03ed16-d9d4-5525-aa3f-2586cff127ac/17d67c74-VID_20261012_142733_560.mp4 (64 s, Persian narration
about the difference between AI model tiers). Frame sheets: scratchpad/ref/sheet1.png, sheet2.png — look at them.
What makes it work:
- Chapters: one subject per chapter (its name is the big heading at the top, with a small progress-dash mark that fills
  per chapter and takes the chapter's accent colour). Calm warm paper background, one serif-like display heading.
- Every sentence gets a visual METAPHOR, not the words: "like an intern" → a profile card with a tag; "light repetitive
  work" → a row of task tiles that get ticked one by one; "usage limit drains slowly" → a meter at 100% → 98% → 95%;
  "most everyday tasks" → icon tiles (writing, code, analysis, chat) building in; "thinks first, finds the best path"
  → branching paths converging on a "task" chip with the best path highlighted; "deep complex problems" → a tangled
  hand-drawn scribble; "strongest" → bars by tier with the last one tallest + badge; "use it only for the hardest part"
  → a row of blocks where the first few take one colour and the rest another.
- The stage of a chapter persists and BUILDS: elements are added, ticked, filled, morphed, dimmed — never a hard cut
  between sentences inside a chapter. Between chapters the old stage clears and the new heading arrives.
- Captions: a few words at a time on a thin accent band near the bottom, in a dark pill.
- Small, crisp UI-like pieces (chips, tiles, meters, cards) with generous whitespace; one accent per chapter;
  everything centred in a safe area; nothing touches the frame edge; Persian text right-to-left with Persian digits.
- No company names, logos or credits may appear in OUR output (the reference shows brand names; we never do unless
  the narration itself names them, and then only as plain text).

## Contract (already committed, 9f4d383)
engine/motion/src/commonMain/kotlin/io/trimio/engine/motion/explainer/ExplainerFilm.kt:
ExplainerFilm(art, chapters, captions, language) / ArtDirection / Chapter / Beat(at, until, line, op, element,
primitive, content, metaphor) / StageOp (add build check fill highlight dim morph replace clear) / BeatContent /
Item / Primitive (36 kinds). The renderer agent owns this file; small additive changes are fine (tell the supervisor
in your report). Don't rename or remove anything without asking via your final report.

## Agents and ownership
- STAGE (engine/motion/.../explainer/**, its tests, docs/screenshots/explainer-*.png): renders an ExplainerFilm.
- SCREENWRITER (engine/autopilot/.../explainer/**, its tests, jury judges for explainer films, pipeline wiring for
  AudioOnly input, the app's mode toggle if any): writes an ExplainerFilm from a transcript.
Disk is tight (~1 GB free): keep outputs small, delete large temporaries, put them in scratchpad/8c-<you>/.
Two agents share the Gradle lock: keep runs short and targeted; don't hold the lock with long proof runs.
