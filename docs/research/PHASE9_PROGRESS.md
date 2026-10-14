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

### Also in code (not compiled yet)

- 9.6 music: `Judge.remood` + `Jury.Revision.music`; MusicJudge offers the calm/driving/named mood for "a calm brief under
  …" findings (the 9B gap); the loop re-arranges the bed. 8d.3: `MusicShelf` (retrieval `PictureRecall.music`) names the
  bed by meaning when the brief leaves it open and it fits the energy; DI shares one recall for pictures and music.
- 9.2: `native/third_party/stable-diffusion.cpp` (submodule, d763dae) + `native/sd/trimio_sd_jni.cpp` + CMake `TRIMIO_SD`
  (Android app ON, Windows CPU ON, Vulkan/macOS/androidGpu/host OFF); `SdNative`/`SdxsEngine` (engine/production
  jvmCommonMain, package illustration.generate); `ProductionPlatform.diffusion()`, `LocalDirectors.diffusion`, DI;
  IllustratedStage gets `Illustrator.create(engine = platform.diffusion())`, `Illustrator.release()` after each film,
  ExplainerPilot frees the director before drawing. CI checkouts: `submodules: true` (sd.cpp's own are not needed).
  Terms: clause 8 (downloaded models, OpenRAIL++-M).
- 9.1: explainer `Production.restage` → 16:9 exports re-laid by the stage.
- ORT slim build: not done (needs ORT-format models and a source build; ~10 MB); reported as a decision.

## Next steps

1. Compile everything (`compileKotlinJvm`, `compileKotlinWasmJs`, Android `compileDebugKotlin`), fix, detekt.
2. Tests: llm PlacementTest, autopilot UnderstandingDraftTest + longform, production DiagnosticsTest, motion (recipes
   list grew — gallery/golden tests), screenshot tests for create/stream/settings.
3. Remaining: ORT slim (assess), 9B music-mood fixer, image/audio matching, 9.1 explainer, 9.2 SDXS, real-video runs.

## 14 Oct — verification pass

- Detekt clean: `Productions.work` split (`directorOf`, `direct`, `Material`, `persian`); targeted tests green
  (PlacementTest, UnderstandingDraftTest, LongFormTest, JuryTest, DiagnosticsTest).
- YouTube proof on the owner's 155 s talk (`LongFormProofTest`, 16:9, Qwen3.5-9B director on the host): frames seen.
  Found and fixed on real output:
  - the chapter card and the subscribe ask were up together and stacked over the face → the ask never overlaps a card;
  - without a director a card showed the chapter's opening words (a broken sentence) → cards only for titles the
    director wrote (`Chapter.named`); the description still lists every chapter;
  - this speaker seldom ends a sentence → no subscribe ask and no Shorts; both now also take a breath as a boundary;
  - the 9B's titles came back empty (grammar) or with a leading «, » → `minItems/maxItems 3`, `clean()`.
  - Added (9.7 «عنوان، عدد و نام»): the video's title as it opens (lower-third, director-written only); spoken
    figures as counters and Latin brand names with a real mark (`SpokenFacts`), one every 30 s, never beside
    another top device, never on the end screen; Persian names left alone («سیگنال» is not the messenger).
  - Result: title 0:02, counter «۲٬۷۰۰٬۰۰۰» at 0:19, subscribe 0:40, cards «بررسی واقعیت ربات تریدر» 1:09 and
    «شفافیت و عضویت در کانال» 1:37; chapters 0:00/1:08/1:36; 2 Shorts.
- Long videos (30–60 min) on the phone: the voice was decoded whole at 48 kHz (an hour = 690 MB, two copies) → OOM.
  Now a long-form edit reads its voice at 16 kHz, brought down while decoding (`AndroidAudioDecoder.decodeMono(rate)`,
  blocks joined once: 1× memory), whisper takes it as is, nothing is mastered (`LoudnessMeter` only), and the file
  carries the recording's own AAC track untouched (`ProductionPlatform.render(sourceAudio)`, Android `SourceAudio`
  streamed sample by sample; desktop ffmpeg maps the source's audio). A retouched (cut) long-form edit uses its
  soundtrack again (`Production.sourceSound`).
- 9.3 cuts on the beat: with the video's own music, every cut skips whole beats (`EditPlan.onBeat`), the next piece
  starting a little earlier in its pause, so the music runs on in time across each cut.
- 9.2 SDXS through JNI on the host (`SdxsEngineTest`): the engine called into `trimio_sd` without loading it (only
  `LocalDirectors` touched `SdNative.available`) → `SdxsEngine` checks it first. sd.cpp logs go to logcat/stderr.
- Owner on Windows (1.0.18, 35B-A3B installed): the report said «rules (no model)». Cause: `LocalModels.bestSpec`
  took the largest *comfortable* model, else the smallest *calibrated* one; the 35B-A3B is experimental (capped at
  Experimental), so with only it installed the rules directed. Fixed: an installed director always directs (the
  smallest experimental one last). A kept reading is reused only by the director that made it (`Saved.by`), and a
  rules-only run says so under the jury on the build and result screens (`RulesOnly`, reason in the report:
  none installed / too big for this memory). The jury's judges are rule checks: misheard real words are caught
  only by the director's proofreading, which is why a rules-only run scored 10 over visible mistakes.
- 9.2 decision: stable-diffusion.cpp (d763dae) on our ggml (llama.cpp baef3ed) aborts in the text encoder's
  segmented runner (`ggml_runner_cut:clip_text.prelude|x|`, an ADD whose dst has nb0 = 0); flash attention off and
  segmented compute off do not avoid it. The engine is left out of the builds (`TRIMIO_SD=OFF`) and the generator
  add-ons out of the catalogue until it runs; the code stays for that work.
- Release 1.0.19 pushed (run 19).

## 9.9 Subtitles (owner's order, 14 Oct, night)

- Research in `docs/research/SUBTITLES.md`. Six styles (`SubtitleStyle`, `SubtitleRecipes`): classic, box, karaoke,
  bold pop, highlight, minimal; each on one of the brand faces already sealed in the app; word ink and blocks end with
  the word (`Decoration.Ink/Block.until`).
- Plain reel (`EditKind.Subtitles`, `SubtitleReel`): silences cut, punch-in at each cut, words in the picked style,
  voice mastered, nothing else. Create screen: «ریلز ساده» and «سبک زیرنویس» (every subtitled kind).
- Real check (`SubtitleProofTest`, owner's 155 s talk): 29 cuts, 155 → 142 s; frames of every style reviewed.
  Fixed from the frames: a ZWNJ word («می‌خوره») half highlighted in bold pop (word boxes from the whole word's
  outline); sizes raised for 9:16.
- CI 118 (1.0.19) failed two FamiliesTest checks: the YouTube chapter card was registered as «chapter», the name of
  the reels' chapter family, and hid it (reels asking for a chapter card got the YouTube one). Renamed `yt-chapter`,
  `yt-subscribe`, `yt-watermark`; `RecipeNamesTest` keeps names unique.


## 14 Oct — every kind reopens and edits; the explainer on the owner's voice

- Full local suite (2 workers, 54 min): green except two — `PlaybackTest` (a real-time check; 57 of 60 pictures while
  three test JVMs shared four cores; passes alone) and the settings screenshot test, which still saw the dialog as
  private (fixed before; passes).
- Found while checking: YouTube and plain-reel edits kept no workbench. After a restart the editor and exports said
  «made by an earlier version»; in the same session the editor said edits «don't run here». Fixed: both keep a plain
  workbench (`Workbench.plain`: no reel critic or jury, which would add a hook, punch-ins and a call to action). A
  YouTube video's is `recorded`: its recording's own track and whole cut stay, nothing is mastered. `Delivery` keeps
  `plain`, `recorded`, `duration`. The workbench now passes the speakers to the compiler (a retouched edit kept its
  speaker colours only before a change).
- 9.1 «باز شدن در ادیتور»: an explainer film keeps its `FilmCut` (film, music, words, seed, frame) in the checkpoint
  and reopens through `FilmBench` (pictures fetched again, no director). The editor changes its palette (13), music,
  level and whooshes; the film's beats stay the director's. Exports in 16:9 after a restart work through it.
- Editor: only the tools an edit has (film: palette and sound; YouTube: text without cut or hit, style, elements).
- Create screen (seen on the shots): four edit kinds in one row cut every name and note («یوتیوب حرفه‌ای» showed as
  «یوتیوب», notes cut). Now «نوع ادیت» is a section of the brief, two by two, names whole; the subtitle styles under it.
- 9.1 run on the owner's 64 s sample voice (rules director, `ExplainerFilmProofTest.aRealNarration`): 6 chapters,
  33 beats, the end card «فالو کنید» from 62 s, 7 misheard words spelled (کاراموز → کارآموز …). The faded moments of the
  8c review (10.8, 21.6, 54.0 s) are gone. Fixed: unticked task tiles were track-on-card (near invisible on dark
  palettes) for five seconds; their lines now read before they are ticked.

## 14 Oct — the owner's two new orders: image generation, own directors

- Owner: «ساخت تصویر باید کامل و حرفه‌ای کار کند». Removing the engine (principle 4) was undone. stable-diffusion.cpp is
  now configured alone on its own pinned ggml (`leejet/ggml` d25b121, the submodule of the submodule): the assertion
  came from running its patched ops on llama.cpp's ggml. One configure cannot hold two `ggml` targets, so the phone
  library comes from a module of its own (`:androidSd`), Windows builds it in its own CPU and Vulkan steps, and CI
  fetches the nested ggml. Every symbol but the JNI entry points is hidden, so the three engines load side by side.
- On the host: SDXS-512 draws again (≈10 s a picture on four cores); its pictures are recognisable but not
  professional (a camel with wrong legs). `SdEngine` drives both generators: SDXS (one file) and FLUX.2 klein 4B (the
  diffusion model, Qwen3-4B text encoder and the small decoder; the library's sketch as its init picture, the film's
  first picture as its style reference). The illustrator picks klein when all three files are installed.
- Owner: «مدل دلخواه کارگردان، فقط ویندوز». `OwnModels`: a GGUF from the computer's own disk becomes a director (kept
  where it lies, never copied or deleted; the same file once; projectors, other files and files under 200 MB refused
  with their reason, said in the creator's language). Its family's profile comes from its file name (Qwen3 answers past
  its reasoning block). The models screen offers «افزودن مدل از روی سیستم» on Windows only; installed directors gained
  a «استفاده» button (before, only the recommended card could switch the director).
- Speed test on the host (Qwen3.5-9B, large-v3): memory 11.3 GB/s; the director 1–4 cores (4 best: 28.5 read,
  3.8 written tokens/s); Whisper 3 and 4 cores (4 best); kept beside the models. MTP: the catalogue's GGUFs carry no
  MTP head, so the row does not appear; a model with one is tried and kept only when it writes 10 % faster.
- The host's native libraries had been built for another CPU (the session moved machines): SIGILL in ggml. Rebuilt.
- FLUX.2 klein 4B on the host (four Xeon cores, CPU build, 768 px): the camel and the rocket clean, correct and in the
  film's pencil look (SDXS drew the camel with broken legs and no man in the trader scene). About 8–10 minutes a
  picture here, more when the letter-mark check makes it draw again: on a processor alone it now draws at 512 px, and
  every denoising step moves the build bar (`DrawProgress`), so the watchdog never takes a long picture for a stall.
  Memory: 6.8 GB resident of which 4.3 GB the file's mapped pages; with its encoder and decoder it rates «جا می‌شود» on a
  12 GB phone (offered there too, the director freed first) and comfortable on 16 GB and the owner's laptop. Windows
  also gets a Vulkan build of the engine (a laptop GPU draws in seconds).
- Drawn pictures are kept beside the checkpoints (`FilmPictureFiles`): a film opened again after a restart, or exported
  in 16:9, takes them from disk instead of drawing them again. The report says how many each source drew.
- 9.3 measured (`AddOnsOnRealVideosRun`, the add-ons downloaded and re-hashed): the owner's five videos — no tempo, one
  voice each (nothing changes their edits). Positive cases built from the owner's talk: two voices taking turns (the
  second pitched) → two speakers, 16.7 and 15.2 s, right; a 100 bpm drum bed under the talk → Beat This! heard 106 of 107
  beats but the interval rule (8 % spread) said «no music» (missed beats and voice onsets). Now a beat grid (tempo and
  phase holding the most beats within 70 ms, most of its lines heard): 100.0 bpm on the bed (60 % / 60 %), none on the
  five talks (13–45 % of beats, a third of the lines). `SourceMusicTest` keeps both sides.

## 14 Oct — pictures drawn during film making, on the owner's news voice

- The news film (`ExplainerFilmProofTest.aVoiceAlone…`, FLUX.2 klein installed) drew 5 of 5 scenes. Found on the frames
  and fixed: headlines cut by letters («… کاربران را») — now the whole sentence up to nine words and sixty letters, cut
  only by whole words where a phrase ends; «و» between nouns was dropped («طلا دلار»); a scene with no request of its
  own («جلوی دفتر صرافی صف کشیده‌اند») reused the money bag before it; «office building» became a hammer and wrench (a
  word stem), a businessman was not a person.
- The illustrator now: a queue or a protest (an action no pose shows) caps the library's fit below good enough, so the
  generator draws it; a building among the objects stands behind the people; a library picture already shown in the
  film is drawn fresh by the generator (without one, the repeat stays). klein wrote «CUNPIA EXOAPNCONFE» on the
  exchange office's sign (a distilled model ignores the negative prompt; the letter check missed letters joined to
  the sign's frame): a shop, office or bank is now asked for with a plain blank signboard — drawn again, clean.
- Final run: whole headlines, the queue in front of the office drawn by klein in the film's pencil look, every other
  scene from the library; the report lists every picture and its source.

## 14 Oct — Whisper turbo against large-v3 on the owner's five talks (`SpeechModelsOnRealVideosRun`)

- Host, four cores, beam 3: large-v3 Q5_0 65 / 47 / 194 / 174 / 437 s for 26 / 13 / 64 / 67 / 155 s of sound; turbo Q8_0
  30 / 21 / 74 / 70 / 153 s (2.2–2.9× faster).
- Word by word against large-v3: most differences are spellings («کاربر‌ها» / «کاربرا», «اسمشو» / «اسمش رو»), some
  turbo's better («جیپیتی»), but turbo left out a whole phrase of the 155 s talk («سرمایه اولیه میدیم پنجاه دولار کف
  ورودیمونه») and two words of the 13 s one. The rule is not one word lost: turbo is not the default. The phone no longer
  prefers it either; it takes the most accurate speech model installed that fits, as the desktop does.
