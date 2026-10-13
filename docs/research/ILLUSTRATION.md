# Illustration layer for audio-only explainer films (phase 8c)

*Research date: 2026-10-13. Devices: Android S26 Ultra (12 GB) and the Windows laptop (i5-11320H, 16 GB, RTX 3050 Ti 4 GB). No cloud image service, no GPU rental.*

Legend:
- **[V]** verified on a primary page: the licence file, the model card, or the site's own licence page.
- **[M]** measured in this container: 4 vCPU x86, CPU only, no GPU.
- **[E]** my estimate.
- **[U]** unverified.

The owner's reference 2 shows monochrome pencil-sketch drawings with one saturated accent (yellow) and one dark (navy):
- a safe;
- a worried man holding a phone;
- three people in front of a storefront.

This document covers how Trimio makes pictures like these on the device, in **one style per film**.

---

## 0. TL;DR

1. **The style comes from code, not from the source.**
   - Every picture passes through one procedural styler, `SketchStyler` in engine/illustration. It draws:
     - pencil lines where painted areas meet, with a double stroke and pressure;
     - hatching by tone (one, two or three directions);
     - a graphite smudge and paper grain;
     - the film's accent laid flat under the lines;
     - everything else in grey.
   - The library, the creator's photos and a generator all come out in the same hand for a given `FilmLook`: style, accent, dark and seed.
   - The jury's `StyleCheck` verifies this:
     - every picture uses one look;
     - there is no colour outside grey and the accent pair;
     - ink density is not wildly off;
     - each picture shows its subject;
     - there are no letter-like marks.
2. **The bundled composable library covers the common case.** It is instant and licence-clean.
   - Sources:
     - Open Peeps, CC0 [V]: 174 pieces. These are poses (bust, standing, sitting, a doctor, a wheelchair), 51 hairstyles including **Hijab**, 33 expressions, facial hair and glasses.
     - Fluent Emoji Flat, MIT [V]: 230 curated objects and places with English and Persian keywords.
     - Three drawings of our own: a safe, gold bars, and a storefront with a blank sign band for a spoken name.
   - Size: 1.79 MB of JSON in the APK [M], about 0.6 MB compressed.
   - Speed: 30 typical Persian explainer sentences took 6.8 s in total [M], about 230 ms each at 512 px, on one thread of this container's CPU.
   - Result: 30/30 answered, with a keyword fit ≥ 0.5 for every one. Contact sheets: `docs/screenshots/illustration-*.png`.
3. **The on-device generator is a Pro/Ultra add-on.**
   - Runtime: **stable-diffusion.cpp**, MIT [V], built on **our own ggml**.
     - I built it here against `native/third_party/llama.cpp/ggml` with `-DSD_USE_UPSTREAM_GGML=ON`. It compiles and links [M].
   - Models, all commercial-OK:

     | Tier | Model | Licence | Files | Fits |
     |---|---|---|---|---|
     | Pro | **SDXS-512** (one step) | OpenRAIL++-M [V] | 683 MB | phone and laptop |
     | Ultra | **FLUX.2 [klein] 4B** | Apache-2.0 [V] | 5.2 GB | laptop only |

     - FLUX.2 [klein] 4B also reads Persian through its Qwen3 encoder and edits from reference pictures, which keeps a film's style.
   - Excluded as non-commercial:
     - SD-Turbo;
     - SDXL-Turbo;
     - FLUX.1-dev;
     - FLUX.2 [klein] 9B;
     - BRIA RMBG 1.4 and 2.0.
   - **One model was measured here [M]:** SDXS-512 Q8_0. It ran through `sd-cli` built on our ggml, CPU only, 4 threads, at 512×512 in one step.
     - Time: about 50 s per picture (text encoder 6.2 s, U-Net 27.6 s, tiny-VAE decode 18.3 s). The 4-vCPU container was shared with other agents' builds (load average ≈ 10), so this is a worst case.
     - Peak RSS: 1.18 GB.
     - The picture of "a worried young man looking at his phone" is already in reference 2's pencil look.
   - Every other model timing in §3 is an estimate.
