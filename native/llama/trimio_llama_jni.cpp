// JNI bridge between io.trimio.engine.llm.local.LlamaNative and llama.cpp.
//
// One handle owns a model and a context. Generation is single-sequence: the prompt is decoded in
// n_batch chunks, then tokens are sampled one at a time through (optional GBNF grammar) ->
// temperature/top-p/min-p -> seeded dist.
//
// Prompt reuse: consecutive requests usually share a long head (the system prompt, the brief).
// That head is decoded once: a plain attention model keeps it in its KV cache and only the tail
// is decoded again; a recurrent or hybrid model (Qwen3.5's gated delta net) cannot cut its state
// back, so its state is snapshotted at the shared head and restored for the next request.
//
// Output is streamed as UTF-8 *bytes*: a byte-level BPE token can end in the middle of a Persian
// letter, so only complete UTF-8 sequences are handed to Kotlin and the tail waits for the next token.

#include <jni.h>

#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstdio>
#include <filesystem>
#include <mutex>
#include <string>
#include <vector>

#include "llama.h"
#include "gguf.h"
#include "ggml-backend.h"
#include "mtmd.h"
#include "mtmd-helper.h"

namespace {

/** What a call cost, read by Kotlin right after it (LlamaNative.lastCall). */
struct CallStats {
    int prompt_tokens = 0;
    int reused_tokens = 0;
    int generated = 0;
    double prompt_ms = 0;
    double generate_ms = 0;
    int grammar_resamples = 0;
};

/** Options from Kotlin (LlamaNative.Options, in this order). */
struct Options {
    int kv_type = GGML_TYPE_F16;   // a ggml_type: 1 = f16, 8 = q8_0
    int flash = -1;                // llama_flash_attn_type: -1 auto, 0 off, 1 on
    int n_batch = 512;
    int n_ubatch = 512;
    bool prefix_cache = true;
    bool lazy_grammar = true;
    double min_gpu_coverage = 0.9; // share of the weights the GPU must be able to run
};

struct Engine {
    llama_model * model = nullptr;
    llama_context * ctx = nullptr;
    const llama_vocab * vocab = nullptr;
    // The model's eyes (vision projector), when loaded.
    mtmd_context * vision = nullptr;
    Options options;
    /** How the model was placed (devices, layers, cache), as key=value lines. */
    std::string info;
    /** Recurrent, hybrid or sliding-window memory cannot just drop a tail: it reuses a head through [snapshot]. */
    bool recurrent = false;
    /** Tokens whose state is in the context's memory right now (plain attention models). */
    std::vector<llama_token> held;
    /** The previous text prompt: where the next one diverges from it is where a head ends. */
    std::vector<llama_token> previous;
    /** The sequence state after [snapshot_tokens] (recurrent/hybrid models). */
    std::vector<uint8_t> snapshot;
    std::vector<llama_token> snapshot_tokens;
    CallStats last;
};

using Clock = std::chrono::steady_clock;

double ms_since(Clock::time_point t0) {
    return std::chrono::duration<double, std::milli>(Clock::now() - t0).count();
}

std::string to_string(JNIEnv * env, jstring s) {
    if (s == nullptr) return {};
    const char * chars = env->GetStringUTFChars(s, nullptr);
    std::string out(chars);
    env->ReleaseStringUTFChars(s, chars);
    return out;
}

std::string from_bytes(JNIEnv * env, jbyteArray bytes) {
    if (bytes == nullptr) return {};
    const jsize n = env->GetArrayLength(bytes);
    std::string out(static_cast<size_t>(n), '\0');
    env->GetByteArrayRegion(bytes, 0, n, reinterpret_cast<jbyte *>(out.data()));
    return out;
}

jbyteArray to_bytes(JNIEnv * env, const std::string & s) {
    jbyteArray out = env->NewByteArray(static_cast<jsize>(s.size()));
    env->SetByteArrayRegion(out, 0, static_cast<jsize>(s.size()), reinterpret_cast<const jbyte *>(s.data()));
    return out;
}

/** Length of the longest prefix of [s] that does not end inside a UTF-8 sequence. */
size_t complete_utf8_prefix(const std::string & s) {
    size_t i = s.size();
    // Walk back over at most 3 continuation bytes to the lead byte of the last sequence.
    size_t back = 0;
    while (i > 0 && back < 4 && (static_cast<unsigned char>(s[i - 1]) & 0xC0) == 0x80) { --i; ++back; }
    if (i == 0) return back == 0 ? 0 : s.size();
    const auto lead = static_cast<unsigned char>(s[i - 1]);
    size_t need = 1;
    if ((lead & 0xE0) == 0xC0) need = 2;
    else if ((lead & 0xF0) == 0xE0) need = 3;
    else if ((lead & 0xF8) == 0xF0) need = 4;
    return (back + 1 >= need) ? s.size() : i - 1;
}

std::vector<llama_token> tokenize(const llama_vocab * vocab, const std::string & text, bool add_special) {
    int n = -llama_tokenize(vocab, text.c_str(), static_cast<int32_t>(text.size()), nullptr, 0, add_special, true);
    std::vector<llama_token> tokens(static_cast<size_t>(std::max(n, 0)));
    if (n > 0) llama_tokenize(vocab, text.c_str(), static_cast<int32_t>(text.size()), tokens.data(), n, add_special, true);
    return tokens;
}

std::string piece(const llama_vocab * vocab, llama_token token) {
    char buf[256];
    int n = llama_token_to_piece(vocab, token, buf, sizeof(buf), 0, false);
    if (n >= 0) return {buf, static_cast<size_t>(n)};
    std::string big(static_cast<size_t>(-n), '\0');
    llama_token_to_piece(vocab, token, big.data(), -n, 0, false);
    return big;
}

/** Log lines worth keeping for the runtime report while a model loads; the rest is dropped. */
std::mutex log_mutex;
std::string load_log;

void quiet_log(ggml_log_level level, const char * text, void *) {
    // Keep logcat clean in release; errors still surface through return codes.
    if (text == nullptr) return;
    const std::string line(text);
    static const char * keep[] = {"offloaded", "model buffer size", "KV buffer size", "compute buffer size", "flash_attn", "graph splits", "using device"};
    for (const char * k : keep) {
        if (line.find(k) != std::string::npos) {
            std::lock_guard<std::mutex> lock(log_mutex);
            if (load_log.size() < 8192) load_log += "log=" + line.substr(0, line.find_last_not_of("\n") + 1) + "\n";
            break;
        }
    }
    (void) level;
}

size_t common_prefix(const std::vector<llama_token> & a, const std::vector<llama_token> & b) {
    size_t n = std::min(a.size(), b.size());
    size_t i = 0;
    while (i < n && a[i] == b[i]) ++i;
    return i;
}

/** Fills [cur] with the last position's logits. */
llama_token_data_array candidates(Engine * engine, std::vector<llama_token_data> & cur) {
    const float * logits = llama_get_logits_ith(engine->ctx, -1);
    const int n_vocab = llama_vocab_n_tokens(engine->vocab);
    cur.resize(static_cast<size_t>(n_vocab));
    for (llama_token t = 0; t < n_vocab; t++) cur[t] = llama_token_data{t, logits[t], 0.0f};
    return llama_token_data_array{cur.data(), cur.size(), -1, false};
}

/**
 * The next token. The grammar is checked against the token the sampler picks first and applied
 * to the whole vocabulary only when it rejects it: masking ~250k candidates every step costs more
 * than the rest of sampling, and the picked token is almost always already valid. When the
 * grammar is applied first ([lazy] off) the result is the same for greedy decoding.
 */
llama_token next_token(Engine * engine, llama_sampler * chain, llama_sampler * grammar, std::vector<llama_token_data> & cur, bool lazy) {
    llama_token_data_array arr = candidates(engine, cur);
    if (grammar != nullptr && !lazy) llama_sampler_apply(grammar, &arr);
    llama_sampler_apply(chain, &arr);
    llama_token token = arr.data[arr.selected].id;
    if (grammar != nullptr && lazy) {
        llama_token_data single{token, 1.0f, 0.0f};
        llama_token_data_array one{&single, 1, -1, false};
        llama_sampler_apply(grammar, &one);
        if (std::isinf(single.logit) && single.logit < 0) {
            engine->last.grammar_resamples++;
            arr = candidates(engine, cur);
            llama_sampler_apply(grammar, &arr);
            llama_sampler_apply(chain, &arr);
            token = arr.data[arr.selected].id;
        }
    }
    if (grammar != nullptr) llama_sampler_accept(grammar, token);
    llama_sampler_accept(chain, token);
    return token;
}

/** The sampling loop shared by text and image prompts; [n_past] tokens are already decoded. */
jint sample(JNIEnv * env, Engine * engine, int n_past, jstring jgrammar, jint max_tokens, jfloat temperature, jfloat top_p, jfloat min_p, jint seed, jobject callback) {
    jclass cls = env->GetObjectClass(callback);
    jmethodID on_text = env->GetMethodID(cls, "onText", "([B)Z");
    env->DeleteLocalRef(cls);
    const int n_ctx = static_cast<int>(llama_n_ctx(engine->ctx));
    llama_sampler * grammar = nullptr;
    const std::string grammar_text = to_string(env, jgrammar);
    if (!grammar_text.empty()) {
        grammar = llama_sampler_init_grammar(engine->vocab, grammar_text.c_str(), "root");
        if (grammar == nullptr) return -3;
    }
    llama_sampler * chain = llama_sampler_chain_init(llama_sampler_chain_default_params());
    if (temperature <= 0.0f) {
        llama_sampler_chain_add(chain, llama_sampler_init_greedy());
    } else {
        llama_sampler_chain_add(chain, llama_sampler_init_top_p(top_p, 1));
        llama_sampler_chain_add(chain, llama_sampler_init_min_p(min_p, 1));
        llama_sampler_chain_add(chain, llama_sampler_init_temp(temperature));
        llama_sampler_chain_add(chain, llama_sampler_init_dist(static_cast<uint32_t>(seed)));
    }

    const auto t0 = Clock::now();
    std::vector<llama_token_data> cur;
    int status = 1;
    std::string pending;
    for (int i = 0; i < max_tokens; ++i) {
        llama_token token = next_token(engine, chain, grammar, cur, engine->options.lazy_grammar);
        engine->last.generated++;
        if (llama_vocab_is_eog(engine->vocab, token)) {
            status = 0;
            break;
        }
        pending += piece(engine->vocab, token);
        const size_t ready = complete_utf8_prefix(pending);
        if (ready > 0) {
            jbyteArray chunk = to_bytes(env, pending.substr(0, ready));
            const jboolean keep_going = env->CallBooleanMethod(callback, on_text, chunk);
            env->DeleteLocalRef(chunk);
            pending.erase(0, ready);
            if (env->ExceptionCheck() || !keep_going) {
                status = 2;
                break;
            }
        }
        if (++n_past >= n_ctx) break;
        if (llama_decode(engine->ctx, llama_batch_get_one(&token, 1)) != 0) {
            status = -2;
            break;
        }
        engine->held.push_back(token);
    }
    engine->last.generate_ms = ms_since(t0);
    if (!pending.empty() && status >= 0 && status != 2) {
        jbyteArray chunk = to_bytes(env, pending);
        env->CallBooleanMethod(callback, on_text, chunk);
        env->DeleteLocalRef(chunk);
    }
    llama_sampler_free(chain);
    if (grammar != nullptr) llama_sampler_free(grammar);
    return status;
}

void ensure_backend() {
    static bool backend_ready = false;
    if (!backend_ready) {
        llama_log_set(quiet_log, nullptr);
        llama_backend_init();
        backend_ready = true;
    }
}

/** Decodes tokens [from, to) of [prompt] in n_batch chunks; false when a decode fails. */
bool decode_range(Engine * engine, const std::vector<llama_token> & prompt, size_t from, size_t to) {
    const auto batch = static_cast<size_t>(std::max(1, engine->options.n_batch));
    for (size_t i = from; i < to; i += batch) {
        const auto n = static_cast<int32_t>(std::min(batch, to - i));
        if (llama_decode(engine->ctx, llama_batch_get_one(const_cast<llama_token *>(prompt.data()) + i, n)) != 0) return false;
    }
    return true;
}

/** Brings the context's memory to the longest reusable head of [prompt]; returns its length. */
size_t reuse_head(Engine * engine, const std::vector<llama_token> & prompt) {
    llama_memory_t mem = llama_get_memory(engine->ctx);
    if (!engine->options.prefix_cache) {
        llama_memory_clear(mem, true);
        engine->held.clear();
        return 0;
    }
    // At least the last prompt token is decoded again: its logits start the reply.
    const size_t limit = prompt.size() - 1;
    if (!engine->recurrent) {
        const size_t keep = std::min(common_prefix(engine->held, prompt), limit);
        if (keep > 0 && llama_memory_seq_rm(mem, 0, static_cast<llama_pos>(keep), -1)) {
            engine->held.resize(keep);
            return keep;
        }
        llama_memory_clear(mem, true);
        engine->held.clear();
        return 0;
    }
    llama_memory_clear(mem, true);
    engine->held.clear();
    const size_t n = engine->snapshot_tokens.size();
    if (n == 0 || n > limit || common_prefix(engine->snapshot_tokens, prompt) != n) return 0;
    if (llama_state_seq_set_data(engine->ctx, engine->snapshot.data(), engine->snapshot.size(), 0) == 0) {
        llama_memory_clear(mem, true);
        return 0;
    }
    engine->held.assign(prompt.begin(), prompt.begin() + static_cast<long>(n));
    return n;
}

/** Shared heads shorter than this are not worth a snapshot. */
constexpr size_t MIN_HEAD = 64;

/** Decodes [prompt] reusing what it can; false when a decode fails. */
bool decode_prompt(Engine * engine, const std::vector<llama_token> & prompt) {
    const auto t0 = Clock::now();
    const size_t start = reuse_head(engine, prompt);
    // Recurrent/hybrid: snapshot where this prompt leaves the previous one (the shared head), so
    // the next request with the same head starts there.
    size_t head = 0;
    if (engine->recurrent && engine->options.prefix_cache) {
        head = std::min(common_prefix(engine->previous, prompt), prompt.size() - 1);
        if (head < MIN_HEAD || head <= start) head = 0;
    }
    bool ok = true;
    if (head > 0) {
        ok = decode_range(engine, prompt, start, head);
        if (ok) {
            engine->snapshot.resize(llama_state_seq_get_size(engine->ctx, 0));
            const size_t written = llama_state_seq_get_data(engine->ctx, engine->snapshot.data(), engine->snapshot.size(), 0);
            engine->snapshot.resize(written);
            if (written > 0) engine->snapshot_tokens.assign(prompt.begin(), prompt.begin() + static_cast<long>(head));
            else engine->snapshot_tokens.clear();
        }
        ok = ok && decode_range(engine, prompt, head, prompt.size());
    } else {
        ok = decode_range(engine, prompt, start, prompt.size());
    }
    engine->held = ok ? prompt : std::vector<llama_token>{};
    engine->previous = prompt;
    engine->last.prompt_tokens = static_cast<int>(prompt.size());
    engine->last.reused_tokens = static_cast<int>(start);
    engine->last.prompt_ms = ms_since(t0);
    if (!ok) llama_memory_clear(llama_get_memory(engine->ctx), true);
    return ok;
}

/** What the layer fit needs from a GGUF's header (read without loading any weights). */
struct Shape {
    int blocks = 0;
    /** Context cache bytes per token for the whole model at f16; 0 when the header does not say. */
    size_t cache_per_token = 0;
};

/** A u32 key, or the largest entry of a per-layer u32/i32 array; 0 when missing. */
uint32_t u32_or_max(const gguf_context * g, const std::string & key) {
    const int64_t id = gguf_find_key(g, key.c_str());
    if (id < 0) return 0;
    const auto type = gguf_get_kv_type(g, id);
    if (type == GGUF_TYPE_UINT32) return gguf_get_val_u32(g, id);
    if (type == GGUF_TYPE_INT32) return static_cast<uint32_t>(std::max(0, gguf_get_val_i32(g, id)));
    if (type != GGUF_TYPE_ARRAY) return 0;
    const auto arr = gguf_get_arr_type(g, id);
    if (arr != GGUF_TYPE_UINT32 && arr != GGUF_TYPE_INT32) return 0;
    const auto * v = static_cast<const int32_t *>(gguf_get_arr_data(g, id));
    uint32_t best = 0;
    for (size_t i = 0; i < gguf_get_arr_n(g, id); i++) best = std::max(best, static_cast<uint32_t>(std::max(0, v[i])));
    return best;
}

/**
 * Blocks and context-cache size of the GGUF at [path]. Only attention layers keep a cache: a
 * hybrid model (Qwen3.5: one attention layer in four, the others a fixed-size recurrent state)
 * needs a fraction of what a plain transformer of its size does.
 */
Shape shape_of(const std::string & path) {
    Shape out;
    gguf_init_params gp = {/* no_alloc */ true, /* ctx */ nullptr};
    gguf_context * g = gguf_init_from_file(path.c_str(), gp);
    if (g == nullptr) return out;
    const int64_t arch_id = gguf_find_key(g, "general.architecture");
    if (arch_id >= 0) {
        const std::string arch = gguf_get_val_str(g, arch_id);
        out.blocks = static_cast<int>(u32_or_max(g, arch + ".block_count"));
        const uint32_t heads = u32_or_max(g, arch + ".attention.head_count");
        const uint32_t kv_heads = u32_or_max(g, arch + ".attention.head_count_kv");
        const uint32_t embd = u32_or_max(g, arch + ".embedding_length");
        uint32_t key = u32_or_max(g, arch + ".attention.key_length");
        uint32_t value = u32_or_max(g, arch + ".attention.value_length");
        if (key == 0 && heads > 0) key = embd / heads;
        if (value == 0) value = key;
        const uint32_t interval = u32_or_max(g, arch + ".full_attention_interval");
        const int attention = interval > 1 ? out.blocks / static_cast<int>(interval) : out.blocks;
        if (kv_heads > 0 && key > 0) out.cache_per_token = size_t(attention) * kv_heads * (key + value) * 2;
    }
    gguf_free(g);
    return out;
}

/**
 * The share (0..1) of the model's matrix weights, by size, that [dev] can multiply itself, tested
 * the way llama.cpp places weights (a 512-token batch). A weight the GPU cannot run stays on the
 * CPU and every layer then bounces between the two: on a phone's OpenCL that is slower than the
 * CPU alone (the Adreno backend has no Q3_K / IQ kernels, which the UD-Q3_K_XL quants use).
 */
double gpu_coverage(const std::string & path, ggml_backend_dev_t dev) {
    ggml_context * meta = nullptr;
    gguf_init_params gp = {/* no_alloc */ true, /* ctx */ &meta};
    gguf_context * g = gguf_init_from_file(path.c_str(), gp);
    if (g == nullptr || meta == nullptr) {
        if (g != nullptr) gguf_free(g);
        return 1.0;
    }
    ggml_init_params params = {ggml_tensor_overhead() * 8, nullptr, true};
    double total = 0;
    double supported = 0;
    for (ggml_tensor * w = ggml_get_first_tensor(meta); w != nullptr; w = ggml_get_next_tensor(meta, w)) {
        const std::string name = ggml_get_name(w);
        // Matrices only (norms and conv kernels are tiny); the token embedding is a lookup on the CPU.
        if (ggml_n_dims(w) < 2 || w->ne[0] < 64 || w->ne[1] < 64 || name.rfind("token_embd", 0) == 0) continue;
        if (name.find(".weight") == std::string::npos) continue;
        ggml_context * ctx = ggml_init(params);
        ggml_tensor * b = ggml_new_tensor_4d(ctx, GGML_TYPE_F32, w->ne[0], 512, w->ne[2], w->ne[3]);
        ggml_tensor * op = ggml_mul_mat(ctx, w, b);
        const auto bytes = static_cast<double>(ggml_nbytes(w));
        total += bytes;
        // A dummy buffer of the device's own type, as llama.cpp does: some backends check it.
        w->buffer = ggml_backend_buft_alloc_buffer(ggml_backend_dev_buffer_type(dev), 0);
        if (ggml_backend_dev_supports_op(dev, op)) supported += bytes;
        if (w->buffer != nullptr) ggml_backend_buffer_free(w->buffer);
        w->buffer = nullptr;
        ggml_free(ctx);
    }
    ggml_free(meta);
    gguf_free(g);
    return total > 0 ? supported / total : 1.0;
}

/**
 * The GPUs the model runs on: the dedicated ones when there are any (a laptop's integrated GPU
 * would otherwise take a share of the layers, from system memory, and slow the dedicated one
 * down), else the integrated ones. Null-terminated, as llama_model_params.devices wants.
 */
std::vector<ggml_backend_dev_t> gpus() {
    std::vector<ggml_backend_dev_t> dedicated;
    std::vector<ggml_backend_dev_t> integrated;
    for (size_t i = 0; i < ggml_backend_dev_count(); i++) {
        ggml_backend_dev_t dev = ggml_backend_dev_get(i);
        const auto type = ggml_backend_dev_type(dev);
        if (type == GGML_BACKEND_DEVICE_TYPE_GPU) dedicated.push_back(dev);
        if (type == GGML_BACKEND_DEVICE_TYPE_IGPU) integrated.push_back(dev);
    }
    std::vector<ggml_backend_dev_t> chosen = dedicated.empty() ? integrated : dedicated;
    if (!chosen.empty()) chosen.push_back(nullptr);
    return chosen;
}

/**
 * Layers to offload when the caller asks for "as many as fit" (-1): the free memory of the GPUs,
 * less a reserve for the compute buffers, over the size of one layer. The rest runs on the CPU
 * from RAM, so a 9B or 35B model works on any graphics card, fast where it fits.
 */
int fitting_layers(const std::string & path, int n_ctx, const std::vector<ggml_backend_dev_t> & devices, bool quantized_cache) {
    // Several cards (a workstation's two or three) pool their memory: llama.cpp spreads the
    // layers over them by free memory. Each card keeps its own compute reserve.
    size_t best = 0;
    size_t cards = 0;
    for (ggml_backend_dev_t dev : devices) {
        if (dev == nullptr) continue;
        size_t free = 0;
        size_t total = 0;
        ggml_backend_dev_memory(dev, &free, &total);
        best += free;
        cards++;
    }
    const Shape shape = shape_of(path);
    const int blocks = shape.blocks;
    std::error_code error;
    const auto bytes = std::filesystem::file_size(path, error);
    if (best == 0 || blocks <= 0 || error) return 0;
    // A layer on the GPU brings its weights and its share of the context cache; the compute
    // buffers (and a recurrent model's state) are a fixed reserve. The cache is the model's own
    // (attention layers × KV heads × head size), a q8_0 cache about half of f16; a header that
    // does not say gets a generous 160 KB per token.
    size_t per_token = shape.cache_per_token > 0 ? shape.cache_per_token : (size_t(160) << 10);
    if (quantized_cache) per_token = per_token * 17 / 32;
    const size_t layer = bytes / static_cast<size_t>(blocks + 1)
        + static_cast<size_t>(n_ctx) * per_token / static_cast<size_t>(blocks);
    const size_t reserve = (size_t(768) << 20) * std::max<size_t>(cards, 1);
    if (best <= reserve + layer) return 0;
    return std::min(blocks + 1, static_cast<int>((best - reserve) / layer));
}

const char * device_kind(ggml_backend_dev_t dev) {
    switch (ggml_backend_dev_type(dev)) {
        case GGML_BACKEND_DEVICE_TYPE_GPU: return "gpu";
        case GGML_BACKEND_DEVICE_TYPE_IGPU: return "igpu";
        case GGML_BACKEND_DEVICE_TYPE_ACCEL: return "accel";
        default: return "cpu";
    }
}

/** Every device ggml sees, one line each: kind, name, description, free/total MiB. */
std::string device_lines() {
    std::string out;
    for (size_t i = 0; i < ggml_backend_dev_count(); i++) {
        ggml_backend_dev_t dev = ggml_backend_dev_get(i);
        size_t free = 0;
        size_t total = 0;
        ggml_backend_dev_memory(dev, &free, &total);
        char line[512];
        std::snprintf(line, sizeof(line), "device=%s|%s|%s|%zu|%zu\n", device_kind(dev), ggml_backend_dev_name(dev),
            ggml_backend_dev_description(dev), free >> 20, total >> 20);
        out += line;
    }
    return out;
}

Options read_options(JNIEnv * env, jintArray joptions) {
    Options o;
    if (joptions == nullptr) return o;
    const jsize n = env->GetArrayLength(joptions);
    std::vector<jint> v(static_cast<size_t>(n));
    if (n > 0) env->GetIntArrayRegion(joptions, 0, n, v.data());
    if (n > 0) o.kv_type = v[0];
    if (n > 1) o.flash = v[1];
    if (n > 2 && v[2] > 0) o.n_batch = v[2];
    if (n > 3 && v[3] > 0) o.n_ubatch = v[3];
    if (n > 4) o.prefix_cache = v[4] != 0;
    if (n > 5) o.lazy_grammar = v[5] != 0;
    if (n > 6) o.min_gpu_coverage = v[6] / 100.0;
    return o;
}

llama_context * make_context(llama_model * model, int n_ctx, int n_threads, const Options & o, bool gpu) {
    llama_context_params cp = llama_context_default_params();
    cp.n_ctx = static_cast<uint32_t>(n_ctx);
    cp.n_batch = static_cast<uint32_t>(o.n_batch);
    cp.n_ubatch = static_cast<uint32_t>(std::min(o.n_ubatch, o.n_batch));
    cp.n_threads = n_threads;
    cp.n_threads_batch = n_threads;
    cp.flash_attn_type = static_cast<llama_flash_attn_type>(o.flash);
    cp.type_k = static_cast<ggml_type>(o.kv_type);
    cp.type_v = static_cast<ggml_type>(o.kv_type);
    // CPU only: no device to hand host weights to for big batches.
    cp.op_offload = gpu;
    // Only the last position's logits are ever read: reserving them for a whole batch would cost
    // ~0.5 GB with a 248k vocabulary (the difference between fitting an 8 GB phone or not).
    cp.n_outputs_max = 1;
    cp.no_perf = true;
    return llama_init_from_model(model, cp);
}

// --- Encoders: text embeddings and relevance judges (retrieval) --------------------------------
//
// An encoder is a model of its own, on the CPU (the GPU belongs to the director), with a context
// made for many short sequences at once:
//  - EMBED: pooled embeddings as the GGUF says (EmbeddingGemma: mean, bge-m3: CLS, Qwen3-Embedding:
//    last token), L2-normalised, as llama.cpp's embedding example and server do;
//  - RANK: the GGUF's rank pooling (`--pooling rank`): for Qwen3-Reranker the softmax of the yes/no
//    head on the last token, P(yes) first — kept to check JUDGE against;
//  - JUDGE: the same reranker run as a causal model: every (instruction, query) head is decoded once
//    and shared by all documents (copied sequences of one unified cache), only each document's last
//    token is output, and P(yes) = softmax(logit_yes, logit_no). The same number as RANK (the rank
//    head's two rows are the lm_head's yes/no rows), without reserving vocabulary-sized logits for
//    every token of the batch (~300 MB per 512 tokens with a 151k vocabulary).

enum EncoderMode { EMBED = 0, RANK = 1, JUDGE = 2 };

struct Encoder {
    llama_model * model = nullptr;
    llama_context * ctx = nullptr;
    const llama_vocab * vocab = nullptr;
    mtmd_context * media = nullptr;
    int mode = EMBED;
    int n_ctx = 0;
    int n_seq = 1;
    int dims = 0;
    llama_token yes = -1;
    llama_token no = -1;
    std::string info;
};

void batch_add(llama_batch & b, llama_token token, llama_pos pos, llama_seq_id seq, bool output) {
    const int i = b.n_tokens;
    b.token[i] = token;
    b.pos[i] = pos;
    b.n_seq_id[i] = 1;
    b.seq_id[i][0] = seq;
    b.logits[i] = output ? 1 : 0;
    b.n_tokens++;
}

/** [t] cut to [limit] tokens, keeping its head and its last few (the end marker or the judge's question). */
std::vector<llama_token> fit_tokens(const std::vector<llama_token> & t, size_t limit) {
    if (t.size() <= limit) return t;
    const size_t tail = std::min<size_t>(16, limit / 4);
    std::vector<llama_token> out(t.begin(), t.begin() + static_cast<long>(limit - tail));
    out.insert(out.end(), t.end() - static_cast<long>(tail), t.end());
    return out;
}

bool all_finite(const float * v, int n) {
    for (int i = 0; i < n; i++) if (!std::isfinite(v[i])) return false;
    return true;
}

void l2_normalise(float * v, int n) {
    double sum = 0;
    for (int i = 0; i < n; i++) sum += double(v[i]) * v[i];
    const float inv = sum > 0 ? static_cast<float>(1.0 / std::sqrt(sum)) : 0.0f;
    for (int i = 0; i < n; i++) v[i] *= inv;
}

/** Sequences [from, to) of [seqs] in one decode; each pooled vector into [out] (dims floats each). */
bool encode_batch(Encoder * e, const std::vector<std::vector<llama_token>> & seqs, size_t from, size_t to, bool normalise, float * out) {
    size_t total = 0;
    for (size_t s = from; s < to; s++) total += seqs[s].size();
    llama_batch batch = llama_batch_init(static_cast<int32_t>(total), 0, 1);
    for (size_t s = from; s < to; s++) {
        for (size_t i = 0; i < seqs[s].size(); i++) batch_add(batch, seqs[s][i], static_cast<llama_pos>(i), static_cast<llama_seq_id>(s - from), true);
    }
    llama_memory_clear(llama_get_memory(e->ctx), true);
    bool ok = llama_decode(e->ctx, batch) == 0;
    for (size_t s = from; ok && s < to; s++) {
        const float * v = llama_get_embeddings_seq(e->ctx, static_cast<llama_seq_id>(s - from));
        if (v == nullptr || !all_finite(v, e->dims)) {
            ok = false;
            break;
        }
        float * dst = out + (s - from) * static_cast<size_t>(e->dims);
        std::copy(v, v + e->dims, dst);
        if (normalise) l2_normalise(dst, e->dims);
    }
    llama_batch_free(batch);
    return ok;
}

/** Embeds (or rank-pools) every sequence, as many per decode as the context and sequence count allow. */
bool encode_all(Encoder * e, const std::vector<std::vector<llama_token>> & seqs, bool normalise, std::vector<float> & out) {
    out.assign(seqs.size() * static_cast<size_t>(e->dims), 0.0f);
    size_t from = 0;
    while (from < seqs.size()) {
        size_t to = from;
        size_t tokens = 0;
        while (to < seqs.size() && static_cast<int>(to - from) < e->n_seq && tokens + seqs[to].size() <= static_cast<size_t>(e->n_ctx)) {
            tokens += seqs[to].size();
            to++;
        }
        if (to == from) return false;
        if (!encode_batch(e, seqs, from, to, normalise, out.data() + from * static_cast<size_t>(e->dims))) return false;
        from = to;
    }
    return true;
}

/** The single token [word] tokenizes to, or -1. */
llama_token single_token(const llama_vocab * vocab, const std::string & word) {
    const auto t = tokenize(vocab, word, false);
    return t.size() == 1 ? t[0] : -1;
}

/**
 * P(yes) for every document after one shared [head]: the head is decoded once in sequence 0, each
 * document of a group continues a copy of it (sequences 1..n) with only its last token output.
 */
bool judge_all(Encoder * e, const std::vector<llama_token> & head, const std::vector<std::vector<llama_token>> & docs, std::vector<float> & out) {
    llama_memory_t mem = llama_get_memory(e->ctx);
    llama_memory_clear(mem, true);
    const auto n_head = static_cast<llama_pos>(head.size());
    if (head.empty() || static_cast<int>(head.size()) + 8 >= e->n_ctx) return false;
    {
        llama_batch batch = llama_batch_init(static_cast<int32_t>(head.size()), 0, 1);
        for (size_t i = 0; i < head.size(); i++) batch_add(batch, head[i], static_cast<llama_pos>(i), 0, false);
        const bool ok = llama_decode(e->ctx, batch) == 0;
        llama_batch_free(batch);
        if (!ok) return false;
    }
    out.assign(docs.size(), 0.0f);
    const size_t room = static_cast<size_t>(e->n_ctx) - head.size();
    size_t from = 0;
    while (from < docs.size()) {
        size_t to = from;
        size_t tokens = 0;
        while (to < docs.size() && static_cast<int>(to - from) < e->n_seq - 1 && tokens + docs[to].size() <= room) {
            tokens += docs[to].size();
            to++;
        }
        if (to == from) return false;
        llama_batch batch = llama_batch_init(static_cast<int32_t>(tokens), 0, 1);
        std::vector<int> last(to - from);
        for (size_t s = from; s < to; s++) {
            const auto seq = static_cast<llama_seq_id>(s - from + 1);
            llama_memory_seq_cp(mem, 0, seq, -1, -1);
            for (size_t i = 0; i < docs[s].size(); i++) {
                const bool final = i + 1 == docs[s].size();
                if (final) last[s - from] = batch.n_tokens;
                batch_add(batch, docs[s][i], n_head + static_cast<llama_pos>(i), seq, final);
            }
        }
        bool ok = llama_decode(e->ctx, batch) == 0;
        for (size_t s = from; ok && s < to; s++) {
            const float * logits = llama_get_logits_ith(e->ctx, last[s - from]);
            if (logits == nullptr || !std::isfinite(logits[e->yes]) || !std::isfinite(logits[e->no])) {
                ok = false;
                break;
            }
            out[s] = 1.0f / (1.0f + std::exp(logits[e->no] - logits[e->yes]));
        }
        for (size_t s = from; s < to; s++) llama_memory_seq_rm(mem, static_cast<llama_seq_id>(s - from + 1), -1, -1);
        llama_batch_free(batch);
        if (!ok) return false;
        from = to;
    }
    return true;
}

Encoder * open_encoder(const std::string & path, int n_ctx, int n_threads, int n_seq, int mode) {
    llama_model_params mp = llama_model_default_params();
    std::vector<ggml_backend_dev_t> cpu_only = {nullptr};
    mp.devices = cpu_only.data();
    mp.n_gpu_layers = 0;
    llama_model * model = llama_model_load_from_file(path.c_str(), mp);
    if (model == nullptr) return nullptr;
    if (llama_model_has_encoder(model) && llama_model_has_decoder(model)) {
        llama_model_free(model);
        return nullptr;
    }
    llama_context_params cp = llama_context_default_params();
    cp.n_ctx = static_cast<uint32_t>(n_ctx);
    cp.n_batch = static_cast<uint32_t>(n_ctx);
    // A non-causal encoder (BERT, EmbeddingGemma) reads a whole batch in one micro-batch.
    cp.n_ubatch = static_cast<uint32_t>(n_ctx);
    cp.n_seq_max = static_cast<uint32_t>(std::max(1, n_seq));
    cp.kv_unified = true;
    cp.n_threads = n_threads;
    cp.n_threads_batch = n_threads;
    cp.op_offload = false;
    cp.no_perf = true;
    if (mode == JUDGE) {
        cp.embeddings = false;
        cp.n_outputs_max = static_cast<uint32_t>(std::max(1, n_seq));
    } else {
        cp.embeddings = true;
        cp.pooling_type = mode == RANK ? LLAMA_POOLING_TYPE_RANK : LLAMA_POOLING_TYPE_UNSPECIFIED;
    }
    llama_context * ctx = llama_init_from_model(model, cp);
    if (ctx == nullptr) {
        llama_model_free(model);
        return nullptr;
    }
    auto * e = new Encoder{model, ctx, llama_model_get_vocab(model)};
    e->mode = mode;
    e->n_ctx = n_ctx;
    e->n_seq = std::max(1, n_seq);
    const auto pooling = llama_pooling_type(ctx);
    bool usable = true;
    if (mode == EMBED) {
        e->dims = llama_model_n_embd_out(model);
        usable = pooling != LLAMA_POOLING_TYPE_NONE && pooling != LLAMA_POOLING_TYPE_RANK;
    } else if (mode == RANK) {
        e->dims = static_cast<int>(llama_model_n_cls_out(model));
        usable = e->dims > 0;
    } else {
        e->dims = 1;
        e->yes = single_token(e->vocab, "yes");
        e->no = single_token(e->vocab, "no");
        usable = e->yes >= 0 && e->no >= 0;
    }
    char desc[256];
    llama_model_desc(model, desc, sizeof(desc));
    char line[512];
    std::snprintf(line, sizeof(line), "model=%s\nmode=%d\npooling=%d\ndims=%d\nctx=%d\nseqs=%d\nthreads=%d\nusable=%d\n",
        desc, mode, static_cast<int>(pooling), e->dims, n_ctx, e->n_seq, n_threads, usable ? 1 : 0);
    e->info = line;
    if (!usable) {
        llama_free(ctx);
        llama_model_free(model);
        delete e;
        return nullptr;
    }
    return e;
}

void close_encoder(Encoder * e) {
    if (e == nullptr) return;
    if (e->media != nullptr) mtmd_free(e->media);
    llama_free(e->ctx);
    llama_model_free(e->model);
    delete e;
}

std::vector<std::vector<llama_token>> tokenize_all(JNIEnv * env, const llama_vocab * vocab, jobjectArray texts, bool add_special, size_t limit) {
    const jsize n = env->GetArrayLength(texts);
    std::vector<std::vector<llama_token>> out;
    out.reserve(static_cast<size_t>(n));
    for (jsize i = 0; i < n; ++i) {
        auto * bytes = static_cast<jbyteArray>(env->GetObjectArrayElement(texts, i));
        auto t = tokenize(vocab, from_bytes(env, bytes), add_special);
        env->DeleteLocalRef(bytes);
        if (t.empty()) t.push_back(llama_vocab_bos(vocab) >= 0 ? llama_vocab_bos(vocab) : 0);
        out.push_back(fit_tokens(t, limit));
    }
    return out;
}

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_io_trimio_engine_llm_local_LlamaNative_init(JNIEnv * env, jobject, jstring jpath, jint n_ctx, jint n_threads, jint gpu_layers, jintArray joptions) {
    ensure_backend();
    {
        std::lock_guard<std::mutex> lock(log_mutex);
        load_log.clear();
    }
    const std::string path = to_string(env, jpath);
    Options options = read_options(env, joptions);
    std::string info = device_lines();

    llama_model_params mp = llama_model_default_params();
    std::vector<ggml_backend_dev_t> devices = gpus();
    std::vector<ggml_backend_dev_t> cpu_only = {nullptr};
    double coverage = 1.0;
    if (!devices.empty()) coverage = gpu_coverage(path, devices.front());
    // "As many as fit" keeps a model the GPU can only partly run on the CPU altogether.
    const bool gated = gpu_layers < 0 && !devices.empty() && coverage < options.min_gpu_coverage;
    if (gated) devices.clear();
    mp.devices = devices.empty() ? cpu_only.data() : devices.data();
    mp.n_gpu_layers = devices.empty() ? 0 : gpu_layers < 0 ? fitting_layers(path, n_ctx, devices, options.kv_type != GGML_TYPE_F16) : gpu_layers;
    const int requested = mp.n_gpu_layers;
    llama_model * model = llama_model_load_from_file(path.c_str(), mp);
    // A GPU that runs out of memory (or a driver that fails) leaves the model to the CPU.
    bool fell_back = false;
    if (model == nullptr && mp.n_gpu_layers > 0) {
        mp.n_gpu_layers = 0;
        mp.devices = cpu_only.data();
        model = llama_model_load_from_file(path.c_str(), mp);
        fell_back = model != nullptr;
    }
    if (model == nullptr) return 0;
    const bool on_gpu = mp.n_gpu_layers > 0;

    llama_context * ctx = make_context(model, n_ctx, n_threads, options, on_gpu);
    // A quantized cache needs flash attention; where the device cannot do it, the plain cache.
    if (ctx == nullptr && options.kv_type != GGML_TYPE_F16) {
        options.kv_type = GGML_TYPE_F16;
        options.flash = LLAMA_FLASH_ATTN_TYPE_AUTO;
        ctx = make_context(model, n_ctx, n_threads, options, on_gpu);
    }
    if (ctx == nullptr) {
        llama_model_free(model);
        return 0;
    }
    auto * engine = new Engine{model, ctx, llama_model_get_vocab(model)};
    engine->options = options;
    // A sliding-window cache (Gemma) has dropped the early positions a reused head would still
    // need, so it takes the snapshot path too.
    engine->recurrent = llama_model_is_recurrent(model) || llama_model_is_hybrid(model) || llama_model_n_swa(model) > 0;

    char desc[256];
    llama_model_desc(model, desc, sizeof(desc));
    char line[1024];
    std::snprintf(line, sizeof(line),
        "model=%s\nlayers=%d\ngpu_layers=%d\nrequested_gpu_layers=%d\ngpu_coverage=%.3f\ngated=%d\nfell_back=%d\n"
        "threads=%d\nctx=%d\nbatch=%d\nubatch=%d\nkv=%s\nflash=%d\nrecurrent=%d\nprefix_cache=%d\nlazy_grammar=%d\n",
        desc, llama_model_n_layer(model) + 1, mp.n_gpu_layers, requested, coverage, gated ? 1 : 0, fell_back ? 1 : 0,
        n_threads, n_ctx, options.n_batch, options.n_ubatch, ggml_type_name(static_cast<ggml_type>(options.kv_type)), options.flash,
        engine->recurrent ? 1 : 0, options.prefix_cache ? 1 : 0, options.lazy_grammar ? 1 : 0);
    info += line;
    for (ggml_backend_dev_t dev : devices) {
        if (dev != nullptr) info += std::string("gpu=") + ggml_backend_dev_description(dev) + "\n";
    }
    {
        std::lock_guard<std::mutex> lock(log_mutex);
        info += load_log;
        load_log.clear();
    }
    engine->info = info;
    return reinterpret_cast<jlong>(engine);
}

JNIEXPORT void JNICALL
Java_io_trimio_engine_llm_local_LlamaNative_free(JNIEnv *, jobject, jlong handle) {
    auto * engine = reinterpret_cast<Engine *>(handle);
    if (engine == nullptr) return;
    if (engine->vision != nullptr) mtmd_free(engine->vision);
    llama_free(engine->ctx);
    llama_model_free(engine->model);
    delete engine;
}

JNIEXPORT jint JNICALL
Java_io_trimio_engine_llm_local_LlamaNative_contextSize(JNIEnv *, jobject, jlong handle) {
    return static_cast<jint>(llama_n_ctx(reinterpret_cast<Engine *>(handle)->ctx));
}

JNIEXPORT jint JNICALL
Java_io_trimio_engine_llm_local_LlamaNative_countTokens(JNIEnv * env, jobject, jlong handle, jbyteArray text) {
    return static_cast<jint>(tokenize(reinterpret_cast<Engine *>(handle)->vocab, from_bytes(env, text), false).size());
}

/** How the model was placed, as key=value lines (devices, layers on the GPU, cache, threads). */
JNIEXPORT jstring JNICALL
Java_io_trimio_engine_llm_local_LlamaNative_runtimeInfo(JNIEnv * env, jobject, jlong handle) {
    return env->NewStringUTF(reinterpret_cast<Engine *>(handle)->info.c_str());
}

/**
 * The last call's cost: [prompt tokens, reused tokens, generated tokens, prompt µs, generate µs,
 * grammar resamples].
 */
JNIEXPORT jlongArray JNICALL
Java_io_trimio_engine_llm_local_LlamaNative_lastCall(JNIEnv * env, jobject, jlong handle) {
    const CallStats & s = reinterpret_cast<Engine *>(handle)->last;
    const jlong values[6] = {s.prompt_tokens, s.reused_tokens, s.generated, static_cast<jlong>(s.prompt_ms * 1000),
        static_cast<jlong>(s.generate_ms * 1000), s.grammar_resamples};
    jlongArray out = env->NewLongArray(6);
    env->SetLongArrayRegion(out, 0, 6, values);
    return out;
}

/**
 * Formats a conversation with the model's own chat template. Returns null when the template is
 * not one llama.cpp knows, so Kotlin can fall back to a format from the model catalogue.
 */
JNIEXPORT jbyteArray JNICALL
Java_io_trimio_engine_llm_local_LlamaNative_applyTemplate(JNIEnv * env, jobject, jlong handle, jobjectArray roles, jobjectArray contents) {
    auto * engine = reinterpret_cast<Engine *>(handle);
    const char * tmpl = llama_model_chat_template(engine->model, nullptr);
    if (tmpl == nullptr) return nullptr;

    const jsize n = env->GetArrayLength(roles);
    std::vector<std::string> r(static_cast<size_t>(n)), c(static_cast<size_t>(n));
    std::vector<llama_chat_message> chat(static_cast<size_t>(n));
    size_t total = 0;
    for (jsize i = 0; i < n; ++i) {
        r[i] = to_string(env, static_cast<jstring>(env->GetObjectArrayElement(roles, i)));
        c[i] = from_bytes(env, static_cast<jbyteArray>(env->GetObjectArrayElement(contents, i)));
        total += c[i].size();
    }
    for (jsize i = 0; i < n; ++i) chat[i] = {r[i].c_str(), c[i].c_str()};

    std::vector<char> buf(total * 2 + 1024);
    int len = llama_chat_apply_template(tmpl, chat.data(), chat.size(), true, buf.data(), static_cast<int32_t>(buf.size()));
    if (len < 0) return nullptr;
    if (static_cast<size_t>(len) > buf.size()) {
        buf.resize(static_cast<size_t>(len));
        len = llama_chat_apply_template(tmpl, chat.data(), chat.size(), true, buf.data(), static_cast<int32_t>(buf.size()));
        if (len < 0) return nullptr;
    }
    return to_bytes(env, std::string(buf.data(), static_cast<size_t>(len)));
}

/**
 * Generates a completion for an already formatted [prompt]. Streams UTF-8 chunks to
 * `callback.onText(byte[]): boolean`; returning false stops generation.
 *
 * Returns the stop reason: 0 end of generation, 1 token limit, 2 cancelled,
 * negative on error (-1 prompt too long, -2 decode failed, -3 invalid grammar).
 */
JNIEXPORT jint JNICALL
Java_io_trimio_engine_llm_local_LlamaNative_generate(
        JNIEnv * env, jobject, jlong handle, jbyteArray jprompt, jstring jgrammar,
        jint max_tokens, jfloat temperature, jfloat top_p, jfloat min_p, jint seed, jobject callback) {
    auto * engine = reinterpret_cast<Engine *>(handle);
    engine->last = CallStats{};

    std::vector<llama_token> prompt = tokenize(engine->vocab, from_bytes(env, jprompt), true);
    const int n_ctx = static_cast<int>(llama_n_ctx(engine->ctx));
    if (prompt.empty() || static_cast<int>(prompt.size()) + 8 >= n_ctx) return -1;
    if (!decode_prompt(engine, prompt)) return -2;

    return sample(env, engine, static_cast<int>(prompt.size()), jgrammar, max_tokens, temperature, top_p, min_p, seed, callback);
}

/**
 * Generates a completion for a formatted [prompt] that holds one media marker per image. Images
 * are RGB bytes, [widths]×[heights]; the vision projector turns each into tokens in place.
 * Returns like generate(), plus -4 when no projector is loaded and -5 when an image fails.
 */
JNIEXPORT jint JNICALL
Java_io_trimio_engine_llm_local_LlamaNative_generateWithImages(
        JNIEnv * env, jobject, jlong handle, jbyteArray jprompt, jobjectArray images, jintArray widths, jintArray heights,
        jstring jgrammar, jint max_tokens, jfloat temperature, jfloat top_p, jfloat min_p, jint seed, jobject callback) {
    auto * engine = reinterpret_cast<Engine *>(handle);
    if (engine->vision == nullptr) return -4;
    engine->last = CallStats{};
    llama_memory_clear(llama_get_memory(engine->ctx), true);
    // The memory now holds image tokens: nothing of it is a text head to reuse (the snapshot is
    // a copy of its own and stays valid).
    engine->held.clear();

    const jsize n = env->GetArrayLength(images);
    std::vector<jint> w(static_cast<size_t>(n)), h(static_cast<size_t>(n));
    if (n > 0) {
        env->GetIntArrayRegion(widths, 0, n, w.data());
        env->GetIntArrayRegion(heights, 0, n, h.data());
    }
    std::vector<mtmd_bitmap *> bitmaps;
    for (jsize i = 0; i < n; ++i) {
        const std::string rgb = from_bytes(env, static_cast<jbyteArray>(env->GetObjectArrayElement(images, i)));
        if (rgb.size() != static_cast<size_t>(w[i]) * static_cast<size_t>(h[i]) * 3) {
            for (auto * b : bitmaps) mtmd_bitmap_free(b);
            return -5;
        }
        bitmaps.push_back(mtmd_bitmap_init(static_cast<uint32_t>(w[i]), static_cast<uint32_t>(h[i]), reinterpret_cast<const unsigned char *>(rgb.data())));
    }
    const std::string prompt = from_bytes(env, jprompt);
    mtmd_input_text text{prompt.c_str(), prompt.size(), true, true};
    mtmd_input_chunks * chunks = mtmd_input_chunks_init();
    const int32_t rc = mtmd_tokenize(engine->vision, chunks, &text, const_cast<const mtmd_bitmap **>(bitmaps.data()), bitmaps.size());
    for (auto * b : bitmaps) mtmd_bitmap_free(b);
    if (rc != 0) {
        mtmd_input_chunks_free(chunks);
        return -5;
    }
    const int n_ctx = static_cast<int>(llama_n_ctx(engine->ctx));
    if (static_cast<int>(mtmd_helper_get_n_pos(chunks)) + 8 >= n_ctx) {
        mtmd_input_chunks_free(chunks);
        return -1;
    }
    const auto t0 = Clock::now();
    llama_pos n_past = 0;
    const int32_t ev = mtmd_helper_eval_chunks(engine->vision, engine->ctx, chunks, 0, 0, engine->options.n_batch, true, &n_past);
    mtmd_input_chunks_free(chunks);
    engine->last.prompt_tokens = static_cast<int>(n_past);
    engine->last.prompt_ms = ms_since(t0);
    if (ev != 0) return -2;
    return sample(env, engine, static_cast<int>(n_past), jgrammar, max_tokens, temperature, top_p, min_p, seed, callback);
}

/** Loads the vision projector for this model; false when the file is not a projector for it. */
JNIEXPORT jboolean JNICALL
Java_io_trimio_engine_llm_local_LlamaNative_initVision(JNIEnv * env, jobject, jlong handle, jstring jpath, jint n_threads, jint max_image_tokens) {
    auto * engine = reinterpret_cast<Engine *>(handle);
    if (engine->vision != nullptr) return JNI_TRUE;
    mtmd_helper_log_set(quiet_log, nullptr);
    mtmd_context_params params = mtmd_context_params_default();
    params.use_gpu = false;
    params.print_timings = false;
    params.n_threads = n_threads;
    params.warmup = false;
    // Phone budget: each image costs at most this many tokens (Qwen's dynamic resolution).
    if (max_image_tokens > 0) params.image_max_tokens = max_image_tokens;
    engine->vision = mtmd_init_from_file(to_string(env, jpath).c_str(), engine->model, params);
    return engine->vision != nullptr && mtmd_support_vision(engine->vision) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jstring JNICALL
Java_io_trimio_engine_llm_local_LlamaNative_systemInfo(JNIEnv * env, jobject) {
    return env->NewStringUTF(llama_print_system_info());
}

/**
 * Opens an encoder ([EncoderMode]: 0 embed, 1 rank pooling, 2 yes/no judge) on the CPU; 0 when
 * the file does not load or is not that kind of model.
 */
JNIEXPORT jlong JNICALL
Java_io_trimio_engine_llm_local_LlamaNative_encoderInit(JNIEnv * env, jobject, jstring jpath, jint n_ctx, jint n_threads, jint n_seq, jint mode) {
    ensure_backend();
    return reinterpret_cast<jlong>(open_encoder(to_string(env, jpath), n_ctx, n_threads, n_seq, mode));
}

JNIEXPORT void JNICALL
Java_io_trimio_engine_llm_local_LlamaNative_encoderFree(JNIEnv *, jobject, jlong handle) {
    close_encoder(reinterpret_cast<Encoder *>(handle));
}

/** How the encoder was opened, as key=value lines (model, mode, pooling, dims, ctx, seqs, threads). */
JNIEXPORT jstring JNICALL
Java_io_trimio_engine_llm_local_LlamaNative_encoderInfo(JNIEnv * env, jobject, jlong handle) {
    return env->NewStringUTF(reinterpret_cast<Encoder *>(handle)->info.c_str());
}

/**
 * Pooled vectors of [texts] (embed or rank mode), n × dims floats; L2-normalised when [normalise].
 * Null when a decode fails or any value is not finite (an f16 EmbeddingGemma's NaNs).
 */
JNIEXPORT jfloatArray JNICALL
Java_io_trimio_engine_llm_local_LlamaNative_encode(JNIEnv * env, jobject, jlong handle, jobjectArray texts, jboolean normalise) {
    auto * e = reinterpret_cast<Encoder *>(handle);
    if (e->mode == JUDGE) return nullptr;
    const auto seqs = tokenize_all(env, e->vocab, texts, e->mode == EMBED, static_cast<size_t>(e->n_ctx));
    std::vector<float> out;
    if (!encode_all(e, seqs, normalise == JNI_TRUE, out)) return nullptr;
    jfloatArray result = env->NewFloatArray(static_cast<jsize>(out.size()));
    env->SetFloatArrayRegion(result, 0, static_cast<jsize>(out.size()), out.data());
    return result;
}

/**
 * P(yes) for each document continuing the shared [head] (judge mode): [head] holds the prompt up
 * to the document, each of [docs] the document and the prompt's end. Null on failure.
 */
JNIEXPORT jfloatArray JNICALL
Java_io_trimio_engine_llm_local_LlamaNative_judge(JNIEnv * env, jobject, jlong handle, jbyteArray jhead, jobjectArray docs) {
    auto * e = reinterpret_cast<Encoder *>(handle);
    if (e->mode != JUDGE) return nullptr;
    const auto head = tokenize(e->vocab, from_bytes(env, jhead), false);
    const size_t limit = static_cast<size_t>(std::max(16, e->n_ctx / 2 - static_cast<int>(head.size())));
    const auto seqs = tokenize_all(env, e->vocab, docs, false, limit);
    std::vector<float> out;
    if (!judge_all(e, head, seqs, out)) return nullptr;
    jfloatArray result = env->NewFloatArray(static_cast<jsize>(out.size()));
    env->SetFloatArrayRegion(result, 0, static_cast<jsize>(out.size()), out.data());
    return result;
}

/** Token count of [text] for this encoder (with its special tokens when [special]). */
JNIEXPORT jint JNICALL
Java_io_trimio_engine_llm_local_LlamaNative_encoderTokens(JNIEnv * env, jobject, jlong handle, jbyteArray text, jboolean special) {
    return static_cast<jint>(tokenize(reinterpret_cast<Encoder *>(handle)->vocab, from_bytes(env, text), special == JNI_TRUE).size());
}

}  // extern "C"