- Left for the phone: large-v3 on the NPU (ORT QNN) or a Q8_0 large-v3 for the KleidiAI path (needs our own server to
  host it, phase 10).

## 14 Oct — verified before 1.0.20

- Local: `detekt jvmTest :server:test` (every module) green but one detekt finding (headline complexity, fixed), then
  detekt and the changed modules again: autopilot 197, production 54, asr 14 tests, no failure; `compileKotlinWasmJs` green.
- Real output seen: the news film with the studio illustrator (frames above), the queue scene drawn alone, the speech A/B.

## 14 Oct — the owner on 1.0.20: what did not reach the phone, and the speed test as the build's setting

- The owner could not find the speed test: it sat above the recommendation card at the top of the models screen. It is
  also under the director model in Settings now (rendered as the owner's phone: `ModelManagerScreenshotTest.ownersPhone`).
- The build percentage broke over two lines on the phone («۱» above «۲») although the earlier fix asked for one line and
  rendered fine on the desktop: Android's line breaker split it. Each digit is its own text now (`DisplayFigure`).
- Checked in the released APK itself: R8 had dropped `SdNative.StepListener.onStep` (the picture engine's progress), so a
  picture taking minutes moved no progress on a phone. Kept now. From now on the APK is inspected before links are given.
- The run report did not move while Whisper read (no detail on the step): the percent is its detail now, so the report
  and its 30 s memory and heat samples move during a long transcription.
- Owner: «سنجش سرعت را زدیم، بهترین را ملاک ساخت قرار بده؛ درصد صفر تا صد دقیق». The chosen ways are kept
  (`speed-profile.json`) and shown in Settings as the way every build runs; the picture engine takes the director's
  fastest processor thread count. Measured on the test machine (Qwen3-4B, large-v3): the first percentage (shares)
  sat at 14 % for five minutes on the single-core call and jumped 61 → 100; now it follows the clock (expected times
  re-estimated as calls end) and went 0 → 100 evenly in 219 s, about half a percent a second. The single-core call
  is gone on multi-core devices (never the fastest; 3.7 min instead of 11).

## 14 Oct — the owner on 1.0.21: the GPU never ran, and a kill at «برنامهٔ ادیت» 52 %

- The speed test never touched the GPU: the director was timed all on it or not at all (the phone's free memory held
  only part of the model), and the picture engine had no GPU build. Now the director is timed on the processor (three
  or four thread counts) and with a quarter, half, three quarters and all of the layers the free memory holds on the
  GPU, the rest on the processor; each way three times, the median kept (every pass starts with its own words, so the
  prompt cache cannot fake a fast read). The GPU is kept only when it wins by 15 %. The picture engine has an OpenCL
  build for Adreno (`:androidSdGpu`) and is timed on the processor, mixed (text encoder on the processor) and on the
  GPU when it fits. The chosen ways are shown with a check mark under «هر ساخت با این تنظیم اجرا می‌شود».
- Without a crash: the measuring runs in its own process (`:bench`). A way that the system kills there becomes a row
  «کرش کرد»; the app and the rest of the test go on, and a way that hangs is ended after 15 minutes.
- The kill (1.0.21, S26 Ultra, «killed for low memory», 52 %): 52 % is the director's «pictures» step. At that moment
  the phone held the director, its eyes' projector (about 1 GB, loaded for the speaker and never freed), the art
  library's embedder and reranker (loaded for the music pick and kept), the live preview's decoder, and the picture
  engine loading. Now the eyes' projector is freed after each look (and after the speaker is found), the art library
  after its pick and after the pictures, and the system's own low-memory warning (`onTrimMemory`) makes a running
  edit drop its optional parts at once (the look, web animations, live previews). The reading is saved the moment it is
  done, and the attempt after a kill inside the director is a lean run (no eyes, no previews, offline visuals) that
  starts from the plan; the report says so («lean run»). Host test: a run whose last attempt died «in pictures» in
  another process finishes, lean, without previews (`aRunKilledForMemoryInsideTheDirectorFinishesLean`).

## 14 Oct — the owner on 1.0.22 and 1.0.23: a speed test of half an hour

- 1.0.22: 21 % after 25 minutes. Every way loaded the director again (eight loads), timed four passes of 1000 tokens,
  the picture model loaded three times for two drawings, a GPU way could hang for 15 minutes. 1.0.23: one load per
  placement for every thread count (`LlamaNative.setThreads`), the better of two 400-token passes, the GPU at all the
  layers that fit and half, MTP only when the model has the head, one small one-step picture, GPU ways stopped at a
  minute plus three times the processor's time. The test machine: 318 s instead of 860.
- 1.0.23 on the phone: 35 minutes and 10 % of the battery; the director «crashed», Whisper 9.2× and 6× the sound
  (1.1× in 1.0.21, measured inside the app). The measuring process was bound as an ordinary service: a background
  process's cores and its place in the low-memory killer's order. ggml's threads meet at a spinning barrier, so eight
  threads on fewer cores wait on each other (this repo's 4-core server once took 600 s for 4.5 s of work that way). And
  Whisper ran the count kept by the last test, not the one being timed. Now the process is bound `BIND_IMPORTANT` (it
  runs as the app in front does), the screen stays on during the test, a way the system ends says why in its row
  (low memory or a crash), Whisper runs the exact count timed — and every build caps the director's and Whisper's
  threads to the cores Android allows the process at that moment (`Cpus_allowed_list`: fewer with the screen off),
  which the run report now shows.

## 15 Oct — the owner's speed test on 1.0.26: right numbers, a jump at 75 % and a frozen screen

- The rows are sane now: the director on the processor at 8 threads (read 90, write 10 tokens/s) beat 40 of 43 layers on
  the GPU (read 104, write 4.5); Whisper 2.1× the sound at 8 threads; the picture engine mixed (text encoder on the
  processor, the rest on the GPU) 47.6 s against 71.3 s on the processor alone — the GPU does draw.
- 75 % → done at once: the plan held «all on the GPU» for the picture engine, which the free memory could not hold, and
  it left the plan only when its turn came. The picture engine's ways are now decided right after the memory row.
- The phone froze for seconds (a screenshot too) while the picture engine drew on the GPU: the screen draws with the
  same Adreno, and a model's kernels held it. Both OpenCL copies of ggml now create their context with Qualcomm's low
  priority hint (`cl_qcom_priority_hint`, patched in at configure time) when the device offers it and the driver accepts
  it, so the screen draws first; without it, everything runs as before.
