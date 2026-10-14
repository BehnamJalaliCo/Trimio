// The image engine's tokenizer data: only what our two picture models read, CLIP's merges (SDXS) and Qwen's
// (FLUX.2 klein's text encoder). sd.cpp's own vocab.cpp embeds six more vocabularies, about 26 MB in each engine
// library (and the phone carries two: processor and GPU); here their loaders return nothing.
#include "../third_party/stable-diffusion.cpp/src/tokenizers/vocab/vocab.h"
#include "../third_party/stable-diffusion.cpp/src/tokenizers/vocab/clip_merges.hpp"
#include "../third_party/stable-diffusion.cpp/src/tokenizers/vocab/qwen_merges.hpp"

std::string load_clip_merges() {
    return std::string(reinterpret_cast<const char*>(clip_merges_utf8_c_str), sizeof(clip_merges_utf8_c_str));
}

std::string load_qwen2_merges() {
    return std::string(reinterpret_cast<const char*>(qwen2_merges_utf8_c_str), sizeof(qwen2_merges_utf8_c_str));
}

std::string load_mistral_merges() { return {}; }
std::string load_mistral_vocab_json() { return {}; }
std::string load_t5_tokenizer_json() { return {}; }
std::string load_umt5_tokenizer_json() { return {}; }
std::string load_gemma_merges() { return {}; }
std::string load_gemma_vocab_json() { return {}; }
