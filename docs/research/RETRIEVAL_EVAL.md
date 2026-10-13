# Retrieval check: picking pictures by meaning (phase 8c)

*2026-10-13. Run on the shared 4-core build server while it was overloaded (load 13–15), so the
timings below are not phone or laptop timings. The quality numbers do not depend on the machine.*

Legend: **[M]** measured here · **[E]** estimate · **[U]** not measured yet.

## 1. The labelled set

- **Builder:** `tools/retrieval/build_eval.py`, which writes `engine/retrieval/src/jvmTest/resources/retrieval/eval.json`.
- **Lines:** 328 in total.
  - 288 Persian lines and 40 English ones.
  - They are written in the workshop's voice and domains: crypto, forex, finance, medical, fitness, cooking, travel, tech, AI, social media, education, lifestyle, news, sales and podcasts.
  - They do not repeat the golden exemplars.
- **Labels:**
  - Each line is labelled with the picture *families* that would make it instantly clear. There are 105 families, such as growth, decline, warning, scam, time and money.
  - Each family is a hand-picked set of ids from the bundled catalogue of 1742 animated pictures (Noto emoji and Material line icons).
  - 15 lines are *abstract* («زندگی همینه دیگه»). For those, no picture should be shown; typography or a primitive should carry the line.
  - The labels are the agent's judgement, not the owner's. They are a fixed yardstick for comparing systems, not ground truth about taste.
- **Hint:** each line also carries the English picture idea a director would write.
- **Modes:**
  - **A**: the sentence alone (an audio-only explainer reading its narration).
  - **B**: the sentence plus the director's English idea (the talking-head path).

## 2. Metrics

- **Ranking metrics,** over the 313 lines that have labels: Recall@10, nDCG@10 and MRR, with binary gains. nDCG is low in absolute terms because a family often holds dozens of acceptable ids.
- **P@1:** whether the first pick is relevant.
- **What would be shown.** Each line shows its top pick when that pick passes the system's floor:
  - **irrelevant picks** are shown picks that are not in the line's set (abstract lines count every shown pick as irrelevant), as a share of all lines;
  - **precision of shown** is the share of shown picks that are relevant.
- **Floor calibration.** Floors are calibrated by two-fold cross-validation (fitted on the odd lines, applied to the even ones, and the other way round). The old matcher uses its own built-in floor of 0.5.

## 3. Results [M]

| System | Mode | R@10 | nDCG@10 | MRR | P@1 | Shown | Irrelevant picks | Precision of shown |
|---|---|---|---|---|---|---|---|---|
| **old** (`ArtCatalogue.search`: words + char n-grams) | A | 0.367 | 0.113 | 0.254 | 0.185 | 0.637 | **0.476** | 0.254 |
| **old** | B | 0.581 | 0.255 | 0.383 | 0.275 | 0.860 | **0.613** | 0.287 |
| BM25 (new Persian terms) | A | 0.355 | 0.123 | 0.271 | 0.217 | 0.704 | 0.497 | 0.294 |
| BM25 | B | 0.553 | 0.266 | 0.426 | 0.358 | 0.887 | 0.546 | 0.385 |
| dense EmbeddingGemma 2 (Q8_0, 768-d, int8) | A | 0.642 | 0.208 | 0.420 | 0.332 | 0.024 | 0.015 | 0.375 |
| dense EmbeddingGemma 2 | B | 0.709 | 0.240 | 0.469 | 0.361 | 0.064 | 0.027 | 0.571 |
| hybrid EmbeddingGemma 2 + BM25 (RRF) | A | 0.655 | 0.217 | 0.429 | 0.332 | 0.027 | 0.021 | 0.222 |
| hybrid EmbeddingGemma 2 + BM25 | B | **0.735** | 0.293 | 0.518 | 0.428 | 0.155 | 0.079 | 0.490 |
| dense bge-m3 (Q8_0, 1024-d, int8) | A | 0.645 | **0.321** | 0.449 | **0.387** | 0.137 | 0.064 | 0.533 |
| dense bge-m3 | B | 0.671 | 0.354 | 0.495 | 0.428 | 0.390 | 0.177 | 0.547 |
| hybrid bge-m3 + BM25 | A | 0.665 | 0.296 | 0.448 | 0.345 | 0.116 | 0.055 | 0.526 |
| hybrid bge-m3 + BM25 | B | 0.709 | **0.374** | **0.529** | **0.450** | 0.396 | 0.180 | 0.546 |
| hybrid + Qwen3-Reranker-0.6B | A/B | [U] | [U] | [U] | [U] | [U] | [U] | [U] |

### What the results show

- **The old matcher shows a picture almost always, and mostly the wrong one.**
  - With the director's idea (mode B) it shows a picture for 86% of lines; 61% of all lines get an irrelevant one.
  - Only 29% of what it shows fits.
  - This is the owner's complaint about "irrelevant elements", in numbers.
- **Meaning search finds the right picture far more often.**
  - On Persian sentences alone, Recall@10 rises from 0.367 to 0.64–0.67.
  - P@1 doubles (0.185 → 0.39 with bge-m3).
- **A dense relevance floor removes most irrelevant picks.**
  - Irrelevant picks fall from 0.61 to 0.18 (bge-m3, mode B) while 40% of lines still get a picture.
  - With EmbeddingGemma 2 the calibrated floor is so strict that it shows almost nothing; its cosines separate relevant from irrelevant less well.
  - The remaining lines fall back to typography or a primitive.
- **Embedder choice: bge-m3.** The rule from the add-on report is to ship EmbeddingGemma 2 only if it is within 1 nDCG point of the best phone-sized model. Measured:
  - with the director's idea (B): bge-m3 0.374 against EmbeddingGemma 2 0.293;
  - on the sentence alone (A): 0.321 against 0.208.
  - EmbeddingGemma 2 keeps a slightly higher Recall@10 in B (0.735 against 0.709).

  It stays in the catalogue as the lighter alternative (310 MB against 635 MB).
- **Not measured yet [U]:**
  - **The reranker stage.** The run was stopped at the coordinator's request because the server was overloaded. The harness caches the partial run's vectors (`scratchpad/r8c/eval/run1`), so it can resume, but the reranker scores it had computed were not kept: the rerank stage starts again from scratch.
  - **EmbeddingGemma 300M, Qwen3-Embedding-0.6B and Qwen3-Embedding-4B:** not downloaded (disk).
  - **Jury relevance scores before and after on the workshop:** these need the wiring, which has not started.

## 4. Speed

- **Measured on this server, overloaded [M].** These numbers are not device speeds.
  - Embedding queries in batches of 16 took 150–400 ms per text on 1–2 effective cores.
  - The int8 brute-force search over 1805 documents took about 2 ms per line, BM25 well under 1 ms.
- **Estimates for devices [E]** (scaled from Trimio's earlier llama.cpp benches):
  - phone: 20–60 ms per query embedding;
  - reranking 20 candidates: 0.6–1.5 s per line on a phone, about 0.3 s on a laptop GPU.

## 5. Reproduce

The run is heavy: two embedders and a reranker. Do not run it while the server is busy.

```
./gradlew :engine:llm:buildHostNative
./gradlew :engine:retrieval:jvmTest --tests '*RetrievalEvalTest*' \
  -Pretrieval.encoders=<dir with bge-m3-q8_0.gguf, embeddinggemma-2-Q8_0.gguf, qwen3-reranker-0.6b-q8_0.gguf> \
  -Pretrieval.native=engine/llm/build/host-native -Pretrieval.out=<dir>
```
