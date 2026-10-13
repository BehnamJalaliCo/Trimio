# Trimio add-on models: what to offer next to the main LLM

*Research date: 2026-10-13. Scope: Android (S26 Ultra, 12 GB, Adreno) and Windows (i5-11320H, 16 GB, RTX 3050 Ti 4 GB + Iris Xe). Research only: no repo changes.*

Legend: **[V]** verified from a primary source (model card, licence file, code in our vendored `llama.cpp`/`whisper.cpp`, or MTEB result files). **[C]** checked in code but not run. **[E]** my estimate. **[U]** unverified or conflicting sources.

---

## 0. TL;DR

1. **Most of the quality gain needs no new engine.** Our vendored `llama.cpp` (commit `baef3ed`, 2026-10-09) already contains these features **[C]**:
   - text embedding archs: `bert`/XLM-R (bge-m3, e5), `qwen3` (Qwen3-Embedding), `gemma-embedding` and `gemma-embedding2` (EmbeddingGemma 1 and 2), `eurobert` (jina v5), `jina-bert-v3`;
   - reranking (`--pooling rank`, Qwen3-Reranker and Qwen3-VL-Reranker conversion);
   - the **EmbeddingGemma 2 vision and audio projector** (`gemma4uv`/`gemma4ua`);
   - **Qwen3-ASR** audio (`qwen3a`);
   - **MTP / EAGLE-3 / DFlash speculative decoding**.

   Our vendored `whisper.cpp` already ships **Silero VAD v6.2.0 (ggml, 0.9 MB)** and DTW token timestamps **[C]**.
2. **The biggest wins for the owner's four complaints:**
   - *Irrelevant elements* come from today's `SemanticIndex`, which is hashed character n-grams with no semantics. Replace it with a real Persian-capable embedder, a reranker, and **grammar-constrained choice from the reranked shortlist**. Add a relevance floor, so that a weak match falls back to typography or a primitive instead of a random icon.
   - *Spelling* errors start in ASR. Use a Persian-fine-tuned ASR as an option, Silero VAD before Whisper, and glossary retrieval fed into Whisper's `initial_prompt` and into the proofreader.
   - *Weak understanding*: add RAG over the glossary, brand memory and research, plus a few-shot exemplar retrieval from the golden archive.
   - *Prompt not followed*: extract the brief into a constraint checklist (JSON), check the plan deterministically, give the jury a "brief compliance" judge, and train a LoRA on the golden archive.
3. **Precompute offline, embed queries on device.** The 20k icons, the Lottie/emoji set, the 36 primitives and the music/SFX packs are curated, so:
   - enrich them on the server with a big model (bilingual descriptions, metaphors, moods);
   - embed them with the *same* on-device embedder;
   - ship int8 vectors with the pack (≈15–20 MB per embedder for 20k items).

   The phone then only embeds the query sentence (milliseconds). This also removes the need for an on-device CLAP model for curated audio.
4. **Top picks.** Licences are commercial-OK unless a cell in the tables says otherwise.

   | Pick | Size | Licence | Notes |
   |---|---|---|---|
   | **EmbeddingGemma 2** | 310 MB text Q8 + 555 MB mmproj Q8 | Apache-2.0 | One space for text, image and audio, in llama.cpp. Its Persian quality is not yet benchmarked: gate it on our own evaluation, with **bge-m3** (MIT) as the fallback. |
   | **Qwen3-Reranker-0.6B** | 639 MB Q8 | Apache-2.0 | |
   | **Qwen3-Reranker-4B** | 2.5 GB Q4_K_M | Apache-2.0 | Laptop only. |
   | **Qwen3-Embedding-4B** | 2.5 GB | Apache-2.0 | Laptop Ultra. It has the best open, commercial-OK score on Persian MIRACL in our tables. |
   | **Silero VAD v6.2** | 0.9 MB | MIT | |
   | **Qwen3.5-4B-MTP** GGUF | +94 MB over today's director file | Apache-2.0 | Faster decoding. |

   Non-commercial traps, all of which must be **excluded**:
   - every Jina model (embeddings v3/v4/v5, rerankers v2/v3/v3.5, jina-clip-v2) is CC BY-NC 4.0;
   - madmom model files are CC BY-NC-SA;
   - MMS is CC BY-NC;
   - MuQ-MuLan is CC BY-NC;
   - llama-embed-nemotron uses NVIDIA's non-commercial licence;
   - aesthetic-predictor-v2.5 is AGPL-3.0;
   - Essentia is AGPL-3.0;
   - emotion2vec uses the FunASR model licence, which is unclear.
5. **Add ONNX Runtime only for the Pro tier,** and only for things llama.cpp cannot run: face detection, shot detection, the punctuation tagger, beat tracking, OCR and diarization. On Google Play, the `.so` must ship inside the APK/AAB or a Play feature module, **not** as a download: Play's Device and Network Abuse policy forbids downloading executable code.

---

## 1. What the repo does today (relevant to this question)

| Area | Current implementation | Gap |
|---|---|---|
| Retrieval of icons/visuals | `engine/assets/.../SemanticIndex.kt`: hashed char 2–4-grams, 1024-dim, cosine | It catches spelling variants but **no meaning**: «رشد» does not match a "rocket/growth" icon unless the synonym list has it. This is the likely root of "irrelevant elements". |
| Normalisation | `core/model/.../TextKey.kt` maps ي/ى→ی, ك→ک, ة→ه, أإآ→ا, and strips ZWNJ/ZWJ/RLM/LRM, tatweel and harakat | It does not unify digits (۰–۹, ٠–٩, 0–9) or map ؤ→و, ئ→ی or ۀ→ه. It also deletes spaces, so a phrase collapses into one token, which is fine for keys but not for BM25. |
| VAD | `engine/audio/.../VoiceActivityDetector.kt`, energy-based | Silero can replace it; Silero is already in whisper.cpp. |
| ASR | whisper.cpp with `token_timestamps=true` and `initial_prompt` set | Not using `vad`, not using DTW alignment heads, and no Persian fine-tune in `ModelCatalog`. |
| Spelling | `Proofreader.fromBrief`/`fromLexicon`, plus a lexicon `fa_words.tsv` (1.7 MB) in the APK | A good base. Add glossary retrieval and an LLM pass constrained to word-level diffs. |
| Constrained output | GBNF grammar sampler in `native/llama/trimio_llama_jni.cpp` (lazy grammar) | Use it to restrict choices to **retrieved candidate IDs** (enum in the grammar). |
| Model catalogue | `ModelKind` already has `Embedding`, `Vision`, `Speech`, `Language` | It is ready for add-on entries. |
| APK | The debug APK is 97.6 MB. Native libs total 15 MB (`libtrimio_llama_gpu.so` 7.5 MB, `libtrimio_llama.so` 4.6 MB, `libtrimio_whisper.so` 1.4 MB). | The ORT `.so` (~16 MB for arm64 in the full build, ~4 MB in a custom op build) fits. |

---

## 2. Text embedding models (RAG and retrieval)

### 2.1 Persian scores: method

There is no public FaMTEB entry for Qwen3-Embedding or EmbeddingGemma 2. So I took the official MTEB results repo, `embeddings-benchmark/results` at commit `34a44096`, and the task list of `MTEB(fas, v2)` from `mteb` 2.24.12 (52 tasks; FaMTEB authors). I then averaged the `main_score` of the **Persian (`fas-Arab`) subsets** per model and per task type.

The scripts are in the scratchpad: `r8c/scripts/fas.py`, `fas2.py`. Their raw outputs are `fas_full.txt` and `fas_subsets.txt`. Models whose authors only ran MMTEB have just 3–6 Persian tasks, so they are compared on shared tasks in §2.3.

### 2.2 Full Persian benchmark (all 52 tasks of MTEB(fas, v2)) [V, computed]

