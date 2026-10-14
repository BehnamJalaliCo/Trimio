# Phase 9 — progress log

Owner's order (14 Oct 2026): review the latest release deeply first, then do all of phase 9 myself
(no agents), finish it, deliver the last version. YouTube: no silence removal.

## 0. Review of 1.0.18

- CI red since #113 (13 Oct): the GitHub runner shut down mid-tests. Cause: the new test modules
  (illustration, perception, retrieval, onnx) run beside the heavy ones (autopilot 4 GB, production,
  motion 3 GB) → 16 GB runner out of memory. Fix: root `build.gradle.kts` `HeavyTests` build service,
  heavy test JVMs take turns.
- Web red: JVM-only calls in common code (`putIfAbsent`, `sortedSetOf`, `getOrDefault`, `String.format`)
  in GeneratorSource, explainer Director/Chapters/Drafts/StageWriter, ModelManagerScreen. Fixed;
  `compileKotlinWasmJs` green locally (the yarn download in `wasmJsBrowserDistribution` needs CI's network).

## 9.5 Android speed — decisions

- KleidiAI (`GGML_CPU_KLEIDIAI=ON`) in both Android builds: its kernels pick SME2 → SME → i8mm →
  dotprod at run time (getauxval), so one armv8.2 build stays safe on every phone and fast on new ones.
- Repack/KleidiAI cover Q4_0 and Q8_0, not Q5_0 → whisper large-v3 q5_0 runs the slow generic path.
- Placement measured on the device (CPU vs GPU) instead of "GPU whenever the driver loads".
- Report: engine facts at the first model call, memory per step (RSS, GPU, free), thermal.

### Done in code (not yet built for Android)

- `GGML_CPU_KLEIDIAI=ON` in androidApp and androidGpu.
- `Placement` (engine/llm): on a GPU build, the first load of each model probes GPU and CPU (~450 tokens
  read, 32 written) and keeps the faster; the GPU must beat the CPU by 15 % on a typical call (800 read,
  250 written). Kept in `<model>.placement`, measured again when the engine build changes. The line
  goes into the report (`llm.placement`) and the CPU reason.
- Report: engine facts after the first model call and after whisper (`RunRecorder.liveRuntime`); every
  step carries RSS, GPU MB (`Debug.MemoryInfo summary.graphics`), system free MB and heat (thermal
  status, headroom, CPU clocks); MEMORY sampled every 30 s inside long steps; in-app report shows them.
- Understanding drafts: `UnderstandingDraft` checkpointed after the whole-piece call and every group;
  a killed run resumes (Checkpoints.Saved.draft → Autopilot.Request.draft). Test: UnderstandingDraftTest.
- Chunking left as is (GROUP 3, 8 min budget): the time went into speed, not the number of calls.

### Done in code after that (not yet compiled — compile and test first!)

- Memory gate: no GPU load without model size + 2 GB `MemAvailable` (cached GPU choices too).
- `LlamaProbe` (engine/llm): one timing (threads, GPU layers, MTP draft); `Placement.Choice` keeps threads and draft.
- Speed test: `engine/models/SpeedTest` (+`SpeedRow`), `engine/production/DeviceSpeedTest` (memory copy speed, director
  at 1/half/fast-1/fast cores + GPU + MTP, Whisper at fast-1/fast; best kept in `<model>.placement` and
  `<whisper>.threads` via `SpeechThreads`), `LocalDirectors.speedTest`, DI on Android and desktop, settings button and
  rows (`SettingsViewModel.testSpeed`, `SpeedTestPanel` in ModelManagerScreen).
- YouTube (9.7): `EditKind` in ProductionRequest; create screen `EditChoiceRow` (ریلز / یوتیوب / یوتیوب حرفه‌ای);
  `longform/` in autopilot: `YouTubeChapters`, `SubtitleFiles` (SRT/VTT), `ShortPicker`, `LongFormPilot` (no cuts,
  subtitles recipe, pro: watermark from the logo attachment, one subscribe after the first minute, chapter cards,
  push-ins every ~30 s, end screen 20 s kept clear; director titles chapters + 3 titles/thumbnail lines); motion recipes
  `subtitles` (TextRecipes) and `YouTubeRecipes` (subscribe, watermark, chapter); caption words cap 8 → 12;
  `YouTubeFiles` writes `<out>.srt/.vtt/.youtube.txt`; result screen «برای یوتیوب» card with copy; project delete
  removes them. Test: `longform/LongFormTest`.
- 9.3: speakers — `AddOnPass.speakers` (pyannote + CAM++ add-ons) → `Voice` turns → `Compiler.Input.voices` → caption
  colour per voice (`Craft.voice`, `Cue.speaker`); kept in `Delivery.voices`. Beats — `AddOnPass.beats` (Beat This!) →
  `SourceMusic.tempo` (steady 50–200 bpm over 60 % of the video) → reel brief gets "no music", score bpm = the video's own.
- Live percent fix (115 sp, 3-digit fit, one line).

## Next steps

1. Compile everything (`compileKotlinJvm`, `compileKotlinWasmJs`, Android `compileDebugKotlin`), fix, detekt.
2. Tests: llm PlacementTest, autopilot UnderstandingDraftTest + longform, production DiagnosticsTest, motion (recipes
   list grew — gallery/golden tests), screenshot tests for create/stream/settings.
3. Remaining: ORT slim (assess), 9B music-mood fixer, image/audio matching, 9.1 explainer, 9.2 SDXS, real-video runs.
