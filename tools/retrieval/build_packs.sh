#!/usr/bin/env bash
# Builds the int8 vector packs the app ships (engine/retrieval composeResources files/retrieval/packs/):
# every bundled corpus embedded offline with the same embedder and prompts the device uses
# (io.trimio.engine.models.TextAddOns.embedder). The phone then only embeds each sentence.
#
#   tools/retrieval/build_packs.sh <dir with the embedder GGUF>
#
# Rebuild after changing the art index, a corpus JSON (primitives, families, sounds, exemplars,
# glossary) or the embedder; documents a pack does not cover are embedded on the device instead
# (their fingerprints differ), so a stale pack is slower, never wrong.
set -euo pipefail
root="$(cd "$(dirname "$0")/../.." && pwd)"
models="$(cd "${1:?models dir}" && pwd)"
cd "$root"
./gradlew :engine:llm:buildHostNative --console=plain -q
./gradlew :engine:retrieval:jvmTest --tests '*VectorPackBuildTest*' --rerun \
  -Pretrieval.build=packs -Pretrieval.encoders="$models" -Pretrieval.native="$root/engine/llm/build/host-native" --console=plain -q -i \
  | grep '^packs:' || true
ls -l engine/retrieval/src/commonMain/composeResources/files/retrieval/packs/
