# Trimio — shared brief for all agents (phase 8b)

Repo: /home/user/Trimio (KMP 2.4, Compose MP, Koin, navigation3; native llama.cpp + whisper.cpp via JNI in native/).
Trimio turns a talking video (Persian first, also English) into a motion-graphics edit fully on device (Android phones, Windows desktop).
Pipeline: engine/production (Productions, platforms, render/export) → engine/autopilot (SpeechAnalysis, Proofreader/PersianSpelling,
Lines, Understander (now reads lines in groups of 3, 8-min budget), ColdOpen, Planner + StyleChoices/Hooks/CallToAction/Concepts/
Enumerations/Names/Payoff/Phrases/UiBroll/UserMedia/Glossary, ScreenSpelling gate, Compiler, Critic, VisionCritic, Jury (10 judges,
revise loop, Jury.forDevice budgets), Soundtrack) → engine/motion (scene graph, recipes engine/motion/recipe/*, 28 styles
engine/motion/style/Styles.kt, score/compiler, RuntimeFx shaders, MotionExporter). Apps: feature/* screens (Signature design),
shared/ (TrimioApp, navigation, ProductionFlow), desktopApp/, androidApp/ + androidGpu/ (Adreno OpenCL variant).
Read `git log -25 --oneline` and docs/ROADMAP.md (phase 8b) first.

## The owner's verdict on a real Windows run (1.0.8, Qwen3.5-9B, ~2-min Persian video about crypto trading)
"If Claude's own edit and variety are 10, Trimio's are 2."
- Elements out of frame, repetitive, irrelevant; text placed badly; spelling mistakes; English text on a Persian video;
  the prompt is not followed; it feels like no intelligence is applied — only render time is wasted.
- The jury only scores; it does not send the edit back until everything is 10/10 (spelling, video problems, placement,
  elements, correctness and variety of motion, the right element for each moment, the prompt).
- The video does not play inside the app.
- All work ran on the CPU; the NVIDIA GPU (RTX 3050 Ti 4 GB) stayed idle (fixed in 1.0.12: dedicated GPU only, multi-GPU).
- Timings: listen 513 s (whisper large on CPU), understand 1643 s, jury 35 s, render ~15+ min for 4385 frames.
- Android (S26 Ultra, 12 GB): "understand" never finished (since 1.0.13: groups of 3 lines + progress + budget).

## Test material (NEVER commit, never copy into the repo)
Owner videos: /root/.claude/uploads/7e03ed16-d9d4-5525-aa3f-2586cff127ac/b6e90edc-3680691608780480128.mp4 and
/root/.claude/uploads/7e03ed16-d9d4-5525-aa3f-2586cff127ac/ff607739-9074971826380325396.mp4.
Models: /tmp/claude-0/-home-user-Trimio/7e03ed16-d9d4-5525-aa3f-2586cff127ac/scratchpad/models (Qwen3.5-9B-UD-Q3_K_XL + mmproj,
ggml-large-v3-q5_0.bin). Proof runs: engine/autopilot AutopilotProofTest (-Pauto.* properties; see the test). Disk is tight
(~2.5 GB free): clean up big temporary files you create; put outputs in your own subfolder of the scratchpad.

## Rules
- Gradle ALWAYS through the lock (several agents share this tree):
  `flock /tmp/claude-0/-home-user-Trimio/7e03ed16-d9d4-5525-aa3f-2586cff127ac/scratchpad/gradle.lock ./gradlew <tasks> --console=plain -q`
  Keep the lock only for the command; don't hold it with long proof runs if you can run them through a separate JVM
  (prefer short focused test tasks).
- Stay inside your OWNED paths; outside them make only minimal, surgical edits and re-read the file right before editing
  (others edit concurrently). Never reformat files you don't own.
- Quality gates before you finish: detekt green (line ≤200, ComplexCondition ≤3, Cyclomatic ≤25, ReturnCount ≤5,
  one break/continue per loop); jvmTest of touched modules; :desktopApp:compileKotlin; :androidApp:compilePlayDebugKotlin
  (+ :androidApp:externalNativeBuildPlayDebug if native/ changed). engine/autopilot golden archive: baseline only moves up
  (if your change legitimately changes scores, explain and raise the baseline only upward).
- UI strings Persian-first with English. NEVER write anything about fonts in the app UI or docs. Never commit fonts,
  brand/brand.key, models or user videos. No new brand logos beyond licensed packs.
- Do NOT git commit or push — the supervisor does. Do not stop other agents' Gradle daemons.
- Be honest in your final report: measured vs estimated, what is verified, what is not.

## Owner's standing rule (13 Oct 2026)
Work continues until the app is truly ultra-professional. The ONLY acceptance is the owner's real videos
(scratchpad/jurybug: the 154.7 s owner video 73521fe5-… and long.mp4), never short workshop scenarios alone:
0 unknown on-screen words, 0 fragment texts, 0 cuts inside words, every judge >= 9.5 with the rules director and
the 9B director. The app itself must detect and fix these on ANY video (no video-specific hacks). Write a PROGRESS.md
in your scratchpad folder after each milestone so a container restart loses nothing.
