# Retrieval agent: handoff (stopped early on coordinator request, 2026-10-13)

Nothing is committed. The work left in the tree builds and its tests pass. Most of the task has not been started.

## Done (verified)
- **Persian text key**: `core/model/src/commonMain/kotlin/io/trimio/core/model/text/TextKey.kt` is rewritten.
  - `of()` keeps its old meaning (a compact key with no spaces) and now also:
    - unifies the digits ۰–۹, ٠–٩ and 0–9;
    - maps ئ→ی, ؤ→و, ۀ/ة→ه and ٱ→ا;
    - drops BOM, LRM/RLM, ZWJ, tatweel and harakat.
  - New functions for BM25:
    - `fold(ch)`: one character, folded;
    - `phrase()`: keeps spaces and collapses ZWNJ;
    - `words()`;
    - `terms()`: the joined and the split forms of half-space words, plus «می روم» → «میروم» and «کتاب ها» → «کتابها».
- New tests in `core/model/src/commonTest/kotlin/io/trimio/core/model/TextKeyTest.kt`. `:core:model:jvmTest` is green.
  - Not yet run: detekt, and the tests of the modules that use TextKey (engine/assets ArtCatalogue, engine/motion Visuals.key, engine/director Lexicon).
  - The golden archive was not re-run either. The new folds could move scores slightly.
- **Models downloaded and verified** in `scratchpad/models-addons/` (1.58 GB). Every sha256 matches the HF LFS oid:

  | File | HF repo | Size (bytes) | sha256 |
  |---|---|---|---|
  | `embeddinggemma-2-Q8_0.gguf` | ggml-org/embeddinggemma-2-GGUF | 309,855,456 | `2188ac1deca4b77dffefd603c2776a9d76d9d74ec01841392982ebb840b09135` |
  | `bge-m3-q8_0.gguf` | ggml-org/bge-m3-Q8_0-GGUF | 634,553,760 | `aa473d51f451a22f0fcf39ba3330c14bed38a385712b1113440f69df4047a173` |
  | `qwen3-reranker-0.6b-q8_0.gguf` | ggml-org/Qwen3-Reranker-0.6B-Q8_0-GGUF | 639,153,184 | `22c9979ce4fbcdc5acdc310c6641c32797eff1aa980b8f7a2db8a8ea23429a48` |
  | `ggml-silero-v6.2.0.bin` | ggml-org/whisper-vad | 885,098 | `2aa269b785eeb53a82983a20501ddf7c1d9c48e33ab63a41391ac6c9f7fb6987` |

  Also listed by HF but not downloaded: embeddinggemma-300M-Q8_0 (333,590,944 bytes, `b5ce9d77…0d63`). Delete this folder if disk is needed. Free disk was about 1.3 GB.

## Half-done
Only the two TextKey files above have been touched. No other repo file was changed.

## Findings to reuse
- **Rerank.** With `--pooling rank`, llama.cpp applies softmax over [yes, no] for qwen3. `llama_get_embeddings_seq()[0]` is P(yes), the same value the server uses as its score.
  - The GGUF rerank template has a fixed instruct line. For our own instruction, build the prompt like this:

    ```
    <|im_start|>system
    Judge whether the Document meets the requirements based on the Query and the Instruct provided. Note that the answer can only be "yes" or "no".<|im_end|>
    <|im_start|>user
    <Instruct>: …
    <Query>: …
    <Document>: …<|im_end|>
    <|im_start|>assistant
    <think>

    </think>

    ```

    Tokenize it with parse_special.
- **Embedding.** Mirror examples/embedding/embedding.cpp:
  - context: embeddings=true, n_ubatch = n_batch, kv_unified, n_seq_max = the batch;
  - before each batch: `llama_memory_clear`;
  - after `llama_decode`: read `llama_get_embeddings_seq` for each sequence, then L2-normalise;
  - take the output size from `llama_model_n_embd_out`.

  EmbeddingGemma 2 uses mean pooling. The card says to run it in bf16 or fp32, never fp16. Use the Q8_0 file on CPU and probe for NaN on load. Prompt formats:
  - query: `task: search result | query: …`
  - document: `title: none | text: …`
- **Whisper VAD.** Our vendored whisper.cpp supports `params.vad`, but it maps only *segment* times back to the original timeline. *Token* t0/t1, which our JNI uses for word times, stay in the cut timeline. So build VAD in the JNI ourselves:
  1. find the speech segments with the `whisper_vad_*` API;
  2. concatenate the speech audio;
  3. map the token times back.
- **Catalogue size.** The app's picture catalogue is the ArtCatalogue: 1,742 bundled entries plus content packs (feature/library core art.json has 1,337). VisualLibrary is empty in the apps. Int8 vectors are therefore under 3 MB and can ship in the app. Entries from packs can be embedded lazily on the device.
- **Golden archive.** It runs with no LLM. Wiring that is gated on the installed add-on leaves it unchanged by default.

## Next (in order)
1. Run detekt and the tests of the modules that use TextKey; run the golden archive; if needed, extend the folding to `Proofreader.key`, `ScreenSpelling.plain` and `Phrases.key`.
2. Create the new module `engine/retrieval` (commonMain, depending only on core:model):
   - `Embedder`/`Reranker` interfaces;
   - BM25;
   - an int8 brute-force index;
   - RRF (k=60), then rerank, then a relevance floor;
   - the vector-pack format;
   - corpora: art, music/SFX, exemplars, a glossary of a few hundred hand-written terms, user memory.
3. JNI: `embedInit`/`embed`/`rerank` in `native/llama/trimio_llama_jni.cpp`; `LlamaEmbedder` and a lazy `LlamaReranker` in engine/llm.
4. Add `ModelKind` Rerank and Vad plus the catalogue entries, then the «تقویت کیفیت» group in ModelShelf/ModelManagerScreen.
5. Build the eval set (300–500 labelled lines). Measure the old ArtCatalogue search against hybrid search, choose EmbeddingGemma 2 or bge-m3, and calibrate the floor.
6. Pack builder under tools/.
7. Wiring:
   - VisualPicker/ArtShelf;
   - the music weights in Planner.chooseMusic;
   - Understander hints;
   - the glossary for the proofreader;
   - whisper VAD.
8. All gates.
