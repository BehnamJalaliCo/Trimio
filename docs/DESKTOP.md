# Trimio desktop (phase 7.7)

The desktop app is the same Trimio: the shared Compose UI (`shared`, `feature/*`), the same
automatic engine (`engine/production` → `engine/autopilot`), with desktop engines underneath:
ffmpeg for decoding and encoding, Skia for frames, whisper.cpp and llama.cpp through JNI.
**Windows ships first; macOS is prepared but not built.**

```
./gradlew :desktopApp:run                    # run from source (ffmpeg/ffprobe on the PATH)
./gradlew :desktopApp:test                   # DI graph, platform rules, headless window screenshot
./gradlew :desktopApp:createDistributable    # app image for the current OS (no installer)
./gradlew :desktopApp:packageMsi packageExe  # Windows installers, on Windows (CI does this)
```

## Module layout

| Path | What |
|---|---|
| `desktopApp/src/main/kotlin/io/trimio/desktop/Main.kt` | entry point, window (1360×880, min 1040×680), files passed on the command line (file associations) |
| `DesktopShell.kt` | desktop layout: side rail (new from video / voice, drop zone, output folder, this computer's RAM, GPU and directors) + the shared app in a readable column; drag and drop anywhere |
| `DesktopPlatform.kt` | the Koin platform module: paths, file dialogs, sharer, device info, `ProductionPlatform` (`JvmProductionPlatform`) + `AutoEditor` and its checkpoints, API-key store |
| `DesktopEnvironment.kt` | `DesktopDirs` (where files live), `BundledTools` (points the engines at the installed ffmpeg and DLLs) |
| `DesktopHardware.kt` | RAM, GPUs, `DesktopDirectors` (which on-device directors this machine is offered) |
| `DesktopFiles.kt` | system open dialogs for video, audio and attachments; "share" = reveal in Explorer/Finder |
| `desktopApp/icons/` | `trimio.ico` / `.icns` / `.png`, drawn by `make_icons.py` from the Android launcher mark |
| `desktopApp/resources/<os>/` | packaged next to the app; CI fills `windows/ffmpeg` and `windows/native` (git-ignored) |
| `shared/.../IncomingMedia.kt` | dropped or opened files → the create screen (optional binding; other platforms ignore it) |
| `core/model/src/jvmMain/.../MediaTools.kt` | which `ffmpeg`/`ffprobe` and H.264 encoder every JVM engine uses |

## Where files go

| | Windows | macOS | Linux |
|---|---|---|---|
| App data (models, packs, cache, projects, settings) | `%APPDATA%\Trimio` | `~/Library/Application Support/Trimio` | `$XDG_DATA_HOME/trimio` |
| Finished videos | `Videos\Trimio` (the Videos library's real location, read from the shell folders) | `~/Movies/Trimio` | `~/Videos/Trimio` |

Inside app data: `models/` (ModelStore downloads, sha256-checked), `packs/` (signed packs, verified
with the Ed25519 keys in `engine/models`), `cache/` (remote config, legacy renders), `projects/`,
`productions/` (checkpoints that let a killed run resume), `crashes/`, `settings.json`,
`secrets.properties` (cloud API keys, per-user folder). `-Dtrimio.home=<dir>` moves everything
under one directory (tests, portable use).

Note: models are multi-gigabyte and `%APPDATA%` is the *roaming* profile. On domain machines with
roaming profiles, moving `models/` to `%LOCALAPPDATA%` is a one-line change in `DesktopDirs`.

## Models on desktop

`DesktopHardware` reads RAM (JVM `OperatingSystemMXBean`, rounded to marketed GB) and GPUs
(`nvidia-smi` for name + VRAM; otherwise `Win32_VideoController` on Windows, `system_profiler` on
macOS), off the UI thread. Memory is rated with `DeviceMemory.desktop` (`DeviceFit` desktop bands).
`DesktopDirectors`:

- **≥ 24 GB RAM:** Qwen3.6 35B-A3B (experimental: offered, never auto-picked), 9B, 4B.
- **< 24 GB:** 9B and 4B. The 35B is removed from the catalogue the director picker sees, so it
  never runs on a smaller machine even if its file is present.
- Recommended: the largest offered, non-experimental model that fits comfortably, else the 4B.

## Native engines (JNI)

`native/CMakeLists.txt` builds `trimio_llama` and `trimio_whisper`. On Windows CI (MSVC + Ninja):

- `native/cpu/` — x64 with AVX2/FMA/F16C (every x64 CPU since ~2015), static CRT, no OpenMP: the
  DLLs need no Visual C++ redistributable.
- `native/vulkan/` — the same plus `GGML_VULKAN` for llama.cpp and whisper.cpp. It links
  `vulkan-1.dll`, which every current NVIDIA/AMD/Intel driver installs. CUDA was not chosen: a CUDA
  build only serves NVIDIA, adds ~500 MB of runtime DLLs and a long CI build; Vulkan covers all
  three vendors.

Loading (`NativeLibraries` in engine/asr, `LlamaNative` in engine/llm): `-Dtrimio.native.dir` is the
folder, `-Dtrimio.native.variants` the subfolders best first. `BundledTools` sets
`vulkan,cpu` on Windows and `metal,cpu` on macOS. A variant whose DLL fails to load (no Vulkan
driver) falls through to the next; the flat folder and finally `System.loadLibrary` come last, so
development and Android are unchanged. With a GPU variant loaded, llama.cpp offloads as many
layers as the dedicated GPU's free memory holds (the cache counted from the model's own header: a
hybrid Qwen3.5 caches only its attention layers), the rest on the CPU
(`-Dtrimio.llama.gpuLayers=N` overrides; 0 forces CPU). A model the GPU can run less than 90% of
(by weight size, e.g. Q3_K/IQ quants on Adreno OpenCL) stays on the CPU altogether
(`-Dtrimio.llama.minGpuCoverage`). Whisper runs on the same dedicated GPU (`-Dtrimio.whisper.gpu=off`
for the CPU), beam 3 (`-Dtrimio.whisper.beam=N`, 1 is greedy).

Speed knobs (`-Dtrimio.llama.*`): `kv` (`q8_0` default, `f16`), `flash` (`auto`, `on`, `off`),
`batch`/`ubatch` (512), `prefixCache` (on: the head a request shares with the previous one is read
once; a hybrid model restores a snapshot of its state there), `lazyGrammar` (on: the grammar checks
the sampled token and masks the whole vocabulary only when it rejects it), `log=calls` (one line per
call on stderr). Every engine reports where it ran: `LanguageModel.runtime` (backend, GPU, layers on
the GPU, threads, cache, tokens and speed), `SpeechRecognizer.runtime` (backend, GPU, beam, real-time
factor), `MotionExporter.profile` (frames per second; per-frame draw, footage, read-out and encoder
wait). Each also prints one `trimio …` line on stderr.

## Rendering

`MotionExporter` draws frames in half-float, reads them out as 16-bit RGBA (Skia converts) and
pipes them to ffmpeg; each of its workers (about one per two hardware threads, at most three)
draws its run of frames while a second thread writes the previous one. On Windows the frames are
drawn by the GPU through Skia's Direct3D 12 backend (`GpuCanvas`, dedicated GPU first); each
worker first compares its first GPU frame with the CPU's and keeps the CPU on a mismatch, and a
process that dies inside a GPU render leaves `cache/gpu-render.state`, after which exports stay on
the CPU (`-Dtrimio.render.gpu=on|off`). The H.264 encoder is the graphics card's own when ffmpeg can
open it here (`h264_nvenc`, `h264_qsv`, `h264_amf`, all in the LGPL build; tried once per process
on a tiny clip, `-Dtrimio.h264.hw=off` to skip), else the software one below. Footage is decoded
with `-hwaccel auto` on Windows (`-Dtrimio.ffmpeg.hwaccel`). Unverified on real Windows hardware so
far: the GPU canvas, NVENC/QSV/AMF and hardware decoding (this repo's CI has no GPU).

## ffmpeg and licensing (decision)

**Bundle an LGPL ffmpeg and encode H.264 with Windows' Media Foundation encoder (`h264_mf`).**

- What we use: decoding of the user's video/audio (FFmpeg's own decoders), scaling/colour
  conversion (`swscale`), the concat demuxer, mp4 muxing, AAC encoding (native encoder), H.264
  encoding. Only the last needs a choice: libx264 is GPL-only, and bundling it would make the
  shipped ffmpeg GPL.
- The Windows bundle is BtbN/FFmpeg-Builds' `win64-lgpl` static build (`ffmpeg.exe`, `ffprobe.exe`),
  pinned in `desktopApp/ffmpeg/windows.properties`. CI checks the sha256 against the pin and the
  release's checksums, fails if `-buildconf` contains `--enable-gpl`/`--enable-nonfree` or libx264
  is present, and requires `h264_mf` and `aac` encoders.
- Software encoder selection is one place, `MediaTools.h264(...)` (a hardware encoder, see
  Rendering, comes first when one works): libx264 with the existing tuning when
  `trimio.h264.encoder` is unset (development, Linux CI, servers); `h264_mf` with unconstrained VBR
  (¾ of the delivery's max rate on average, the max rate as ceiling, closed GOPs) when the packaged
  Windows app sets it. Media Foundation's software encoder is part of every desktop Windows
  edition except "N" editions without the Media Feature Pack.
- The app runs ffmpeg as a separate process; it is never linked. `LICENSE.txt` and `SOURCE.txt`
  ship next to the executables; `docs/THIRD_PARTY_NOTICES.md` has the details and what would change
  if libx264 were ever bundled (GPL obligations for the bundled ffmpeg, x264 licence).

**Pinning still to do:** the first CI run prints the sha256 of the `latest` LGPL build. Before the
first release tag, set `tag` to a monthly autobuild tag (kept for two years), `asset` to its zip
and `sha256` to the printed hash. Tag builds refuse to package without a pin.

Quality note: `h264_mf` has no CRF; at the same bitrate it is below x264 `slow`, so desktop exports
use a generous bitrate. Hardware MF encoders (`-hw_encoding 1`) are faster but vary by driver; not
enabled yet.

## Packaging (Compose `nativeDistributions`)

- Windows: MSI and EXE, **per-user install** (no admin prompt, `%LOCALAPPDATA%\Programs`),
  Start-menu group "Trimio", desktop shortcut, install-folder chooser, a fixed `upgradeUuid` so
  newer MSIs upgrade in place, and file associations for `.mp4` / `.mov` ("Open with Trimio" starts
  an edit). The full JDK module set is bundled (`includeAllModules`).
- Version: `-Ptrimio.desktop.version=1.2.3` (tags `v1.2.3` / `desktop-v1.2.3` set it in CI);
  MSI needs `MAJOR.MINOR.BUILD` with MAJOR ≥ 1.
- jpackage cannot build MSI/EXE on Linux, so `.github/workflows/desktop-windows.yml` (windows-latest,
  `workflow_dispatch` + tags) builds the DLLs, fetches/verifies ffmpeg, runs the JVM tests (and
  loads the freshly built DLLs through the app's loader), packages, optionally signs, and uploads
  `trimio-windows-installers` and `trimio-windows-native`.
- Code signing (optional): secrets `WINDOWS_SIGNING_CERT_BASE64` (.pfx, base64) and
  `WINDOWS_SIGNING_CERT_PASSWORD`; `signtool` signs the MSI and EXE with an RFC 3161 timestamp.
  Unsigned installers work but SmartScreen warns. Signing the launcher and DLLs inside the image
  needs a jpackage `--app-image` two-step; listed below.

## What is verified where

| Check | Linux server | Windows CI |
|---|---|---|
| desktopApp compiles, Koin graph resolves (AutoEditor, ProductionPlatform) | yes | yes |
| Headless main-window screenshot (`build/screenshots/desktop-main-window.png`) | yes | yes |
| `createDistributable` (packaging config) | yes (Linux image) | — |
| DLLs build with MSVC, CPU + Vulkan | — | yes |
| DLLs load through `NativeLibraries` / variant fallback | — | yes (`-Ptrimio.nativeSmoke`) |
| ffmpeg pin, LGPL check, `h264_mf` present | — | yes |
| `h264_mf` actually encodes | — | best effort (Server images may lack Media Foundation) |
| MSI / EXE produced, installs per-user | — | builds yes; install by hand |
| A full production run on Windows with real models | — | no: manual test (phase 8.5) |

## Shipping macOS later

Already in place: `macOS { }` in `desktopApp/build.gradle.kts` (bundle id `io.trimio.desktop`,
category video, minimum macOS 12, DMG target, entitlements in `desktopApp/macos/`, signing and
notarisation read from `TRIMIO_MAC_*` environment variables), the loader's `metal,cpu` variants,
`~/Library/Application Support/Trimio` and `~/Movies/Trimio`, and a disabled `macos` job in the
workflow. Needed:

1. Apple Developer Program account; a "Developer ID Application" certificate in the CI keychain
   (secrets `MAC_SIGNING_IDENTITY`, plus the certificate import step), and an app-specific
   password or API key for notarisation (`MAC_NOTARY_APPLE_ID`, `MAC_NOTARY_PASSWORD`,
   `MAC_NOTARY_TEAM_ID`).
2. An LGPL ffmpeg/ffprobe for macOS arm64 (and x64 if Intel Macs matter) built with VideoToolbox:
   no public LGPL macOS builds are pinned yet, so build one in CI from a release tag with
   `--disable-gpl --enable-videotoolbox`, then set `trimio.h264.encoder=h264_videotoolbox` in
   `BundledTools` (and add its arguments to `MediaTools.h264`).
3. Metal build of the engines (`GGML_METAL=ON`, `GGML_METAL_EMBED_LIBRARY=ON`) into
   `resources/macos/native/metal`, a CPU fallback into `native/cpu`; every dylib and the ffmpeg
   binaries must be signed with the app (hardened runtime) or notarisation fails.
4. Review the entitlements (JIT and unsigned executable memory are needed by the JVM; library
   validation stays on once all native code is signed by us).
5. Enable the job (`if: false` → tag trigger), run `notarizeDmg`, staple, test on a clean Mac.

## Open items

- Pin the ffmpeg build (above) before the first tagged release.
- Sign the app image contents (launcher, DLLs, ffmpeg) before the installer, not only the
  installer: package with `createDistributable`, sign, then `jpackage --app-image`.
- Protect `secrets.properties` with DPAPI (Windows) / Keychain (macOS).
- Attachments can be dropped only through the create screen's picker; a drop on the window
  starts a new edit from the first video or recording.
- Media Foundation is missing on Windows "N" editions: detect and point the user at the Media
  Feature Pack (or fall back to the BSD-licensed OpenH264 encoder in the same LGPL build).
