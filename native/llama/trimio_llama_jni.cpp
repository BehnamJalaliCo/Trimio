// JNI bridge between io.trimio.engine.llm.local.LlamaNative and llama.cpp.
//
// One handle owns a model and a context. Generation is single-sequence: the KV cache is cleared
// for every request, the prompt is decoded in n_batch chunks, then tokens are sampled one at a time
// through a chain of (optional GBNF grammar) -> temperature/top-p/min-p -> seeded dist.
//
// Output is streamed as UTF-8 *bytes*: a byte-level BPE token can end in the middle of a Persian
// letter, so only complete UTF-8 sequences are handed to Kotlin and the tail waits for the next token.

#include <jni.h>

#include <algorithm>
#include <string>
#include <vector>

#include "llama.h"
#include "mtmd.h"
#include "mtmd-helper.h"

namespace {

struct Engine {
    llama_model * model = nullptr;
    llama_context * ctx = nullptr;
    const llama_vocab * vocab = nullptr;
    // The model's eyes (vision projector), when loaded.
    mtmd_context * vision = nullptr;
};

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

void quiet_log(ggml_log_level level, const char * text, void *) {
    // Keep logcat clean in release; errors still surface through return codes.
    (void) level;
    (void) text;
}


/** The sampling loop shared by text and image prompts; [n_past] tokens are already decoded. */
jint sample(JNIEnv * env, Engine * engine, int n_past, jstring jgrammar, jint max_tokens, jfloat temperature, jfloat top_p, jfloat min_p, jint seed, jobject callback) {
    jclass cls = env->GetObjectClass(callback);
    jmethodID on_text = env->GetMethodID(cls, "onText", "([B)Z");
    env->DeleteLocalRef(cls);
    const int n_ctx = static_cast<int>(llama_n_ctx(engine->ctx));
    llama_sampler * chain = llama_sampler_chain_init(llama_sampler_chain_default_params());
    const std::string grammar = to_string(env, jgrammar);
    if (!grammar.empty()) {
        llama_sampler * g = llama_sampler_init_grammar(engine->vocab, grammar.c_str(), "root");
        if (g == nullptr) {
            llama_sampler_free(chain);
            return -3;
        }
        llama_sampler_chain_add(chain, g);
    }
    if (temperature <= 0.0f) {
        llama_sampler_chain_add(chain, llama_sampler_init_greedy());
    } else {
        llama_sampler_chain_add(chain, llama_sampler_init_top_p(top_p, 1));
        llama_sampler_chain_add(chain, llama_sampler_init_min_p(min_p, 1));
        llama_sampler_chain_add(chain, llama_sampler_init_temp(temperature));
        llama_sampler_chain_add(chain, llama_sampler_init_dist(static_cast<uint32_t>(seed)));
    }

    int status = 1;
    std::string pending;
    for (int i = 0; i < max_tokens; ++i) {
        llama_token token = llama_sampler_sample(chain, engine->ctx, -1);
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
    }
    if (!pending.empty() && status >= 0 && status != 2) {
        jbyteArray chunk = to_bytes(env, pending);
        env->CallBooleanMethod(callback, on_text, chunk);
        env->DeleteLocalRef(chunk);
    }
    llama_sampler_free(chain);
    return status;
}

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_io_trimio_engine_llm_local_LlamaNative_init(JNIEnv * env, jobject, jstring jpath, jint n_ctx, jint n_threads, jint gpu_layers) {
    static bool backend_ready = false;
    if (!backend_ready) {
        llama_log_set(quiet_log, nullptr);
        llama_backend_init();
        backend_ready = true;
    }
    const std::string path = to_string(env, jpath);

    llama_model_params mp = llama_model_default_params();
    mp.n_gpu_layers = gpu_layers;
    llama_model * model = llama_model_load_from_file(path.c_str(), mp);
    if (model == nullptr) return 0;

    llama_context_params cp = llama_context_default_params();
    cp.n_ctx = static_cast<uint32_t>(n_ctx);
    cp.n_batch = 512;
    cp.n_ubatch = 512;
    cp.n_threads = n_threads;
    cp.n_threads_batch = n_threads;
    cp.no_perf = true;
    llama_context * ctx = llama_init_from_model(model, cp);
    if (ctx == nullptr) {
        llama_model_free(model);
        return 0;
    }
    auto * engine = new Engine{model, ctx, llama_model_get_vocab(model)};
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
    llama_memory_clear(llama_get_memory(engine->ctx), true);

    std::vector<llama_token> prompt = tokenize(engine->vocab, from_bytes(env, jprompt), true);
    const int n_ctx = static_cast<int>(llama_n_ctx(engine->ctx));
    if (prompt.empty() || static_cast<int>(prompt.size()) + 8 >= n_ctx) return -1;

    for (size_t i = 0; i < prompt.size(); i += 512) {
        const auto n = static_cast<int32_t>(std::min<size_t>(512, prompt.size() - i));
        if (llama_decode(engine->ctx, llama_batch_get_one(prompt.data() + i, n)) != 0) return -2;
    }

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
    llama_memory_clear(llama_get_memory(engine->ctx), true);

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
    llama_pos n_past = 0;
    const int32_t ev = mtmd_helper_eval_chunks(engine->vision, engine->ctx, chunks, 0, 0, 512, true, &n_past);
    mtmd_input_chunks_free(chunks);
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

}  // extern "C"
