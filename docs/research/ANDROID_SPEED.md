# Android speed — research (13 Oct 2026)

Input: the owner's 1.0.17 report on the Galaxy S26 Ultra (SM-S948B, Snapdragon 8 Elite Gen 5 for Galaxy,
12 GB), 154.7 s video, director Gemma 4 E4B QAT Q4_0, speech whisper-large-v3 Q5.

## What the report shows

| stage | measured | note |
|---|---|---|
| speech (whisper, CPU, 8 threads) | 154.7 s of sound in 633 s, real-time factor 4.09 | a 30 min video would need ~2 h |
| alignment add-on (CPU) | 48.5 s | |
| director, reading the prompt | 20–54 tok/s | first token after 188 s |
| director, writing | 3.9–5.6 tok/s | |
| «understand» | 53 chunks, 3 done in ~330 s | ≈ 1 h for 2.5 min of video |

The report cannot say whether the GPU ran: the ENGINE section is written only when a run reaches
render. RSS stayed ~600 MB while the model is several GB, which points to the weights sitting in
OpenCL (Adreno) buffers — likely the GPU ran, partly, and slower than the CPU would.

## The phone

- CPU: 8 Oryon Gen 3 cores (2 prime up to 4.74 GHz, 6 performance up to 3.6 GHz), 24 MB L2, 8 MB L3, TSMC N3P.
- GPU: Adreno 840 (~1.3 GHz). NPU: Hexagon (HTP v81). Memory: LPDDR5X, ~85 GB/s.
- Sources: Notebookcheck spec page; TechARP S26 Ultra review.

## Why it is slow (from the code)

1. **CPU build targets 2020 phones only**: `-DGGML_CPU_ARM_ARCH=armv8.2-a+dotprod+fp16` (androidApp and
   androidGpu). No int8 matrix multiply (i8mm), no SVE2/SME, no KleidiAI. Prompt reading on Arm
   depends on exactly those kernels (Arm's numbers: SME2 ≈ 1.6× over i8mm/dotprod on one core; KleidiAI on
   an ARMv9 board: 36 → 186 tok/s prompt for a 1.5B Q4_0).
2. **The GPU is chosen blindly**: `LlamaNative.load()` loads `trimio_llama_gpu` (OpenCL) whenever the
   phone has the driver, then offloads "as many layers as fit" when ≥90 % of the *weights* are
   supported. Ops the Adreno backend lacks for Gemma 4 (and the q8_0 KV cache with flash attention)
   run on the CPU, so every layer bounces GPU ↔ CPU. Roadmap 8.10 asked to measure this and never did.
3. **Speech runs on the CPU only**, full large-v3 (32 decoder layers), 8 threads.
4. **Too many director calls**: one per chunk of the transcript (53 for 2.5 min).

## What exists to fix it (all on the device, no server)

| path | what | status in our tree (llama.cpp baef3ed) |
|---|---|---|
| CPU variants | `GGML_CPU_ALL_VARIANTS` + `GGML_BACKEND_DL`: Android variants armv8.0 → armv8.2 dotprod → armv8.6 i8mm → armv9.0 SVE2 → armv9.2 SME; the best one the phone supports is picked at run time | present (`ggml/src/CMakeLists.txt`), not used |
| KleidiAI | Arm's micro-kernels (SME2 → i8mm → dotprod priority) | present (`GGML_CPU_KLEIDIAI`), not used |
| Hexagon NPU | `ggml-hexagon` (Qualcomm): HTP v73/v75/v79/v81 = 8 Gen 3, 8 Elite, 8 Elite Gen 5; Q4_0 / Q8_0 / MXFP4 — our director is Q4_0 QAT; ~3.5 GB per NPU session, larger models split over HTP0..n | present, experimental upstream, not used |
| Adreno GPU | `ggml-opencl` | used blindly today |
| Vulkan | for Mali / Xclipse (Exynos, Dimensity) phones | present, not used on Android |
| Whisper on NPU | Qualcomm AI Hub: whisper-large-v3-turbo on SM8850 — encoder ~1.1 s per 30 s, decoder ~6 ms per token (≈ 20–40× faster than now); ONNX Runtime QNN EP (`onnxruntime-android-qnn`, QNN 2.42) | ORT 1.29 already in the app (add-ons); QNN EP not used |

Turbo has 4 decoder layers instead of 32: Persian accuracy must be measured on the owner's videos
first (rule: not one word lost); if it loses words, the full large-v3 encoder goes on the NPU instead.

## Plan (phase 9.5)

1. **Exact log**: ENGINE written the moment each model loads — backend (cpu variant / opencl / hexagon /
   vulkan / qnn), device, layers offloaded, graph splits, ops that fell back to the CPU, threads, cores
   used, temperature and throttling over time; per stage tok/s and real-time factor. MEMORY from the
   same sampler as the stages.
2. **One engine with every backend, chosen per phone**: CPU all-variants + KleidiAI, OpenCL (Adreno),
   Hexagon (NPU), Vulkan (Mali/Xclipse). A short benchmark on first use (prompt + write, ~20 s) measures
   each available backend on the real model; the fastest that gives the same output wins and is
   remembered per device and app version. A backend slower than the CPU is removed from that phone,
   not kept as an option (principle 4).
3. **Speech on the NPU** where present (ORT QNN), CPU variants elsewhere; threads = prime + performance
   cores, measured.
4. **Fewer, larger director calls**: chunk by topic, reuse the prompt head (prefix cache), one pass
   for long videos.
5. **Targets on the S26 Ultra** (to be measured, not promised): speech ≤ 0.3× real time; director
   reading ≥ 300 tok/s, writing ≥ 20 tok/s; a 2.5 min video ≤ 3 min end to end; a 30 min video
   ≤ 30 min. Every Android 13+ phone gets the best path it has; acceptance on the owner's phone and
   at least one non-Qualcomm phone (Exynos/Dimensity) through the same report.

## Sources

- https://www.notebookcheck.net/Qualcomm-Snapdragon-8-Elite-Gen-5-for-Galaxy-Processor-Benchmarks-and-Specs.1271123.0.html
- https://www.techarp.com/reviews/samsung-galaxy-s26-ultra-performance-review/
- llama.cpp `docs/backend/hexagon` (ggml-hexagon, PR #16547) and `docs/backend/snapdragon/developer.md`
- https://learn.arm.com/learning-paths/mobile-graphics-and-gaming/performance_llama_cpp_sme2/
- https://docs.radxa.com/en/orion/o6/app-development/artificial-intelligence/llama-cpp (KleidiAI on/off)
- https://www.qualcomm.com/developer/blog/2026/04/llama-models-acceleration-on-cpu-qmx
- https://aihub.qualcomm.com/mobile/models/whisper_large_v3_turbo
- https://central.sonatype.com/artifact/com.microsoft.onnxruntime/onnxruntime-android-qnn
- https://onnxruntime.ai/docs/execution-providers/QNN-ExecutionProvider.html