| Model | Params | Licence | Task mean | Type mean | Retrieval (17) | Rerank (2) | STS (2) | Summary retrieval (3) | Classification (16) |
|---|---|---|---|---|---|---|---|---|---|
| codefuse-ai/F2LLM-v2-4B | 4.0 B | Apache-2.0 | **72.11** | 74.36 | **63.95** | 72.50 | 78.99 | 77.06 | 74.17 |
| MCINext/Hakim | 124 M | **API only, no weights** | 71.58 | 75.13 | 52.93 | 69.41 | 80.16 | 82.92 | 81.37 |
| codefuse-ai/F2LLM-v2-0.6B | 0.6 B | Apache-2.0 | 68.34 | 70.89 | 58.58 | 70.12 | 77.16 | 69.68 | 71.08 |
| google/embeddinggemma-300m (v1) | 0.3 B | Gemma Terms | 67.75 | 70.83 | **59.22** | 71.46 | 79.07 | 69.54 | 68.68 |
| codefuse-ai/F2LLM-v2-330M | 0.33 B | Apache-2.0 | 66.37 | 69.05 | 56.88 | 69.95 | 75.94 | 65.00 | 68.47 |
| BAAI/bge-m3 | 0.57 B | MIT | 65.55 | 68.63 | 58.92 | **74.56** | 79.67 | 61.07 | 65.26 |
| jinaai/jina-embeddings-v3 | 0.57 B | **CC BY-NC** | 65.25 | 67.78 | 57.48 | 61.26 | **82.84** | 65.50 | 66.43 |
| intfloat/multilingual-e5-large | 0.56 B | MIT | 64.28 | 67.36 | 55.98 | 74.34 | 79.32 | 56.61 | 65.72 |
| PartAI/Tooka-SBERT-V2-Large | 0.35 B | **no licence stated** | 63.29 | 66.16 | 48.01 | 69.89 | 77.13 | 52.88 | 70.52 |
| intfloat/multilingual-e5-base | 0.28 B | MIT | 62.59 | 65.85 | 53.92 | 72.07 | 78.32 | 54.58 | 63.57 |
| intfloat/multilingual-e5-small (48/52) | 0.12 B | MIT | 59.39 | 61.88 | 50.17 | 54.65 | 76.86 | 51.02 | 60.16 |
| HooshvareLab ParsBERT (baseline) | 0.11 B | Apache-2.0 | 45.59 | 46.45 | 14.82 | 46.38 | 63.77 | 7.09 | 70.91 |

