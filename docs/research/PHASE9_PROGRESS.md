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

## Next steps

1. Compile + tests for the above (llm, autopilot, production, shared), detekt.
2. Commit, push `[skip ci]`; CI run on a non-skip push later checks Android assembly with KleidiAI.
3. Whisper: q5_0 gets no fast kernels; candidates large-v3 q8_0 (self-quantized, hosted) — decide.
4. Then 9.7 YouTube (research first), 9.3, 9.6, 9.1, 9.2.
