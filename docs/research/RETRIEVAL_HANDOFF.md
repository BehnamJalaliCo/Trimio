# Retrieval and text add-ons: status (2026-10-13, 16:00)

The interfaces are built and tested. Wiring into the pipeline waits for the coordinator. Heavy runs (the reranker evaluation, building the vector packs, re-testing MTP) are paused while the server is overloaded.

The live log is `scratchpad/r8c/RETRIEVAL_PROGRESS.md`. Measured results are in [RETRIEVAL_EVAL.md](RETRIEVAL_EVAL.md).

## What exists

### Persian text key
`core/model/.../text/TextKey.kt`:
- unifies the digits;
- folds Arabic letter forms and hamza seats;
- `phrase()`, `words()` and `terms()` keep word boundaries for BM25, so a half-space word is matched both joined and split.

### Native code
`native/llama/trimio_llama_jni.cpp`.

- **Encoders, on the CPU:**
  - **embed:** the GGUF's own pooling, L2-normalised, many texts per decode;
  - **rank:** the GGUF rank head, like `--pooling rank`;
  - **judge:** the Qwen3 rerankers run as causal LMs, with the instruction and query shared across documents and P(yes) taken as the softmax of the yes/no logits. This matches the rank head within 0.07 logit when each pair is read alone. It also works for GGUFs converted without a rank head (mradermacher's 4B and VL-2B).

  Verified with real models in `LlamaEncodersTest`.
- **MTP self-speculation for the director:**
  - set by `-Dtrimio.llama.draft`, off by default;
  - the verification is lossless: plain text and grammar-constrained text came out identical with draft sizes 0, 1 and 3 (`LlamaMtpTest`, Qwen3.5-0.8B MTP);
  - **but the test run made almost no guesses.** I traced the cause by reading the code: the hidden rows went into the wrong batch slot. It is fixed, but **not re-tested**.

### engine/llm
- `Embeddings.kt`:
  - the prompt formats per family (EmbeddingGemma, bge-m3, Qwen3-Embedding);
  - the `TextEmbedder` and `TextReranker` interfaces;
  - `RerankPrompt`, with the instructions for visuals, sounds, exemplars and terms.
- `local/LlamaEncoders.kt`:
  - the embedder stays resident and rejects a file that gives NaNs;
  - the reranker loads lazily.
- `local/LocalEncoders.kt` picks the installed encoders.

### engine/retrieval (new module)
- **Search:** BM25 over Persian terms, an int8 brute-force index, RRF fusion, a reranker over the top 20, then a relevance floor. `Shortlist.choiceSchema()` gives a grammar enum of the passing ids plus "none", and MMR keeps picks varied.
- **Corpora:**
  - the picture catalogue (read through `Corpora.pictures` from the art index);
  - the 36 explainer primitives and the 27 card families;
  - 6 music moods and 10 SFX;
  - 110 golden exemplars;
  - a glossary of 309 Persian terms in 7 domains. Spelling variants get corrected; synonyms are found but never "corrected".
- **The creator's own data:**
  - `UserMemory`: brands, terms, corrections, banned words and tone;
  - `TasteMemory`: the creator's own approved lines, used as few-shot examples. This replaces the LoRA.
  - `TasteHead` and `TasteTrainer`: logistic regression trained on the CPU from jury scores. It only reorders picks that already pass the floor.
- **Vector packs:** a `VectorPack` int8 format with per-document fingerprints. A stale pack is never wrong: documents it no longer matches are embedded on the device and cached through `BlobStore`.
- **Facade:** `Retrieval`, with `visuals`, `sounds`, `examples`, `terms` and `hints`.

### engine/models
- `TextAddOns`: every text add-on with its exact repository, file, size, licence and sha256. Each hash was verified on the downloaded bytes, either saved to disk or streamed without saving.
  - **Shipped:** bge-m3 (the measured winner) and Qwen3-Reranker-0.6B.
  - **Alternative:** EmbeddingGemma 2.
  - **Pro (laptop only):** Qwen3-Reranker-4B and Qwen3-Embedding-4B.
  - **Ultra:** Qwen3-VL-Reranker-2B and its projector.
- `AddOnAdvice`: the device-fit rule. The embedder and reranker must fit beside the director; every other add-on runs alone as a stage; Ultra is offered from 12 GB.

### feature/settings
The «تقویت کیفیت» section of the models screen:
- the three tiers;
- the «پیشنهاد برای این دستگاه» one-tap download;
- for each item, its purpose, RAM in its stage and download size.

### tools/retrieval
- Generators for the corpora and the labelled evaluation set.
- `build_packs.sh`, which runs `VectorPackBuildTest` to write `engine/retrieval/composeResources/files/retrieval/packs/`.

## Not done yet
1. **Reranker evaluation numbers.** Resume with `scratchpad/r8c/eval.sh` (it reuses the cached vectors). Then calibrate `RetrievalConfig.rerankFloor` and the dense floors.
2. **Build the bge-m3 vector pack** with `tools/retrieval/build_packs.sh`. Estimated at about 2 MB, which will ship in the app.
3. **Re-test MTP** after the slot fix, and measure acceptance and speed on the 4B MTP GGUF. Only then add it to the catalogue.
4. **Not verified:** the Pro multimodal path (EmbeddingGemma 2 image and audio through mtmd) and the VL reranker at runtime. Their hashes are verified but neither was run, because there was no disk space. There is no JNI code for media embeddings yet.
5. **Wiring:**
   - VisualPicker/ArtShelf: shortlist plus floor, then constrained choice;
   - Soundtrack/Planner: music by mood;
   - Understander: `Retrieval.hints`;
   - Proofreader/ScreenSpelling: the glossary;
   - Whisper `initial_prompt`: `Glossary.asrPrompt`;
   - recording the jury results into `TasteMemory` and `TasteTrainer`.

   After that, re-run the golden archive and the jury before/after comparison.