4. **The creator's photos go into the film's look with no model.**
   - The steps:
     1. A border flood fill separates a plain background. When the background is busy, the photo fades to paper in a soft oval instead.
     2. XDoG finds the lines.
     3. Local contrast is lifted, so detail survives the hatching.
     4. Pixels of the accent's hue keep the accent.
     5. The shared `SketchStyler` draws the result.
   - Optional matting models for hard backgrounds: BiRefNet-lite (MIT [V]) or U²-Net (Apache-2.0 [V]), on the media agent's ONNX runtime.
5. **Persian context.**
   - In a Persian film a woman wears a headscarf by default (Open Peeps `Hijab`).
   - The library has a mosque, a classical government building and a storefront with a blank sign band.
   - Names are **never drawn into the picture.** The illustration returns a `signSlot` box, and the stage letters the spoken name there with its own Persian text engine. This keeps both garbled text and invented brands out of every picture.

---

## 1. Composable illustration libraries (CC0 or commercial-OK)

| Library | Licence (primary page) | Commercial | Attribution | Bundle or redistribute | Composable | Persian context | Verdict |
|---|---|---|---|---|---|---|---|
| **Open Peeps** (Pablo Stanley) | CC0 1.0 [V] [openpeeps.com](https://www.openpeeps.com/): "Free for commercial and personal use under CC0 License" | yes | no | yes (public domain) | **yes**: 584,688 combinations; busts, standing and sitting poses; faces; hair; accessories | **Hijab** head piece [V in package]; Turban (Sikh style); no chador | **Bundled.** The parts come from the `react-peeps` npm package 0.1.10. Its code is MIT [V LICENSE]; its drawings are Open Peeps, CC0. The SVG markup is extracted by `engine/illustration/tools/peeps.js`. |
| DiceBear `open-peeps` | Design CC0, code MIT [V package LICENSE] | yes | no | yes | heads and faces only (avatars) | Hijab, Turban | Subset of the above; not needed. |
| **Humaaans** (Pablo Stanley) | "CC0 Free for commercial or personal use" [V humaaans.com] | yes | no | yes | yes: tops, pants, poses, scenes | none | A good second people pack in a flat style. The styler pencils it. Download via humaaans.com or Blush. |
| **Open Doodles** (Pablo Stanley) | CC0, "No need to credit" [V opendoodles.com] | yes | no | yes | no: 33 whole scenes | none | Optional pack (reading, sitting, coffee). |
| **Fluent Emoji Flat** (Microsoft) | MIT [V github LICENSE] | yes | licence text in the app's notices, not in videos | yes | objects and places (3,145) | mosque 🕌, person with headscarf 🧕 | **Bundled (230 curated).** Its flat colour areas become tone, so the hatching has real shading. |
| Fluent Emoji High Contrast | MIT [V] | yes | notice | yes | 1,595 monochrome | same | Not needed: the flat set carries more tone. |
| **Health Icons** (Resolve to Save Lives) | MIT [V github LICENSE] | yes | notice | yes | 2,042 health icons | n/a | 8 bundled; more as a pack. |
| **Tabler Icons** | MIT [V github LICENSE] | yes | notice | yes | 6,238 line icons | n/a | Pack candidate for line props. The styler gives them a hand-drawn line. |
| **Openclipart** | CC0 1.0 for every upload [V openclipart.org/share] | yes | no | yes | huge (≈160k), mixed quality | some Middle-East items [U] | Curated pack candidate. Avoid items tagged `pd_issue`. |
| Lukasz Adam illustrations | "CC0 licensed … (MIT license)", "No attribution required" [V page] | yes | no | yes | objects, people, places | none | The download sits behind an email form; curate by hand. |
| Kenney | CC0 [U, home page reachable] | yes | no | yes | game assets | none | Wrong style. |
| **unDraw** | Own licence [V undraw.co/license]: no distribution "in packs or otherwise", no "replicate a similar or competing service", **no AI/ML use** | yes (in a project) | no | **no** | scenes | none | **Excluded** for bundling or packs. |
| Twemoji, Streamline (free), Pepicons, Game-icons | CC BY 4.0 / CC BY 3.0 [V iconify metadata] | yes | **yes** | yes | icons | – | Possible, but every video would need a credit line. Not used. |
| OpenMoji | CC BY-SA 4.0 [V iconify metadata] | – | – | – | – | – | **Excluded** (ShareAlike). |
| Public-domain engravings (Wikimedia Commons PD-old, Old Book Illustrations) | PD per file [U per item] | yes | no | yes | objects, people, places | Persian architecture engravings exist [U] | Closest to a real ink or pencil look. A future "engraving" pack needs a licence check per file. |

How the library is used (implemented):
- `People` reads the cast from the words. Persian and English are both supported, including colloquial plurals such as «کاربرا».
  - **Who:** man, woman, older person, doctor, or a group of 2–4.
  - **How they feel:** worried → `Concerned`, scared → `Fear`, happy → `SmileBig`, and so on.
  - **What they do:**
    - phone → `bust/Device`;
    - paper or contract → `bust/Paper`;
    - walking → `standing/Walking`;
    - and so on.
  - The figures are dressed deterministically from the film's seed.
  - Pieces are composed at react-peeps' own anchors: head at (225, 0), face (159, 186), beard (123, 338), glasses (47, 241).
- `LibrarySource` picks the parts of the scene:
  - the hero object or place;
  - up to two props, never a second copy of what the pose already holds;
  - the layout: people standing in front of a place, a hero with props in front of it, or a person with props at the side.
- It answers **null** when nothing matches above the floor, so the stage uses a diagram primitive instead of an unrelated picture.

---

## 2. On-device runtimes

| Runtime | Licence | Backends | Shares our ggml? | Notes |
|---|---|---|---|---|
| **stable-diffusion.cpp** (leejet, commit d637cfb, 2026-10-12) | MIT [V] | CPU (AVX2/AVX-512), CUDA, **Vulkan**, Metal, **OpenCL**, SYCL [V README] | **Yes [M].** `cmake/ggml.cmake` uses an existing `ggml` target. I configured it with `SD_GGML_SOURCE_DIR=native/third_party/llama.cpp/ggml` and `SD_USE_UPSTREAM_GGML=ON`. All 109 targets built and `sd-cli` linked (7 min 15 s, 3 threads). | Models it supports [V README]: SD1.x/2.x, SDXL, the distilled tiny U-Nets (SSD-1B, Vega, BK-SDM, **SDXS**), SD3.x, FLUX.1/FLUX.2 (incl. **klein**), Z-Image, Qwen-Image, PixArt, and others. Also LCM/LoRA, TAESD, ControlNet (SD 1.5), **IP-Adapter** (SD 1.5/SDXL), PhotoMaker, img2img, and reference editing for FLUX.2. Upstream ggml loses only INT8 tensorwise/convrot, and FP8 weights become F16 at load [V cmake warning]. |
| ONNX Runtime (+ QNN EP) | MIT | CPU, XNNPACK, QNN (Hexagon NPU) | no (third engine) | The media agent already adds ORT for perception. Running SD as ONNX on QNN, like Qualcomm AI Hub's SD 1.5, is fast on Snapdragon NPUs [U]. But it needs per-SoC context binaries, and the `libQnnHtp*` libraries add tens of MB [ADDONS.md §8.1]. |
| MNN diffusion (Alibaba) | Apache-2.0 [U] | CPU, OpenCL, Vulkan | no | It has an Android SD demo [U]. It would be a fourth engine. |

The build I ran [M]:

```sh
git clone --depth 1 https://github.com/leejet/stable-diffusion.cpp sdcpp
cmake -S sdcpp -B sdbuild -G Ninja -DCMAKE_BUILD_TYPE=Release -DSD_USE_UPSTREAM_GGML=ON \
  -DSD_GGML_SOURCE_DIR=<repo>/native/third_party/llama.cpp/ggml -DSD_WEBP=OFF -DSD_WEBM=OFF
ninja -C sdbuild -j3 sd-cli
```

**APK impact (Play ships native code in the APK; models are downloaded data).**
- Measured [M]: `sd-cli` is 41.6 MB, 39.9 MB stripped and 16.3 MB gzip, for x86-64.
  - Of that, **~142 MB of source is tokenizer vocabularies compiled in as headers**: `src/tokenizers/vocab/*.hpp`, for umt5, gemma, mistral, qwen and t5.
- For SDXS/SD 1.x we need only the CLIP vocabulary. FLUX.2 [klein] uses Qwen3, whose GGUF carries its own vocabulary.
- **Plan:** build `libtrimio_sd.so` with the vocabulary headers compiled out (a small patch), or load them from the model download.
  - Estimated [E]: **+5–9 MB** for arm64-v8a, against today's 97.6 MB debug APK and the ≤150 MB budget.
  - The shared ggml adds nothing; llama.cpp already links it.
- **Wiring** (next step, not done here):
  - Add a `TRIMIO_DIFFUSION` option in `native/CMakeLists.txt` that calls `add_subdirectory(third_party/stable-diffusion.cpp sd-build EXCLUDE_FROM_ALL)` after the shared ggml.
  - Add `trimio_sd` as its own JNI library with hidden visibility, as for whisper and llama.
  - Implement `DiffusionEngine` in a jvmCommon source set over `new_sd_ctx` / `generate_image`, which are in `stable-diffusion.h`.
  - Vulkan on the laptop (RTX 3050 Ti or Iris Xe) and OpenCL on Adreno, the same choices llama.cpp already makes.

---

## 3. Models (commercial-OK only)

Licences come from each model card's licence tag and text. Sizes and sha256 are the Hugging Face LFS values.

| Model | Licence | Size | RAM at work | Phone (S26U) s/image | Laptop s/image | Style control |
|---|---|---|---|---|---|---|
| **SDXS-512** (IDKiro, one-step, tiny U-Net + tiny VAE). GGUF: `concedo/sdxs-512-tinySDdistilled-GGUF` Q8_0 | OpenRAIL++-M [V card]. Commercial OK; use restrictions pass on to users | 683 MB (sha256 `409ab235…eb6a`, re-hashed here [M]); 652 MB of parameters [M] | **1.18 GB peak RSS [M]** | 6–15 s CPU, 1–3 s OpenCL/Vulkan [E] | 10–20 s CPU, <1 s CUDA/Vulkan [E]. Here: **≈ 50 s** on a contended 4-vCPU x86 [M] | Fixed style prompt + seed, then the styler. **Image-to-image from our sketch template does not work** at 1–2 steps [M]. A sketch-ControlNet variant exists (`IDKiro/sdxs-512-dreamshaper-sketch`, OpenRAIL++ [V tag]) but is not in sd.cpp [U]. |
| SD 1.5 Q4_0 (`second-state/stable-diffusion-v1-5-GGUF`) + **TCD LoRA** (`h1t/TCD-SD15-LoRA`, MIT [V tag]) or LCM-LoRA (OpenRAIL++ [V tag]) | CreativeML OpenRAIL-M [V tag] | 1.57 GB + 135 MB | ~2.5 GB [E] | 15–30 s for 4 steps CPU [E] | ~10 s CPU, ~2 s GPU [E] | img2img; ControlNet lineart/scribble (OpenRAIL [V tag]); **IP-Adapter** (Apache-2.0 [V tag]), which needs a 2.5 GB ViT-H image encoder [E]: too heavy for the phone. |
| BK-SDM-tiny Q4_0 (`synesterai/…`) | CreativeML OpenRAIL-M [V base] | 686 MB | ~1.4 GB [E] | 6–12 s for 4–8 steps [E] | 3–6 s [E] | No few-step distillation, so slower than SDXS for the same quality. |
| Segmind Vega / SSD-1B (SDXL distilled) | **Apache-2.0** [V tag] | 3.3 GB (fp16 safetensors) | ~4–5 GB [E] | too slow [E] | 15–40 s CPU, VegaRT LCM LoRA (Apache [V]) ~4 steps on GPU ~5 s [E] | Better than SD 1.5 at 1024 px; laptop only. |
| **FLUX.2 [klein] 4B** Q4_0 (`leejet/FLUX.2-klein-4B-GGUF`) + Qwen3-4B Q4_K_M + FLUX.2 small decoder | **Apache-2.0** [V LICENSE.md, Apache License 2.0] | 2.46 + 2.50 + 0.25 = **5.2 GB** | ~7 GB [E] | experimental only (≈ 8 GB footprint on 12 GB) [E] | **20–40 s** for 4 steps at 768 px with `--offload-to-cpu`; minutes on CPU only [E] | **Reference editing (`-r`)**: the film's first picture keeps the rest in its hand. This is the closest thing to an IP-Adapter on device. It understands Persian prompts through Qwen3. |
| Z-Image-Turbo (Tongyi) | Apache-2.0 [V tag] | 3.7 GB Q4_0 + Qwen3-4B 2.5 GB | ~8 GB [E] | no | 40–90 s [E] | Stronger realism; heavier than klein for our needs. |
| Nitro-E (AMD, 304 M) | MIT [V tag] | small [E] | – | – | – | Not in sd.cpp (PyTorch only); its Llama-3.2-1B encoder brings the Llama licence. Watch for a port. |
| Sana 600M / Sana Sprint | Now Apache-2.0, plus Gemma terms for the text encoder [V card] | – | – | – | – | Not in sd.cpp. |
| **Excluded** | SD-Turbo and SDXL-Turbo (Stability non-commercial / community terms), FLUX.1-dev and FLUX.2 [klein] 9B (non-commercial, `license:other`, gated [V]), SD 3.5 (Stability Community Licence, revenue cap [V tag `other`]), RMBG-1.4/2.0 (BRIA, non-commercial [V tag `other`]) | | | | | |

**Style LoRAs.** I found no "pencil sketch" LoRA with a clear commercial licence on a base we can use.
- Most are trained on FLUX.1-dev (non-commercial), or carry no licence.
- Recommendation: **no LoRA.** Unify the style with the styler instead. That works for every source, and no training is needed (training elsewhere is out of scope).

**Text artefacts.** Generators scribble pseudo-letters on signs and papers.
- The negative prompt keeps "text, letters, words, watermark, logo" out.
- `TextArtefacts` finds rows of four or more glyph-shaped blobs on one baseline. Such a picture is generated again with the next seed, at most twice; after that the library picture is kept.
- The media agent's PP-OCR add-on can confirm through `TextDetector`.
- Names on signs are lettered by the stage in the `signSlot`, never by the model.

**What was measured here, and what was not.**
- [M] The sd.cpp build against our ggml; binary sizes; the library pipeline timings and contact sheets.
- [M] SDXS-512 Q8_0, measured once the disk freed up: three text-to-image runs at 512 px, 1 step and seed 42, through `sd-cli` (sd.cpp `d763dae`, upstream ggml from our llama.cpp, CPU, `-t 4`):
  - **49.4–52.1 s** per picture;
  - peak RSS 1,177 MB;
  - breakdown: CLIP 6.2 s, U-Net 27.6 s, TAE decode 18.3 s;
  - the load average was ≈ 10 on 4 vCPUs (other agents' Gradle builds), so expect about 2–3× faster on an idle machine [E].
- **Image-to-image does not work with SDXS [M].** I fed it the library's safe sketch at strength 0.55 and 0.75 with 1 step, and at 0.5 with 2 steps. All three runs returned the same picture as text-to-image, with the init ignored: the one-step distilled model gives no structure from a partly noised init.
  - So SDXS is text-to-image only (`Recipes.sdxs.img2img = false`).
  - Composition control needs a multi-step model: SD 1.5 + TCD with img2img or ControlNet, or FLUX.2 klein's reference editing.
- A fourth run, "a woman wearing a headscarf reading a report" (34.8 s at load ≈ 5), is a convincing pencil portrait. It also has faint pseudo-text lines in the background, exactly the artefact `TextArtefacts` and the negative prompt exist for.
- How the first three pictures came out:
  - "a worried young man looking at his phone" matches reference 2's pencil look;
  - "a steel safe full of gold bars" became a generic metal box, a semantic miss. The library's own safe is better; this is why the library goes first;
  - "three people in front of a shop" is good, but the grey background fills the frame, so the styler falls back to the oval fade.
  - Sheet: `docs/screenshots/illustration-generator.png` (raw output next to the same picture in the film's look).
- [E] Every other model timing and RAM figure. There is no GPU here.
- The SHA256 values in `IllustrationAddOns`: SDXS was re-hashed after download [M]. The FLUX.2 ones are the upstream LFS hashes from the HF API.

---

## 4. The creator's photos in the film's style

1. **Classical (implemented, no model):** `PhotoStyler`.
   - **Cut-out.** The median colour of the border is the reference. A flood fill from the border, with a colour tolerance, separates a plain background when it covers 12–90 % of the frame. Otherwise the picture fades to paper in a soft oval (a "vignette sketch").
   - **Lines.** XDoG, an extended difference of Gaussians: two box-blur cascades, σ ≈ 0.9 and 1.44 at 384 px. The silhouette of the cut-out is added as a line.
   - **Tone.** Luminance with its local contrast lifted (detail ×1.8 around a 10-unit blur).
   - **Accent.** Saturated pixels within 12–24° of the accent's hue keep the accent: a yellow taxi stays yellow.
   - Then the same `SketchStyler` draws it, so a photo cannot look different from a library drawing.
2. **Optional small models (Pro), all on the media agent's ORT:**
   - BiRefNet-lite (MIT [V tag], ONNX available [V `onnx-community/BiRefNet_lite-ONNX`]) for hard backgrounds;
   - U²-Net (Apache-2.0 [V GitHub LICENSE]; `u2netp` is 4.7 MB [U]) for small and fast cut-outs;
   - Informative Drawings (Chan et al. 2022, MIT code [V GitHub LICENSE]; the weights' licence is not stated [U]) for artistic line drawings.
   - Not used yet. The classical pass is good on plain backgrounds, and the oval fade handles the rest.

---

## 5. Recommendation by tier (matches ADDONS.md)

| Tier | Phone (S26 Ultra 12 GB) | Laptop (i5-11320H, 16 GB, 3050 Ti 4 GB) | Download |
|---|---|---|---|
| **Built in (all)** | Library (Open Peeps CC0 + Fluent MIT + ours) + `SketchStyler` (3 looks: pencil, ink, flat) + `PhotoStyler` + `StyleCheck` | same | 0. The 1.8 MB library ships in the APK. |
| **Essential** | + the retrieval agent's embedder ranks library items by meaning. Wire it through the `ItemRanker` interface: `Retriever.search(CorpusIndex("illustration", items→Doc), Query(text))`. | same | 0 extra (the embedder is already Essential) |
| **Pro** | + **SDXS-512** generator for what the library cannot show (1–3 s per picture on the GPU, 6–15 s on the CPU [E]; ≈ 50 s measured on a contended 4-vCPU x86 [M]); + BiRefNet-lite cut-outs | + SDXS-512 (GPU) | 683 MB (+ ~45 MB matting) |
| **Ultra** | (FLUX.2 klein is "experimental" on 12 GB; not offered by default) | + **FLUX.2 [klein] 4B** with reference-style consistency and Persian prompts (20–40 s per picture [E]) | 5.2 GB |

Order inside a film (the `Illustrator`):
1. The creator's photo for that moment, when one is attached.
2. The **library**, kept whenever its fit is ≥ 0.8.
3. The generator, only when installed and the library falls short.
4. Otherwise the best picture with fit ≥ 0.5.
5. Otherwise none: the stage uses a primitive.

A vision check (the director's eyes), when installed, can only lower a library picture's fit, and it decides for generated ones.

**Optional cloud path.** The owner's Anthropic key cannot generate images: Claude outputs text.
- A future option is "Claude draws an SVG". The vector would go through the same styler, exactly like a library item. It would suit props the library lacks.
- Not implemented. There is no other cloud image service.

---

## 6. Interface for the stage and the screenwriter (proposal)

- The screenwriter adds, per scene, an `IllustrationRequest`:
  - `subject`, `action`, `objects`, `place`, `mood`;
  - `sign`, only when the narration says a name;
  - `aspect`, `line`, `language`, the optional `photo`, and `id`.
- The pipeline calls `Illustrator.create(...)` once per film and then `illustrator.illustrate(request, look)`. The `FilmLook` comes from the film's `ArtDirection`: the accent and dark of the palette, and the seed.
- The result: `Illustration.image` is a `Raster` (ARGB, transparent background); `Raster.toImageBitmap()` turns it into a bitmap on Android and desktop. It also carries:
  - `subject`, a box to aim motion at;
  - `signSlot`, where the stage letters the name on a `dark` plate;
  - `fit`, `shown`, `missing`, `items` and `look` for the jury.
- Small additive proposals for the contract, for the STAGE and SCREENWRITER owners to decide:
  1. a `Primitive.Illustration` (`"illustration"`), whose `BeatContent.visual` is the request id; or
  2. the request inline as `BeatContent.illustration: IllustrationRequest?`.

  The picture reaches the stage as a `StillMedia(id, bitmap)` in its `MediaSources`, so `engine/motion` needs no dependency on this module.

---

## 7. Sources

- Open Peeps: https://www.openpeeps.com/ (CC0). react-peeps: https://www.npmjs.com/package/react-peeps (MIT code). DiceBear open-peeps LICENSE (design CC0, code MIT).
- Humaaans: https://www.humaaans.com/ (CC0). Open Doodles: https://www.opendoodles.com/ (CC0). Openclipart: https://openclipart.org/share (CC0). unDraw: https://undraw.co/license. Lukasz Adam: https://lukaszadam.com/illustrations.
- Fluent Emoji: https://github.com/microsoft/fluentui-emoji/blob/main/LICENSE (MIT). Health Icons: https://github.com/resolvetosavelives/healthicons/blob/main/LICENSE (MIT). Tabler: https://github.com/tabler/tabler-icons/blob/main/LICENSE (MIT). Iconify collection metadata: https://api.iconify.design/collections.
- stable-diffusion.cpp: https://github.com/leejet/stable-diffusion.cpp (README, docs/distilled_sd.md, docs/flux2.md, docs/z_image.md, docs/taesd.md, cmake/ggml.cmake).
- Model cards: stable-diffusion-v1-5/stable-diffusion-v1-5, latent-consistency/lcm-lora-sdv1-5, h1t/TCD-SD15-LoRA, IDKiro/sdxs-512-0.9, IDKiro/sdxs-512-dreamshaper, IDKiro/sdxs-512-dreamshaper-sketch, concedo/sdxs-512-tinySDdistilled-GGUF, segmind/Segmind-Vega, segmind/SSD-1B, nota-ai/bk-sdm-tiny, black-forest-labs/FLUX.2-klein-4B (LICENSE.md, Apache-2.0), black-forest-labs/FLUX.2-klein-9B (other), black-forest-labs/FLUX.2-small-decoder, leejet/FLUX.2-klein-4B-GGUF, unsloth/Qwen3-4B-GGUF, Tongyi-MAI/Z-Image-Turbo, amd/Nitro-E, Efficient-Large-Model/Sana_600M_1024px_diffusers, stabilityai/stable-diffusion-3.5-medium, h94/IP-Adapter, lllyasviel/control_v11p_sd15_lineart, ZhengPeng7/BiRefNet(_lite), briaai/RMBG-1.4/2.0, madebyollin/taesd.
- U²-Net: https://github.com/xuebinqin/U-2-Net (Apache-2.0). Informative Drawings: https://github.com/carolineec/informative-drawings (MIT).
