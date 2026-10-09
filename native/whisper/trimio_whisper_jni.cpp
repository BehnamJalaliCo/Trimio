// JNI bridge between io.trimio.engine.asr.whisper.WhisperNative and whisper.cpp.
//
// Tokens are returned as raw bytes (not Java strings): whisper's byte-level BPE can split a
// multi-byte Persian letter across tokens, so the Kotlin side concatenates bytes per word before
// decoding UTF-8. Segments are delivered through a callback as soon as the decoder emits them.

#include <jni.h>
#include <string>
#include <vector>

#include "whisper.h"

namespace {

struct CallbackContext {
    JNIEnv * env;
    jobject callback;
    jmethodID onProgress;
    jmethodID onSegment;
    jmethodID isCancelled;
    whisper_token eot;
};

void deliver_segments(whisper_context * ctx, whisper_state * state, int n_new, void * user) {
    auto * cb = static_cast<CallbackContext *>(user);
    JNIEnv * env = cb->env;
    const int n_segments = whisper_full_n_segments_from_state(state);

    for (int s = n_segments - n_new; s < n_segments; ++s) {
        const int n_tokens = whisper_full_n_tokens_from_state(state, s);
        std::vector<int> kept;
        for (int t = 0; t < n_tokens; ++t) {
            // Special tokens (timestamps, language, task) all sit at or above end-of-text.
            if (whisper_full_get_token_id_from_state(state, s, t) < cb->eot) kept.push_back(t);
        }

        jclass byteArrayClass = env->FindClass("[B");
        jobjectArray texts = env->NewObjectArray(static_cast<jsize>(kept.size()), byteArrayClass, nullptr);
        jlongArray t0 = env->NewLongArray(static_cast<jsize>(kept.size()));
        jlongArray t1 = env->NewLongArray(static_cast<jsize>(kept.size()));
        jfloatArray p = env->NewFloatArray(static_cast<jsize>(kept.size()));
        std::vector<jlong> t0v, t1v;
        std::vector<jfloat> pv;

        for (size_t i = 0; i < kept.size(); ++i) {
            const int t = kept[i];
            const char * text = whisper_full_get_token_text_from_state(ctx, state, s, t);
            const auto len = static_cast<jsize>(std::char_traits<char>::length(text));
            jbyteArray bytes = env->NewByteArray(len);
            env->SetByteArrayRegion(bytes, 0, len, reinterpret_cast<const jbyte *>(text));
            env->SetObjectArrayElement(texts, static_cast<jsize>(i), bytes);
            env->DeleteLocalRef(bytes);

            const whisper_token_data data = whisper_full_get_token_data_from_state(state, s, t);
            // whisper times are in centiseconds.
            t0v.push_back(static_cast<jlong>(data.t0) * 10);
            t1v.push_back(static_cast<jlong>(data.t1) * 10);
            pv.push_back(data.p);
        }
        if (!kept.empty()) {
            env->SetLongArrayRegion(t0, 0, static_cast<jsize>(kept.size()), t0v.data());
            env->SetLongArrayRegion(t1, 0, static_cast<jsize>(kept.size()), t1v.data());
            env->SetFloatArrayRegion(p, 0, static_cast<jsize>(kept.size()), pv.data());
        }
        env->CallVoidMethod(cb->callback, cb->onSegment, texts, t0, t1, p);

        env->DeleteLocalRef(texts);
        env->DeleteLocalRef(t0);
        env->DeleteLocalRef(t1);
        env->DeleteLocalRef(p);
        env->DeleteLocalRef(byteArrayClass);
        if (env->ExceptionCheck()) return;
    }
}

void deliver_progress(whisper_context *, whisper_state *, int progress, void * user) {
    auto * cb = static_cast<CallbackContext *>(user);
    cb->env->CallVoidMethod(cb->callback, cb->onProgress, static_cast<jint>(progress));
}

bool should_abort(void * user) {
    auto * cb = static_cast<CallbackContext *>(user);
    if (cb->env->ExceptionCheck()) return true;
    return cb->env->CallBooleanMethod(cb->callback, cb->isCancelled) == JNI_TRUE;
}

std::string to_string(JNIEnv * env, jstring s) {
    if (s == nullptr) return {};
    const char * chars = env->GetStringUTFChars(s, nullptr);
    std::string out(chars);
    env->ReleaseStringUTFChars(s, chars);
    return out;
}

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_io_trimio_engine_asr_whisper_WhisperNative_init(JNIEnv * env, jobject, jstring modelPath, jboolean useGpu, jboolean flashAttention) {
    whisper_context_params params = whisper_context_default_params();
    params.use_gpu = useGpu == JNI_TRUE;
    params.flash_attn = flashAttention == JNI_TRUE;
    const std::string path = to_string(env, modelPath);
    whisper_context * ctx = whisper_init_from_file_with_params(path.c_str(), params);
    return reinterpret_cast<jlong>(ctx);
}

JNIEXPORT void JNICALL
Java_io_trimio_engine_asr_whisper_WhisperNative_free(JNIEnv *, jobject, jlong handle) {
    whisper_free(reinterpret_cast<whisper_context *>(handle));
}

JNIEXPORT jboolean JNICALL
Java_io_trimio_engine_asr_whisper_WhisperNative_isMultilingual(JNIEnv *, jobject, jlong handle) {
    return whisper_is_multilingual(reinterpret_cast<whisper_context *>(handle)) ? JNI_TRUE : JNI_FALSE;
}

/** Returns 0 on success, the whisper error code otherwise, or -100 when aborted. */
JNIEXPORT jint JNICALL
Java_io_trimio_engine_asr_whisper_WhisperNative_transcribe(
    JNIEnv * env, jobject, jlong handle, jfloatArray pcm, jstring language, jstring initialPrompt,
    jint threads, jobject callback) {

    auto * ctx = reinterpret_cast<whisper_context *>(handle);
    jclass cls = env->GetObjectClass(callback);
    CallbackContext cb{
        env,
        callback,
        env->GetMethodID(cls, "onProgress", "(I)V"),
        env->GetMethodID(cls, "onSegment", "([[B[J[J[F)V"),
        env->GetMethodID(cls, "isCancelled", "()Z"),
        whisper_token_eot(ctx),
    };

    const std::string lang = to_string(env, language);
    const std::string prompt = to_string(env, initialPrompt);

    whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_BEAM_SEARCH);
    params.beam_search.beam_size = 3;
    params.n_threads = threads;
    params.language = lang.empty() ? "auto" : lang.c_str();
    params.detect_language = false;
    params.translate = false;
    params.no_context = true;
    params.token_timestamps = true;
    params.print_progress = false;
    params.print_realtime = false;
    params.print_special = false;
    params.print_timestamps = false;
    params.suppress_blank = true;
    params.initial_prompt = prompt.empty() ? nullptr : prompt.c_str();
    params.new_segment_callback = deliver_segments;
    params.new_segment_callback_user_data = &cb;
    params.progress_callback = deliver_progress;
    params.progress_callback_user_data = &cb;
    params.abort_callback = should_abort;
    params.abort_callback_user_data = &cb;

    jsize n = env->GetArrayLength(pcm);
    jfloat * samples = env->GetFloatArrayElements(pcm, nullptr);
    const int rc = whisper_full(ctx, params, samples, n);
    env->ReleaseFloatArrayElements(pcm, samples, JNI_ABORT);

    if (env->ExceptionCheck()) return -101;
    if (rc != 0 && should_abort(&cb)) return -100;
    return rc;
}

JNIEXPORT jstring JNICALL
Java_io_trimio_engine_asr_whisper_WhisperNative_detectedLanguage(JNIEnv * env, jobject, jlong handle) {
    const int id = whisper_full_lang_id(reinterpret_cast<whisper_context *>(handle));
    return env->NewStringUTF(id >= 0 ? whisper_lang_str(id) : "");
}

JNIEXPORT jstring JNICALL
Java_io_trimio_engine_asr_whisper_WhisperNative_systemInfo(JNIEnv * env, jobject) {
    return env->NewStringUTF(whisper_print_system_info());
}

}  // extern "C"