These match the FaMTEB paper ([arXiv 2502.11571](https://arxiv.org/abs/2502.11571), Table 2: bge-m3 rerank 74.56, jina-v3 STS 78.65 on v1) and the Hakim paper ([arXiv 2505.08435](https://arxiv.org/abs/2505.08435)). Hakim is the Persian SOTA on average but is **API-only** (its HF repo holds only a README pointing to `mcinext.ai/api`), so it is unusable on device.

### 2.3 Persian subsets of shared multilingual tasks (all models) [V, computed]

| Model | Licence | MassiveIntent-fa | WikiRerank-fa | WikiRetrieval-fa | **MIRACL-fa (hard neg.) nDCG@10** | NeuCLIR-fa | WebFAQ-fa |
|---|---|---|---|---|---|---|---|
| nvidia/llama-embed-nemotron-8b | **non-commercial (NSCL)** | 81.06 | 91.32 | 92.55 | **64.78** | – | – |
| Qwen/Qwen3-Embedding-8B | Apache-2.0 | 81.33 | 89.89 | 91.33 | 62.57 | – | – |
| **Qwen/Qwen3-Embedding-4B** | Apache-2.0 | 79.57 | 88.62 | 90.90 | **62.19** | – | – |
| google/gemini-embedding-001 | API | 83.95 | 91.20 | 93.57 | 61.63 | – | – |
| **BAAI/bge-m3** | MIT | 69.44 | 88.21 | 89.32 | **61.38** | 59.30 | 77.26 |
| Snowflake arctic-embed-l-v2.0 | Apache-2.0 | 69.00 | 88.71 | 90.12 | 60.73 | – | – |
| **google/embeddinggemma-300m** | Gemma | 76.22 | 90.41 | 90.74 | 58.84 | **62.25** | 78.06 |
| jina-embeddings-v5-text-nano | **CC BY-NC** | 85.67 | 88.91 | 89.37 | 58.53 | – | – |
| microsoft/harrier-oss-v1-0.6b (new) | MIT | 71.57 | 83.58 | 84.65 | 55.91 | – | – |
| F2LLM-v2-4B | Apache-2.0 | 87.99 | 89.59 | 90.89 | 55.16 | 59.94 | 86.63 |
| **Qwen/Qwen3-Embedding-0.6B** | Apache-2.0 | 70.07 | 86.59 | 86.97 | **54.32** | – | 37.21 (outlier) |
| F2LLM-v2-0.6B | Apache-2.0 | 85.65 | 87.81 | 88.57 | 50.28 | 52.42 | 82.84 |
| e5-large-instruct | MIT | 68.48 | 90.89 | 91.68 | 52.16 | 59.50 | 78.13 |
| granite-embedding-311m-multilingual-r2 | Apache-2.0 | 58.44 | 86.58 | 86.59 | 52.39 | – | – |

**Reading:**
- **Qwen3-Embedding-0.6B is weaker in Persian than its MMTEB average (64.33) suggests.** On MIRACL-fa it scores 54.3, against 61.4 for bge-m3 and 58.8 for EmbeddingGemma-300m.
- On the phone, **EmbeddingGemma** and **bge-m3** are the best open, commercial-OK Persian retrievers.
- On the laptop, **Qwen3-Embedding-4B** is the best Apache-licensed Persian retriever.
- F2LLM-v2 is excellent on Persian classification and STS, which suits tagging and clustering, but weaker on MIRACL.

### 2.4 Candidate table (size, licence, GGUF, llama.cpp)

| Model | Params / dims | Licence (from the card) | GGUF (exact repo/file, size) | Arch in our llama.cpp | Notes |
|---|---|---|---|---|---|
| **google/embeddinggemma-2** (released 2026-10-06) | 740 M in total. Text = 270 M (130 M backbone + 140 M embedder); vision 170 M; audio 300 M. 768-d, MRL 512/256/128. | **Apache-2.0** [V] | `ggml-org/embeddinggemma-2-GGUF`: `embeddinggemma-2-Q8_0.gguf` (310 MB), `mmproj-embeddinggemma-2-Q8_0.gguf` (555 MB) [V] | `gemma-embedding2` + `gemma4uv`/`gemma4ua` projector [C] | MTEB-multilingual v2 61.36; MIEB-lite 64.64; MMEB-v2 image Hit@1 57.28; MSEB audio retrieval MRR@10 69.54; MAEB 49.39; 8k context; "100+ languages" ([card](https://huggingface.co/google/embeddinggemma-2)). **No Persian number yet [U].** Card warning: use bf16/fp32, **not fp16**, or it outputs NaNs. Prompts: `task: search result \| query: …`, `title: … \| text: …`. |
| google/embeddinggemma-300m (v1) | 308 M, 768-d | Gemma Terms (commercial OK, use policy passes through) | `ggml-org/embeddinggemma-300M-GGUF`, `…-qat-q8_0-GGUF` | `gemma-embedding` | Best measured small Persian retriever (above). |
| **BAAI/bge-m3** | 568 M, 1024-d, 8k | MIT [V] | `ggml-org/bge-m3-Q8_0-GGUF` (635 MB) | `bert` (XLM-R) | Dense only in llama.cpp; the sparse and ColBERT heads are not exposed [U]. |
| Qwen/Qwen3-Embedding-0.6B | 596 M, 1024-d (MRL 32–1024), 32k | Apache-2.0 | `Qwen/Qwen3-Embedding-0.6B-GGUF`: Q8_0 639 MB | `qwen3`, last-token pooling | MMTEB 64.33; weaker in Persian (above). Instruction format `Instruct: …\nQuery:` ([card](https://huggingface.co/Qwen/Qwen3-Embedding-0.6B)). |
| **Qwen/Qwen3-Embedding-4B** | 4.0 B, 2560-d (MRL) | Apache-2.0 | `Qwen/Qwen3-Embedding-4B-GGUF`: Q4_K_M 2.50 GB, Q8_0 4.28 GB | `qwen3` | MMTEB 69.45. Laptop Ultra. |
| Qwen/Qwen3-Embedding-8B | 7.6 B, 4096-d | Apache-2.0 | Q4_K_M ≈ 4.7 GB [E] | `qwen3` | MMTEB 70.58. Too big for marginal gain on our devices. |
| intfloat/multilingual-e5-{small,base,large,large-instruct} | 118 M / 278 M / 560 M | MIT | community GGUFs (e.g. `Ralriki/multilingual-e5-large-instruct-GGUF`) | `bert` | Dominated by bge-m3 and EmbeddingGemma on Persian. |
| Alibaba-NLP/gte-multilingual-base | 305 M | Apache-2.0 | none mainstream for the embedder | `new`/gte arch [U] | Persian 64.9 on a partial run. Skip. |
| jinaai/jina-embeddings-v3 / v5-text-nano/small | 572 M / 212 M / 596 M | **CC BY-NC 4.0** ✗ | yes | `jina-bert-v3` / `eurobert` | **Excluded: non-commercial.** |
| microsoft/harrier-oss-v1 (270m / 0.6b / 27b) | 268 M / 596 M | MIT | `mradermacher/harrier-oss-v1-0.6b-GGUF` | qwen3/gemma3 decoders [U] | New. Persian is listed among its 94 languages, but MIRACL-fa is only 55.9. |
| codefuse-ai/F2LLM-v2 (80M…14B) | Qwen3 base | Apache-2.0 | `mradermacher/F2LLM-v2-0.6B-GGUF` | `qwen3` | Good for Persian **classification/clustering** (e.g. mood or topic tags). |

**Recommendation:**
- **Essential:** EmbeddingGemma 2 text Q8_0, if it passes our Persian gate (§9); otherwise bge-m3 Q8_0.
- **Ultra (laptop):** Qwen3-Embedding-4B Q4_K_M, used for text-to-text search over the exemplar, glossary and research corpora. Truncate it to 1024-d with MRL so the vector packs stay small.

---

## 3. Rerankers

| Model | Size | Licence | MMTEB-R* | llama.cpp | GGUF |
|---|---|---|---|---|---|
| **Qwen/Qwen3-Reranker-0.6B** | 0.6 B | Apache-2.0 | 66.36 | Yes: the converter detects Qwen3-Reranker and writes `cls_out` (yes/no logits); run with `--embedding --pooling rank` or `/v1/rerank` [C] | `ggml-org/Qwen3-Reranker-0.6B-Q8_0-GGUF` (639 MB) |
| **Qwen/Qwen3-Reranker-4B** | 4.0 B | Apache-2.0 | 72.74 | same | `mradermacher/Qwen3-Reranker-4B-GGUF`: Q4_K_M 2.50 GB |
| Qwen/Qwen3-Reranker-8B | 8.2 B | Apache-2.0 | 72.94 | same | barely better than the 4B; skip |
| BAAI/bge-reranker-v2-m3 | 568 M | Apache-2.0 | 58.36 | Yes (XLM-R sequence classification; the README's own example) | `gpustack/bge-reranker-v2-m3-GGUF`: Q4_K_M 438 MB, Q8_0 636 MB |
| Alibaba-NLP/gte-multilingual-reranker-base | 306 M | Apache-2.0 | 59.44 | GGUF exists (`gpustack/…`); support not verified [U] | |
| Qwen/Qwen3-VL-Reranker-2B | 2.1 B | Apache-2.0 | – (multimodal) | converter detects "qwen3-vl-reranker" [C]; runtime not tested [U] | `mradermacher/Qwen3-VL-Reranker-2B-GGUF` |
| mixedbread-ai/mxbai-rerank-base-v2 / large-v2 | 0.5 B / 1.5 B | Apache-2.0 | n/a | community GGUF; llama.cpp support [U] | |
| jinaai/jina-reranker-v2-base-multilingual, v3, v3.5 | 0.28–0.6 B | **CC BY-NC 4.0** ✗ | 63.73 (v2) | v3.5 needs a llama.cpp fork (PR #26286) | **Excluded.** |

\*MMTEB-R comes from the Qwen3-Reranker card ([link](https://huggingface.co/Qwen/Qwen3-Reranker-0.6B)): it reranks the top-100 from Qwen3-Embedding-0.6B. **No Persian-specific reranker benchmark exists for the Qwen3 rerankers [U].** The only Persian rerank data point is FaMTEB, where the bge-m3 *embedder* reaches 74.56.

**Why a reranker matters for a creative director:**
- The Qwen3 rerankers are *instruction-aware*. For example: `<Instruct>: Judge whether this visual (icon/animation/diagram) would make a viewer instantly grasp the meaning or metaphor of the Persian sentence`. That is much closer to "relevance" in our sense than cosine similarity.
- Its yes-probability is a calibrated **relevance score** we can threshold. Below a floor, the plan uses typography or a primitive instead of a random icon.
- The same score can serve as an objective jury metric.

**Latency [E].** Earlier Trimio bench logs show the 4B director at about 400 tok/s prefill.
- Scaled to a 0.6B model, expect roughly 2–3k tok/s on the same device.
- Twenty candidates of about 80 tokens each is about 1,600 tokens, so **about 0.6–1.5 s per sentence on the phone**, about 0.3 s on the laptop GPU.
- The Qwen3 reranker template puts `<Instruct>`+`<Query>` *before* `<Document>`, so share that prefix across candidates (KV prefix reuse or parallel sequences) to save another 30–50%.
- Rerank only the top 20–30 from hybrid retrieval.

---

## 4. Multimodal embeddings (visual↔sentence, frame↔sentence, audio↔mood)

### 4.1 Text↔image / video

| Model | Size | Licence | Persian text side | Runtime on our devices |
|---|---|---|---|---|
| **EmbeddingGemma 2** (+mmproj) | text 270 M + vision 170 M (+ audio 300 M) | Apache-2.0 | 100+ languages, Gemma tokenizer; Persian not measured [U] | **llama.cpp mtmd**: `gemma4uv` projector [C]. `llama-server` documents multimodal embeddings via `/embedding` [V]. Also ONNX (`onnx-community/embeddinggemma-2-ONNX`: vision q4 109 MB, fp16 336 MB) and LiteRT-LM (`litert-community/embeddinggemma-2-text-vision-440m-litert-lm`). |
| google/siglip2-base-patch16-256 / so400m-naflex | 375 M / 1.1 B | Apache-2.0 | Trained on WebLI in 109 languages, multilingual Gemma tokenizer, 64-token text, lower-cased ([paper](https://arxiv.org/abs/2502.14786)) [V]. Persian retrieval not measured [U]. | ONNX: `onnx-community/siglip2-base-patch16-256-ONNX` (vision int8 95 MB, text int8 283 MB). **Not in llama.cpp** (mtmd has no CLIP text tower). |
| Qwen/Qwen3-VL-Embedding-2B / 8B | 2.1 B / 8.1 B | Apache-2.0 | "30+ languages" | MMEB-V2 overall 73.2 (2B). GGUF exists (`mradermacher/Qwen3-VL-Embedding-2B-GGUF` Q4_K_M 1.1 GB + mmproj 445 MB). llama.cpp: arch `qwen3vl` plus multimodal embedding should work, but this is **not verified** [U]. |
| M-CLIP/XLM-Roberta-Large-Vit-B-32 | ~2.2 GB fp32 | **no licence in the card** [U] | XLM-R covers Persian | PyTorch/TF only. Skip. |
| jinaai/jina-clip-v2 | 0.9 B | **CC BY-NC 4.0** ✗ | 89 languages; Persian not listed explicitly | ONNX. **Excluded.** |

**Design advice:**
- **Do not depend on Persian text→image directly.** The director already reads Persian and can emit an **English and Persian "visual query"** per line (HyDE-style). Embed that query.
- For the catalogue, prefer **text↔text** matching against offline, VLM-written bilingual descriptions of each icon or animation. This is more robust than CLIP-style matching on 2-colour icons. Keep image embeddings for:
  - (a) video frames of talking-head footage: what is on screen, and where the free space is;
  - (b) user-imported images;
  - (c) the jury's check that "the rendered frame depicts the sentence".

### 4.2 Text↔audio (music and SFX by mood)

| Model | Licence | Text encoder | Evidence | Runtime |
|---|---|---|---|---|
| **EmbeddingGemma 2 audio** | Apache-2.0 | Multilingual (Gemma) | MSEB retrieval 69.54, MAEB 49.39. Trained on "speech recordings…, environmental sounds, and acoustic events"; **music not mentioned** [V/U] | llama.cpp `gemma4ua` [C]; ONNX audio q4 189 MB |
| LAION-CLAP (`laion/larger_clap_general`, `…_music_and_speech`) | Apache-2.0 (HF card); repo CC0 | RoBERTa, **English** | AudioCaps T2A nDCG@10 57.1 / 63.1; Clotho 33.7 / 32.4 (MTEB/MAEB results) [V] | ONNX: `Xenova/clap-htsat-unfused` (text int8 127 MB, audio int8 34 MB) |
| Microsoft CLAP 2023 | code MIT; HF card says **MS-PL** [V], so check it | GPT-2, **English** | Clotho 41.9; MusicCaps 19.2; SoundDescs 37.3 (best of the open CLAPs here) [V] | PyTorch, so it needs an ONNX export [U] |
| facebook/pe-av-small/base/large (PE Audio-Visual, Dec 2025) | Apache-2.0 [V] | Not stated [U] | Average retrieval 48.1–51.6 (card) | PyTorch only [V] |
| OpenMuQ/MuQ-MuLan-large | **CC BY-NC** ✗ | – | music-only | Excluded |

**Design advice:** the music and SFX packs are curated, so **tag them offline** with a large audio-LLM or a CLAP on the server:
- mood, energy, tempo (BPM), key, instruments, genre, "suits: tension/reveal/success" and so on;
- plus beats, downbeats and sections (§6.12).

Embed those tags as text with the on-device text embedder. On device, CLAP is only needed for **user-imported** music or SFX. There, EmbeddingGemma 2 audio (in llama.cpp, no new engine) is the first choice, and LAION-CLAP ONNX is the alternative. Feed CLAP English descriptors written by the director.

---

## 5. RAG for a creative director (not Q&A)

### 5.1 Corpora worth indexing

| Corpus | Unit | Retrieval key → payload | Where it plugs in | How to measure it |
|---|---|---|---|---|
| **Golden archive exemplars** | One (sentence, context, chosen metaphor, visual(s), primitive(s), timing, jury score) per line, plus a whole-edit summary | Key: normalised sentence plus a 1-line "intent" written by the LLM. Payload: the decision JSON. Use as **few-shot** examples (3–5 per line, MMR-diversified). | understand → plan/direct | Leave-one-edit-out: Recall@5 of "same metaphor family"; jury Δ with and without few-shot |
| **Visual catalogue** (≈20k icons, Lottie/emoji, 36 primitives) | One per asset, with several *views* (Persian synonyms, English name, VLM description, metaphor list, "good for…" usage notes) | Max-sim over views (we already do this in `SemanticIndex`). Payload: asset ID plus style tags. | pick visuals | Labelled set of 300–500 lines × relevant assets: nDCG@10, Recall@20 (pre-rerank), P@1 (post-rerank), "irrelevant rate" judged by the owner |
| **Persian glossary/terminology** (finance, crypto, forex, medical, tech) | Term entries: canonical spelling, variants, English, Finglish, definition, common ASR confusions (e.g. «نوبیتکس» ↔ Nobitex) | BM25 over variants plus dense over the definition | **ASR `initial_prompt` biasing** (top terms for the topic), proofread, understand | Domain WER/CER and term-level F1 before vs after; false-correction rate |
| **Brand and user memory** | Brand kit, banned words, tone, recurring names, past corrections the owner made | Exact/BM25 first, dense second; **always-include** pinned facts | proofread, plan, jury ("brief and brand compliance") | Constraint-violation count per edit |
| **Web research** | Chunks of fetched pages (300–500 tokens, 15% overlap, heading-aware), with source URL and date | Hybrid plus rerank, per question generated by the planner | research → understand | Faithfulness: share of on-screen numbers and claims traceable to a retrieved chunk; human spot-check |
| *(optional)* Music/SFX pack tags | Per clip: mood/BPM/sections text | Dense text | music/SFX pick | Owner A/B on mood fit |

### 5.2 Hybrid retrieval recipe

1. **Normalise** the query and documents the same way (§5.3).
2. **BM25**, in Kotlin in `commonMain`. Our corpora are under about 100k units, so an in-memory inverted index is fine (and identical on Android and Windows). Use Persian light stemming: strip ها/های/هایی/تر/ترین/ی and so on, conservatively, *and* index the unstemmed form. Do not rely on Android's platform SQLite FTS5: its availability is [U], and its default `unicode61` tokenizer splits at ZWNJ.
3. **Dense**: EmbeddingGemma 2 (or bge-m3) query embedding, brute-force cosine over int8 vectors. For 20k × 768 that is 15 MB and about 5 ms [E]. No ANN index is needed at this scale.
4. **Fuse with RRF** (k=60), take the top 30, then the **reranker** with a creative instruction, then a **threshold**.
5. **Constrain** the LLM: put the surviving candidate IDs into the GBNF grammar as an enum, so the director can only pick from relevant, existing assets. This fixes "irrelevant elements" and also fixes invented asset IDs.
6. **Diversity**: apply MMR across consecutive lines, so the same icon does not repeat.

**Vector stores.**
- **Default: no store.** Use flat int8 arrays plus BM25 in Kotlin.
- If a corpus grows past about 200k, consider these:
  - **usearch**: Apache-2.0, HNSW, int8, with a Java JNI binding. Android is listed as a platform, but the JNI build is [U].
  - **hnswlib**: Apache-2.0, header-only C++, easy to add to our existing JNI.
  - **sqlite-vec**: MIT/Apache-2.0. It is still pre-v1 (v0.1.10 alphas add DiskANN and IVF), so expect breaking changes.
  - **FAISS** (MIT) builds for Android but is overkill.
  - Avoid "SQLite Vector" by sqliteai: it uses the Elastic License 2.0.

### 5.3 Persian normalisation checklist (for both index and query)

| Issue | Rule |
|---|---|
| Arabic Yeh/Alef-maksura ي ى (U+064A/0649) | → Farsi Yeh ی (U+06CC) — already done in `TextKey` |
| Arabic Kaf ك (U+0643) | → Keheh ک (U+06A9) — done |
| ۀ (U+06C0), ه + U+0654, ة | → ه for matching (keep the original for display) |
| ؤ → و, ئ → ی, أ إ → ا, آ → ا (search only) | add ؤ/ئ/ۀ (missing today) |
| Digits ۰–۹ (U+06F0–06F9), ٠–٩ (U+0660–0669) | → ASCII for the index; render Persian digits on screen. **Missing today.** |
| ZWNJ U+200C | Index **both** joined («میروم») and split («می روم») forms. Collapse repeated ZWNJ; drop ZWNJ next to a space; drop ZWJ, LRM/RLM, BOM, tatweel ـ, and harakat U+064B–065F/0670. |
| Spaces and NBSP (U+00A0, U+202F) | → single space (do *not* delete spaces for BM25 tokens, unlike `TextKey`) |
| Punctuation ، ؛ ؟ « » | Map to separators for tokenisation |
| Finglish and loanwords | Glossary entries carry both scripts (e.g. Google Calendar ↔ گوگل کلندر) |

Libraries for reference rules, all MIT [V]:
- **Shekar** ([repo](https://github.com/amirivojdan/shekar)): follows the Academy of Persian Language and Literature rules; JOSS 2025;
- **hazm** ([repo](https://github.com/roshan-research/hazm));
- **Parsivar** ([repo](https://github.com/ICTRC/Parsivar)).

They are Python, so port the rule tables to Kotlin rather than embedding Python.

### 5.4 Chunking

| Corpus | Chunking |
|---|---|
| Exemplars and catalogue | One record per decision or asset; no chunking. |
| Glossary | One entry per term. |
| Web pages | Heading-aware chunks of about 300–500 tokens with 10–15% overlap. Keep the title, URL and date in the chunk header (EmbeddingGemma's `title: … \| text: …` format). Deduplicate near-duplicates by cosine above 0.95. |
| Transcript context | The line ± 1 neighbour as query context; the full-script summary as a session-level prefix. |

---

## 6. Other add-ons that raise quality

### 6.1 ASR quality (the root of spelling mistakes)

| Add-on | Size | Licence | Runtime | Evidence and notes | Plugs in |
|---|---|---|---|---|---|
| **Silero VAD v6.2.0** (`ggml-org/whisper-vad/ggml-silero-v6.2.0.bin`) | 0.9 MB | MIT [V] | **whisper.cpp built-in** (`vad_model_path` / `whisper_vad_*` API) [C] | Cuts hallucinations in silences and fixes segment boundaries. It replaces our energy VAD for jump cuts too. | listen |
| Whisper DTW word timing (`dtw_token_timestamps` with the large-v3-turbo alignment-head preset) | 0 | MIT | whisper.cpp [C] | Better word timings than `token_timestamps` alone, for karaoke captions and beat-synced reveals | listen → captions |
| **Persian-fine-tuned Whisper large-v3**, then converted to ggml (q5_0 ≈ 1.1 GB [E]) | ~1.1 GB | `nezamisafa/whisper-persian-v4` Apache-2.0 (card WER 8.73 on its own test set); `MohammadKhosravi/whisper-large-v3-Persian` Apache-2.0; `BuzzASR/persian` MIT (FLEURS CER 5.13 vs 7.18 zero-shot; Common Voice 25 CER 4.05 vs 14.0) | whisper.cpp after conversion with `convert-h5-to-ggml.py`. **BuzzASR uses a custom per-language tokenizer, so whisper.cpp conversion may fail [U].** | Probably the **single largest spelling fix**. Must be A/B tested on our own recordings, because fine-tunes can lose punctuation or code-switching (English brand names). | listen |
| Qwen3-ASR-1.7B Persian fine-tune (`Neurai/Qwen3-ASR-1.7B-Persian`; GGUF `mradermacher/Qwen3-ASR-1.7B-Persian-GGUF` Q8_0 1.83 GB + mmproj 356 MB) | ~2.2 GB | Apache-2.0 | **llama.cpp mtmd `qwen3a`** [C] | The base Qwen3-ASR is *worse* than Whisper on the Fleurs‡ group that includes Persian (12.60 vs 8.16 WER) ([card](https://huggingface.co/Qwen/Qwen3-ASR-1.7B)). The Neurai fine-tune reports no WER [U]. Use it as a **second-opinion hypothesis** for LLM fusion (ROVER-style), not as the primary model. | listen → proofread |
| Rade-ASR-CTC-3B-fa (`RadeAI/Rade-ASR-CTC-3B-fa`, Omnilingual CTC-3B fine-tune) | 6.2 GB fp16 | Apache-2.0 | needs an ONNX export [U] | FLEURS fa CER ~4.4%, CV17 fa 17.8% (search snippet [U]) | laptop Ultra only |

### 6.2 Persian punctuation, normalisation and spelling

| Add-on | Size | Licence | Runtime | Notes |
|---|---|---|---|---|
| **MohammadJRanjbar/parsbert-persian-punctuation** (PersianPunc, 2026) | 162 M (≈650 MB fp32 / ≈170 MB int8 [E]) | MIT [V] (gated: you must share contact info) | ONNX Runtime; or llama.cpp `bert` with `--pooling none` plus the 768×5 classification head applied in Kotlin [U, engineering] | Macro-F1 91.3; comma 80.0, period 98.7, question 88.9, colon 90.5. Cheap, deterministic punctuation before the LLM proofreader, which then only fixes words. |
| Rule normaliser (port of the Shekar/hazm tables) | KB | MIT | Kotlin | ZWNJ for می/نمی/ها/تر/ترین, spacing around punctuation, digits |
| Lexicon + MLM real-word check: `HooshvareLab/bert-fa-zwnj-base` or `PartAI/TookaBERT-Base` | 110–123 M | Apache-2.0 | llama.cpp `bert` (embeddings only; a fill-mask head is **not** supported [U]) or ORT | Scores candidate corrections in context. Optional: the director LLM with a **word-diff-only grammar** gives most of the benefit. |
| `mfsadi/Llama-3.1-8B-spelling-fa` | 8 B LoRA | no licence in the card; Llama terms | – | E-commerce queries only. Skip. |

**Technique (no download).** Make the LLM proofreader emit a JSON list of `{index, from, to, reason}`, constrained by GBNF, never a rewritten line. Then:
- accept a change only if `to` is in the lexicon, the glossary or the brief, *or* the reranker or second ASR agrees;
- track **false-correction rate** as a first-class metric.

### 6.3 Speaker diarization (two-person interviews or podcasts)

**sherpa-onnx** (Apache-2.0) bundles these, all [V]:
- pyannote `segmentation-3.0` (MIT, ~6 MB);
- 3D-Speaker or NeMo speaker embeddings (Apache);
- Silero VAD;
- Android/Windows JNI on top of ONNX Runtime.

`pyannote/speaker-diarization-community-1` is **CC-BY-4.0**: commercial use is OK with attribution, and it is gated. **Ultra only**, since talking-head input is usually a single speaker.

### 6.4 Forced alignment (exact word timing, Persian)

| Option | Licence | Status |
|---|---|---|
| whisper.cpp DTW (above) | MIT | Ready. **Use first.** |
| `facebook/omniASR-CTC-300M` (Omnilingual ASR, CTC, char vocabulary, 1600+ languages) | **Apache-2.0** [V] | Run CTC Viterbi alignment of the *proofread* text against the emissions. That is the right way to time corrected text. Needs an ONNX export (~1.3 GB fp32, ~350 MB int8 [E]) [U]. |
| Qwen3-ForcedAligner-0.6B | Apache-2.0 | **No Persian** (11 languages) [V]. Skip. |
| MMS / MMS_FA, `ctc-forced-aligner` | **CC BY-NC 4.0** ✗ (`facebook/mms-300m`, `mms-1b-all`) [V] | Excluded. |

### 6.5 Prosody and emotion (emphasis and pacing)

- **DSP first (no model).** Compute F0 (YIN/pYIN), RMS energy, speaking rate from word timestamps, pause length, and pitch-range z-scores per word. Mark emphasis where loudness and pitch deviate by more than 1.5σ, and hit visuals on those words. This is language-independent and has no licence risk.
- **Audio-capable director.** Gemma-4-E4B (already in the catalogue; llama.cpp `gemma4a` audio [C]) can be asked per line for "emotion, emphasis word, energy 1–5". Persian accuracy [U].
- **`m3hrdadfi/hubert-base-persian-speech-emotion-recognition`** (Apache-2.0 [V], ShEMO-trained acted speech, ~95 M). Needs ORT. Treat it as a weak signal.
- **emotion2vec+** (FunASR "model-license", terms unclear [U]): avoid until the licence is clarified.

### 6.6 Shot/scene detection, faces and saliency (layout that avoids faces)

| Add-on | Size | Licence | Runtime | Notes |
|---|---|---|---|---|
| **TransNetV2** | ~30 MB [E] | MIT [V] | ORT after conversion [U] | F1 77.9 ClipShots / 96.2 BBC / 93.9 RAI. Alternative with no model: PySceneDetect-style histogram rules (BSD-3) in Kotlin, which is enough for single-take talking heads. |
| **YuNet** (`opencv/face_detection_yunet`, 2023mar ONNX, int8 variant) | ~0.2–0.3 MB | MIT (OpenCV Zoo) [U: the HF card shows no licence field] | ORT or OpenCV DNN | Face boxes and 5 landmarks → "no-go zones" for graphics; also eye-line for text placement. |
| MediaPipe Face Detector / Selfie segmenter | <1 MB / ~0.5 MB | Apache-2.0 [V] | LiteRT/MediaPipe, which is a *fourth* engine: avoid, or convert to ONNX [U] | The person mask gives "free space" for full layout. |
| Saliency: reuse the person mask plus face boxes plus text-region OCR | – | – | – | More reliable for talking heads than a generic saliency net. |

### 6.7 Image-quality, aesthetic and VLM judges (jury)

- **The cheapest strong judge is the embedding head we already have.** Train a tiny MLP (a few KB) on **EmbeddingGemma 2 image embeddings → owner/jury score**, using the golden archive's rated frames. The licence is ours, and it costs about 1 ms per frame after the embedding.
- LAION *improved-aesthetic-predictor* is Apache-2.0 [V] but needs CLIP ViT-L/14 (about 900 MB). **aesthetic-predictor-v2.5 is AGPL-3.0** ✗ [V]. Avoid it.
- **Relevance judge:**
  - reranker score of (sentence ↔ chosen asset description);
  - EmbeddingGemma 2 cosine of (rendered frame ↔ English visual query).

  Both are objective numbers to log next to the LLM jury.
- **VLM judge:** the existing Qwen3.5-4B + mmproj on the phone. On the laptop, use Qwen3.5-9B, or Gemma-4-E4B as a *different-family* second judge, which reduces correlated jury bias.

### 6.8 OCR (on-screen text checks)

We render the text ourselves, so OCR exists only to catch **shaping, clipping, overlap and contrast failures** (for example, disjoint Persian letters or a cut ZWNJ).

| Option | Size | Licence | Runtime | Notes |
|---|---|---|---|---|
| **PaddleOCR-VL-0.9B** | ~1.9 GB bf16 (Q8 ≈ 1 GB [E]) | Apache-2.0 [V] | **llama.cpp mtmd `paddleocr`** [C] | Persian is in its 109 languages [V] |
| PP-OCR (Arabic-script recogniser) via RapidOCR | tens of MB | Apache-2.0 | ORT | Persian accuracy [U] |
| Google ML Kit text recognition | – | – | – | Has no Arabic-script model. Unusable. |

**Cheaper alternative:** a render-time assertion. Re-shape the string with HarfBuzz and compare glyph clusters, which needs no model.

### 6.9 Speculative decoding (speed buys more jury and revise iterations)

| Option | Download | Notes |
|---|---|---|
| **Qwen3.5 MTP** | `unsloth/Qwen3.5-4B-MTP-GGUF` (Q4_K_M 2.835 GB vs 2.741 GB today, so **+94 MB**); `unsloth/Qwen3.5-9B-MTP-GGUF` (Q4_K_M 5.87 GB) | Apache-2.0 [V]. llama.cpp `--spec-type draft-mtp`; the code has a "single trained MTP head" mode for qwen35 [C]. Unsloth reports 1.4–2.2× on big GPUs [V-vendor]; on phones expect about 1.2–1.5× [E]. |
| Gemma-4-E4B assistant drafter | `google/gemma-4-E4B-it-assistant`: 79 M params, 159 MB bf16 | Apache-2.0 [V]. llama.cpp support merged in June 2026 per secondary sources (`gemma4-assistant` is in our tree [C]). One report on a Galaxy S26+: 48% acceptance and only +1–2 tok/s [U]. |
| n-gram / `ngram-map` | 0 | Good for proofreading, which copies the input almost verbatim. |

### 6.10 LoRA adapters from the golden archive

- Unsloth gives Qwen3.5 bf16 LoRA VRAM needs of **0.8B: 3 GB, 2B: 5 GB, 4B: 10 GB, 9B: 22 GB**. It *does not recommend QLoRA for Qwen3.5* [V] ([guide](https://unsloth.ai/docs/models/qwen3.5/fine-tune)). So the laptop's 4 GB GPU can only train ≤0.8B; rent a 24 GB GPU for the 4B or 9B.
- Train on (transcript line + context → director JSON) pairs from the golden archive, keeping only jury-approved edits, typically 1–5k examples.
- Ship it as a GGUF LoRA through `convert_lora_to_gguf.py` (≈50–200 MB [E]) and load it at runtime. Hybrid Gated-DeltaNet layers in Qwen3.5 may not all be LoRA-able in llama.cpp [U].
- This targets "prompt not followed" and house style.

### 6.11 Constrained JSON

We already have GBNF. Upgrade it in three ways:
- (a) generate grammars from the plan JSON schema (`json_schema_to_grammar` in llama.cpp `common/`);
- (b) **inject per-line enums** of retrieved asset IDs, primitive names, transition names and music cue IDs;
- (c) bound the length of free-text fields.

`llguidance` (MIT) is faster for big schemas, but it needs Rust in the NDK build; it is optional.

### 6.12 Music beat and structure

| Option | Size | Licence | Status |
|---|---|---|---|
| **beat_this** (CPJKU 2024) | `small0` checkpoint ≈ 8.1 MB | **MIT, code and weights** [V] | Beats and downbeats without a DBN (madmom's DBN is optional). ONNX export [U]. |
| All-In-One (beats, downbeats, sections, labels) | – | MIT [V] | Depends on madmom (BSD code; madmom's **model files are CC BY-NC-SA** ✗) and on NATTEN, so it is **server-side only**. |
| SongFormer (structure) | – | **CC-BY-4.0** [V] | Commercial use OK with attribution; server-side for packs. |
| Essentia | – | **AGPL-3.0** ✗ | Avoid. |

**Plan:** precompute beats, sections and energy curves for every pack track on the server. On device, run beat_this ONNX only for user-imported music.

---

## 7. Recommended bundles

Sizes are download sizes; RAM is approximate resident memory at inference [E]. "Phone" means the S26 Ultra (12 GB). "Laptop" means 16 GB RAM, with the director on the RTX 3050 Ti 4 GB and add-ons on CPU or the Iris Xe (Vulkan).

### 7.1 Essential (recommended download): no new engine

| # | Item | Exact file | Licence | Runtime | Size | RAM | Latency [E] | Plugs in |
|---|---|---|---|---|---|---|---|---|
| E1 | Text embedder | `ggml-org/embeddinggemma-2-GGUF/embeddinggemma-2-Q8_0.gguf` (fallback: `ggml-org/bge-m3-Q8_0-GGUF/bge-m3-q8_0.gguf`, 635 MB, MIT) | Apache-2.0 | llama.cpp (`--embedding`, pooling from the GGUF) | 310 MB | ~0.4 GB | Query (≤40 tokens): phone 10–30 ms, laptop <10 ms | understand, pick visuals, glossary, exemplars, research |
| E2 | Precomputed vector packs (catalogue, primitives, exemplars, glossary, music/SFX tags) | Our CDN, int8, per-embedder version | ours | Kotlin brute force + BM25 | ~25–40 MB | ~60 MB | ~5 ms per query | same |
| E3 | Reranker | `ggml-org/Qwen3-Reranker-0.6B-Q8_0-GGUF/qwen3-reranker-0.6b-q8_0.gguf` | Apache-2.0 | llama.cpp `/rerank` (`--pooling rank`) | 639 MB | ~0.8 GB | 20 candidates: phone 0.6–1.5 s per line, laptop GPU ~0.3 s | pick visuals, research, exemplars, jury relevance metric |
| E4 | Silero VAD | `ggml-org/whisper-vad/ggml-silero-v6.2.0.bin` | MIT | whisper.cpp | 0.9 MB | ~10 MB | real-time ×100+ | listen, jump cuts |
| E5 | Faster director | `unsloth/Qwen3.5-4B-MTP-GGUF/Qwen3.5-4B-Q4_K_M.gguf` (replaces the current file) | Apache-2.0 | llama.cpp `draft-mtp` | +94 MB | +~0.1 GB | 1.2–1.5× decode | every LLM stage |
| E6 | Techniques (no download) | Constrained enums, constraint checklist, relevance floor, glossary→`initial_prompt`, DTW timing, normalisation fixes | – | – | 0 | – | – | all |

**Total: about 1.05 GB on both platforms.** Peak RAM during plan/pick is the director (≈3.2 GB with KV) + E1 + E3, which is **about 4.5 GB** and fits the phone.

### 7.2 Pro

| # | Item | Exact file | Licence | Runtime | Size | RAM | Latency [E] | Plugs in |
|---|---|---|---|---|---|---|---|---|
| P1 | Multimodal projector for E1 (image + audio embedding) | `ggml-org/embeddinggemma-2-GGUF/mmproj-embeddinggemma-2-Q8_0.gguf` | Apache-2.0 | llama.cpp mtmd | 555 MB | ~0.7 GB | Image (~280 tokens): phone 0.1–0.4 s, laptop <0.1 s. Audio: 25 tokens/s of audio. | frame↔sentence, layout free space, user images and music, jury frame relevance |
| P2 | Persian ASR upgrade (pick after A/B) | `nezamisafa/whisper-persian-v4` or `MohammadKhosravi/whisper-large-v3-Persian` → ggml q5_0 (we convert and host it) | Apache-2.0 | whisper.cpp | ~1.1 GB | ~1.5 GB | ≈ large-v3 (slower than turbo) | listen |
| P3 | Larger reranker, **laptop only** | `mradermacher/Qwen3-Reranker-4B-GGUF/Qwen3-Reranker-4B.Q4_K_M.gguf` | Apache-2.0 | llama.cpp (CPU, or the dGPU when the director is unloaded) | 2.5 GB | ~3 GB | 20 candidates: 1–3 s on CPU | pick visuals |
| P4 | **ONNX Runtime engine** | `com.microsoft.onnxruntime:onnxruntime-android` (custom op build); Windows `onnxruntime` DLL (DirectML/OpenVINO/CUDA EP) | MIT | new JNI | APK +4–16 MB | – | – | for P5–P8 |
| P5 | Punctuation tagger | `MohammadJRanjbar/parsbert-persian-punctuation` → ONNX int8 | MIT (gated) | ORT | ~170 MB | ~0.3 GB | <50 ms per line | proofread |
| P6 | Face detector | `opencv/face_detection_yunet` 2023mar (int8) | MIT [U card] | ORT | <1 MB | ~20 MB | ~5 ms per frame | layout |
| P7 | Shot detector | TransNetV2 → ONNX | MIT | ORT | ~30 MB | ~0.2 GB | ~1–2 s per minute of video | video edits |
| P8 | Beat tracker (user music) | beat_this `small0` → ONNX | MIT | ORT | ~8 MB | ~0.1 GB | ~1 s per minute of audio | music |
| P9 | Gemma director drafter | `google/gemma-4-E4B-it-assistant` → GGUF Q8_0 (or `AtomicChat/gemma-4-E4B-it-assistant-GGUF`) | Apache-2.0 | llama.cpp MTP | ~85 MB | ~0.1 GB | 1.1–1.3× | Gemma director |
| P10 | Aesthetic and relevance heads (trained by us on P1 embeddings) | ours | ours | Kotlin | <1 MB | – | ~1 ms | jury |

**Pro total:**
- **Phone ≈ 1.05 + 0.56 + 1.1 + 0.02 + 0.21 + 0.09 ≈ 3.0 GB.**
- **Laptop ≈ 5.5 GB**, because it adds P3.

### 7.3 Ultra

| # | Item | Exact file | Licence | Runtime | Size | Notes |
|---|---|---|---|---|---|---|
| U1 | Best Persian dense retriever, **laptop** | `Qwen/Qwen3-Embedding-4B-GGUF/Qwen3-Embedding-4B-Q4_K_M.gguf` | Apache-2.0 | llama.cpp | 2.5 GB | MIRACL-fa 62.2. Needs its own vector packs (MRL 1024-d → ~20 MB each). |
| U2 | Multimodal reranker | `mradermacher/Qwen3-VL-Reranker-2B-GGUF` Q4_K_M (~1.1 GB) + mmproj Q8 (~445 MB) | Apache-2.0 | llama.cpp [U runtime] | ~1.5 GB | Reranks image or Lottie *thumbnails* against the sentence |
| U3 | Second-opinion ASR | `mradermacher/Qwen3-ASR-1.7B-Persian-GGUF` Q8_0 + mmproj Q8_0 | Apache-2.0 | llama.cpp `qwen3a` | 2.2 GB | ROVER/LLM fusion with Whisper |
| U4 | Persian forced aligner | `facebook/omniASR-CTC-300M` → ONNX int8 | Apache-2.0 | ORT | ~0.35 GB [E] | Times the proofread text exactly |
| U5 | Diarization | sherpa-onnx: pyannote seg-3.0 + 3D-Speaker embedding | MIT / Apache-2.0 | ORT (via sherpa-onnx) | ~40 MB | Interviews |
| U6 | OCR check | `PaddlePaddle/PaddleOCR-VL` → GGUF (Q8 ≈ 1 GB [E]) | Apache-2.0 | llama.cpp `paddleocr` [C] | ~1 GB | Render QA |
| U7 | House-style LoRA | trained by us on 4B/9B | ours (base Apache-2.0) | llama.cpp LoRA | 50–200 MB | Prompt-following and style |
| U8 | Second-family VLM judge (laptop) | Qwen3.5-9B (already in the catalogue) or Gemma-4-E4B | Apache-2.0 | llama.cpp | 5–6 GB | Jury diversity |
| U9 | Laptop director MTP | `unsloth/Qwen3.5-9B-MTP-GGUF/Qwen3.5-9B-Q4_K_M.gguf` | Apache-2.0 | llama.cpp | +~0.2 GB vs non-MTP | Speed |

**Ultra total (incremental):**
- **Phone ≈ +5 GB** (U2–U7 without U1 and U8), cumulative ≈ 8 GB on disk; run these one stage at a time.
- **Laptop ≈ +8–9 GB** (U1–U7, U9), cumulative ≈ 14 GB.

---

## 8. Integration risks

### 8.1 A third engine (ONNX Runtime)

| Risk | Detail | Mitigation |
|---|---|---|
| APK size | Full ORT 1.18 AAR is 24.4 MB, with `libonnxruntime.so` at **16.3 MB for arm64**. A custom op-reduced build is about 7.5 MB AAR / **4.0 MB `.so`** ([ORT docs](https://onnxruntime.ai/docs/tutorials/mobile)) [V]. The QNN EP adds Qualcomm `libQnnHtp*.so` (`onnxruntime-android-qnn` depends on `qnn-runtime` 2.42) [V], tens of MB [U]. | Build ORT with only our ops, CPU + XNNPACK; skip QNN at first. Today's debug APK is 97.6 MB. |
| Store limits | Google Play: **200 MB base module (compressed)**, 1.5 GB per asset pack, 4 GB on-demand total ([Play help](https://support.google.com/googleplay/android-developer/answer/9859372)) [V]. Unity pages still show the old 150 MB figure. The Cafe Bazaar limit was not found [U]. | Models stay outside the APK (our CDN). |
| **Play policy** | The Device and Network Abuse policy forbids downloading executable code (dex/JAR/**.so**) from outside Play ([policy](https://support.google.com/googleplay/android-developer/answer/9888379)) [V]. | The ORT `.so` must be **in the APK/AAB or a Play feature module**. Model files are data, so they are fine. The Bazaar build could download it, but keep one approach. |
| JNI | A second native runtime means a second thread pool. ORT and ggml both spin up threads, which oversubscribes the big cores. | Set ORT `intra_op_num_threads` to 2–4. Never run ORT and llama.cpp prefill at the same time. Use a single "ModelScheduler" in Kotlin. |
| Windows | DirectML is in *sustained engineering*; new work goes to Windows ML (ORT with vendor EPs) ([MS docs](https://learn.microsoft.com/windows/ai/directml)) [V]. | Use the ORT CPU EP for these small models (they are cheap). Add the OpenVINO EP for the Iris Xe later. |
| Avoiding ORT altogether | E1–E5, P1–P3, P9, U1–U3, U6 and U9 all run in llama.cpp or whisper.cpp. | Ship Essential with zero new engines. ORT is Pro and later. |

### 8.2 Concurrency and RAM

- **Phone (12 GB).** A practical native budget is about 6–7 GB before the low-memory killer gets aggressive [E].
  - Stage the pipeline: ASR (≤1.5 GB) → unload → proofread/understand/plan with director + E1 + E3 (≈4.5 GB) → pick visuals (same) → jury (director + mmproj ≈ 4 GB) → render (GPU).
  - Keep E1 resident (small). Load E3 lazily and unload it before render.
  - Use `mmap` for every GGUF (it already is) so evicted pages are clean.
- **Laptop.** The 4 GB VRAM holds only the director (4B Q4 plus KV). Put the embedder and reranker on CPU (AVX2/AVX-512 VNNI on the i5-11320H) or on the Iris Xe through a second Vulkan device. llama.cpp can open separate contexts on different devices.
- **GPU contention with render.** Never run Vulkan/OpenCL inference during the render pass on the phone.

### 8.3 Licence traps (commercial use)

| Status | Models |
|---|---|
| **Excluded (non-commercial or copyleft)** | jina-embeddings-v3/v4/v5 (CC BY-NC), jina-reranker-v2/v3/v3.5 (CC BY-NC), jina-clip-v2 (CC BY-NC), MuQ-MuLan (CC BY-NC), MMS/MMS_FA (CC BY-NC), madmom *model files* (CC BY-NC-SA), llama-embed-nemotron-8b (NVIDIA non-commercial), aesthetic-predictor-v2.5 (AGPL-3.0), Essentia (AGPL-3.0) |
| **Unclear** | emotion2vec+ (FunASR model licence); Tooka-SBERT-V2 and M-CLIP (no licence in the card); Microsoft CLAP weights (HF card says MS-PL, code MIT); YuNet HF mirror (no licence field; upstream OpenCV Zoo says MIT) |
| **Attribution needed** | pyannote community-1 (CC-BY-4.0, gated), SongFormer (CC-BY-4.0) |
| **Gemma Terms** | EmbeddingGemma-300m v1: commercial OK, but the use policy passes through to users. **EmbeddingGemma 2 is plain Apache-2.0**. |
| **API-only** | Hakim, gemini-embedding |
| **OK** | Apache-2.0 / MIT: everything in the bundles above |

Record every model's licence and source in `docs/THIRD_PARTY_NOTICES.md` and in the `ModelCatalog.license` field, which already exists.

### 8.4 Version skew

- Vector packs are tied to an exact embedder, quantisation and prompt template. Version them as `(embedderId, dims, prompt)` and refuse to mix.
- EmbeddingGemma 2 in **fp16 can produce NaNs** (per its card). Use the Q8_0 or BF16 GGUF and check for NaNs on load.

---

## 9. Evaluation plan (offline metrics plus jury before and after)

**Gate 0 (1–2 days): pick the embedder.**
- From the golden archive, label 300–500 Persian lines × relevant assets (3–5 each) and 200 glossary queries.
- Compare EmbeddingGemma 2, EmbeddingGemma-300m, bge-m3 and Qwen3-Embedding-0.6B (and Qwen3-Embedding-4B on the laptop), each alone and in hybrid with BM25.
- Metrics: nDCG@10, Recall@30 (reranker input), MRR. Then add the reranker: P@1, P@3.
- **Ship EmbeddingGemma 2 only if it is within 1 nDCG point of the best phone-sized model.**

**Component metrics (offline, every release):**

| Component | Metric | Target set |
|---|---|---|
| ASR (+VAD, Persian fine-tune) | WER/CER, plus **term-error rate** on glossary terms and English brand names | 30–60 min of owner recordings with gold transcripts |
| Proofreader | Corrections precision/recall; **false-correction rate**; punctuation macro-F1 | Same, with gold edits |
| Alignment | Mean / P90 absolute word-boundary error (ms) | 5 min, hand-labelled |
| Retrieval | nDCG@10, Recall@30, P@1 after rerank; **irrelevant-pick rate** (owner-labelled) | Labelled set above |
| Relevance floor | Precision/recall of the "no good asset → fall back" decision | Same |
| Prompt following | % of brief constraints satisfied (extracted checklist, checked deterministically) | 50 briefs from the archive |
| Faithfulness (research) | % of on-screen claims and numbers with a supporting chunk | 30 research edits |
| Speed and RAM | Per-stage wall time, peak RSS/VRAM, thermal throttling over a 10-min edit | Both devices |

**End-to-end:**
- Run the same 30 inputs (15 talking-head, 15 audio-only) through `baseline` → `+Essential` → `+Pro` → `+Ultra`.
- Record the 10+ judge jury scores per dimension (relevance, understanding, spelling, brief compliance, aesthetics, pacing).
- Test with a **paired bootstrap** (95% CI on the mean difference). Add a **blind pairwise preference** from the owner on 20 pairs.
- Log the objective proxies too (reranker relevance score, frame↔sentence cosine), to check that the LLM jury agrees with them.
- Promote an add-on only if it improves its target metric *and* does not regress the other jury dimensions beyond the CI.

---

## 10. Uncertainty register

| # | Item | Status |
|---|---|---|
| 1 | EmbeddingGemma 2's Persian retrieval quality | Unmeasured: not yet in the MTEB results; released 2026-10-06 |
| 2 | EmbeddingGemma 2 image and audio embedding through llama.cpp mtmd | Code present (`gemma4uv`/`gemma4ua`; the converter registers `EmbeddingGemma2Model` for both text and mmproj; ggml-org publishes the mmproj); not run by me |
| 3 | Qwen3-VL-Embedding and Qwen3-VL-Reranker runtime in llama.cpp | The reranker converter path exists; embedding with an image input is not verified |
| 4 | Qwen3-Reranker quality on Persian | No benchmark found |
| 5 | Persian Whisper fine-tunes | Converted-to-ggml quality, punctuation and code-switching are unknown. BuzzASR's custom tokenizer may block conversion. |
| 6 | Cafe Bazaar APK size limit | Not found |
| 7 | MTP speedup on Adreno/Vulkan or the phone CPU | Only secondary reports (small gains) |
| 8 | ONNX exports of TransNetV2, beat_this, omniASR-CTC and the ParsBERT tagger | Plausible (standard ops), not done |
| 9 | Latency figures marked [E] | Scaled from Trimio's bench (~400 tok/s prefill, 16–21 tok/s decode for the 4B) by parameter count; measure on device |

---

## 11. Sources

**Benchmarks and data:**
- MTEB results repo: https://github.com/embeddings-benchmark/results (commit 34a44096); `mteb` 2.24.12 `MTEB(fas, v2)` definition (PyPI wheel)
- FaMTEB: https://arxiv.org/abs/2502.11571 · https://aclanthology.org/2025.findings-emnlp.614/
- Hakim: https://arxiv.org/abs/2505.08435 · https://huggingface.co/MCINext/Hakim

**Embedders:**
- https://huggingface.co/google/embeddinggemma-2 · https://huggingface.co/ggml-org/embeddinggemma-2-GGUF · https://huggingface.co/onnx-community/embeddinggemma-2-ONNX
- https://huggingface.co/Qwen/Qwen3-Embedding-0.6B · https://huggingface.co/Qwen/Qwen3-Embedding-4B-GGUF · Qwen3 Embedding paper https://arxiv.org/abs/2506.05176
- https://huggingface.co/BAAI/bge-m3 · https://huggingface.co/ggml-org/bge-m3-Q8_0-GGUF
- https://huggingface.co/jinaai/jina-embeddings-v5-text-nano (CC BY-NC) · https://huggingface.co/microsoft/harrier-oss-v1-0.6b · https://huggingface.co/codefuse-ai/F2LLM-v2-0.6B

**Rerankers:**
- https://huggingface.co/Qwen/Qwen3-Reranker-0.6B · https://huggingface.co/ggml-org/Qwen3-Reranker-0.6B-Q8_0-GGUF
- https://huggingface.co/BAAI/bge-reranker-v2-m3 · https://huggingface.co/jinaai/jina-reranker-v3.5-GGUF (CC BY-NC)

**Multimodal:**
- https://huggingface.co/Qwen/Qwen3-VL-Embedding-2B (arXiv 2601.04720)
- SigLIP 2: https://arxiv.org/abs/2502.14786 · https://huggingface.co/google/siglip2-base-patch16-256
- https://huggingface.co/jinaai/jina-clip-v2 (CC BY-NC)
- https://huggingface.co/facebook/pe-av-small
- LAION-CLAP: https://github.com/LAION-AI/CLAP · MS-CLAP: https://github.com/microsoft/CLAP · https://huggingface.co/microsoft/msclap · https://huggingface.co/Xenova/clap-htsat-unfused

**Speech:**
- whisper.cpp VAD models: https://huggingface.co/ggml-org/whisper-vad · Silero: https://github.com/snakers4/silero-vad
- Qwen3-ASR: https://huggingface.co/Qwen/Qwen3-ASR-1.7B · https://huggingface.co/Qwen/Qwen3-ForcedAligner-0.6B · https://huggingface.co/ggml-org/Qwen3-ASR-1.7B-GGUF · https://huggingface.co/Neurai/Qwen3-ASR-1.7B-Persian
- Persian Whisper fine-tunes: https://huggingface.co/BuzzASR/persian · https://huggingface.co/nezamisafa/whisper-persian-v4 · https://huggingface.co/MohammadKhosravi/whisper-large-v3-Persian
- Omnilingual ASR: https://huggingface.co/facebook/omniASR-CTC-300M · https://github.com/facebookresearch/omnilingual-asr · https://huggingface.co/RadeAI/Rade-ASR-CTC-3B-fa
- MMS licence: https://huggingface.co/facebook/mms-300m
- Diarization and runtime: https://huggingface.co/pyannote/speaker-diarization-community-1 · https://github.com/k2-fsa/sherpa-onnx
- Emotion: https://huggingface.co/emotion2vec/emotion2vec_plus_large · https://huggingface.co/m3hrdadfi/hubert-base-persian-speech-emotion-recognition

**Persian NLP:**
- https://huggingface.co/MohammadJRanjbar/parsbert-persian-punctuation (PersianPunc, arXiv 2603.05314) · ViraPart arXiv 2110.09086
- https://github.com/amirivojdan/shekar · https://github.com/roshan-research/hazm · https://github.com/ICTRC/Parsivar

**Video and image:**
- TransNetV2: https://github.com/soCzech/TransNetV2 · https://arxiv.org/abs/2008.04838
- https://huggingface.co/opencv/face_detection_yunet · MediaPipe: https://github.com/google-ai-edge/mediapipe
- Aesthetic: https://github.com/christophschuhmann/improved-aesthetic-predictor (Apache-2.0) · https://github.com/discus0434/aesthetic-predictor-v2-5 (AGPL-3.0)
- OCR: https://huggingface.co/PaddlePaddle/PaddleOCR-VL · https://www.paddleocr.ai/latest/en/version3.x/algorithm/PaddleOCR-VL/PaddleOCR-VL.html

**Music:**
- https://github.com/CPJKU/beat_this · https://github.com/mir-aidj/all-in-one · https://github.com/CPJKU/madmom (model files CC BY-NC-SA) · https://github.com/ASLP-lab/SongFormer · https://github.com/MTG/essentia

**Decoding and fine-tuning:**
- llama.cpp docs (vendored): `docs/speculative.md`, `tools/server/README.md` (`/embedding`, `/rerank`), `docs/multimodal.md`, `docs/llguidance.md`
- https://unsloth.ai/docs/models/mtp · https://huggingface.co/unsloth/Qwen3.5-4B-MTP-GGUF · https://huggingface.co/google/gemma-4-E4B-it-assistant · https://point.free/blog/gemma-4-mtp/
- https://unsloth.ai/docs/models/qwen3.5/fine-tune

**Vector stores:**
- https://github.com/asg017/sqlite-vec · https://github.com/unum-cloud/usearch · https://github.com/nmslib/hnswlib · https://github.com/facebookresearch/faiss

**Platform:**
- https://onnxruntime.ai/docs/tutorials/mobile · https://onnxruntime.ai/docs/execution-providers/QNN-ExecutionProvider.html
- https://support.google.com/googleplay/android-developer/answer/9859372 · https://support.google.com/googleplay/android-developer/answer/9888379
- https://learn.microsoft.com/windows/ai/directml · https://blogs.windows.com/windowsdeveloper/2025/05/19/introducing-windows-ml/
