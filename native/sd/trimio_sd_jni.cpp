// JNI bridge to stable-diffusion.cpp (built on the app's own ggml): one-step text-to-image for the
// illustration layer's generator add-on (SDXS-512). Kotlin side: io.trimio.engine.illustration.generate.SdNative.
#include <jni.h>

#include <cstdlib>
#include <cstring>
#include <string>

#include "stable-diffusion.h"

#ifdef __ANDROID__
#include <android/log.h>
#else
#include <cstdio>
#endif

namespace {

// The engine's own lines (what it loads, each stage, any failure) go to logcat / stderr beside the app's report.
void log_line(enum sd_log_level_t level, const char * text, void *) {
    if (level < SD_LOG_INFO || text == nullptr) return;
#ifdef __ANDROID__
    __android_log_write(level >= SD_LOG_ERROR ? ANDROID_LOG_ERROR : level >= SD_LOG_WARN ? ANDROID_LOG_WARN : ANDROID_LOG_INFO, "trimio-sd", text);
#else
    std::fputs(text, stderr);
#endif
}

std::string str(JNIEnv * env, jstring s) {
    if (s == nullptr) return {};
    const char * chars = env->GetStringUTFChars(s, nullptr);
    std::string out(chars ? chars : "");
    if (chars) env->ReleaseStringUTFChars(s, chars);
    return out;
}

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_io_trimio_engine_illustration_generate_SdNative_init(JNIEnv * env, jobject, jstring jpath, jint threads) {
    const std::string path = str(env, jpath);
    sd_set_log_callback(log_line, nullptr);
    sd_ctx_params_t p;
    sd_ctx_params_init(&p);
    p.model_path = path.c_str();
    p.n_threads = threads > 0 ? threads : sd_get_num_physical_cores();
    p.enable_mmap = true;
    sd_ctx_t * ctx = new_sd_ctx(&p);
    return reinterpret_cast<jlong>(ctx);
}

// RGB bytes (width × height × 3), or null when generation failed.
JNIEXPORT jbyteArray JNICALL
Java_io_trimio_engine_illustration_generate_SdNative_generate(
    JNIEnv * env, jobject, jlong handle, jstring jprompt, jstring jnegative, jint width, jint height, jlong seed, jint steps, jfloat cfg) {
    auto * ctx = reinterpret_cast<sd_ctx_t *>(handle);
    if (ctx == nullptr) return nullptr;
    const std::string prompt = str(env, jprompt);
    const std::string negative = str(env, jnegative);
    sd_img_gen_params_t g;
    sd_img_gen_params_init(&g);
    g.prompt = prompt.c_str();
    g.negative_prompt = negative.c_str();
    g.width = width;
    g.height = height;
    g.seed = seed;
    g.batch_count = 1;
    g.sample_params.sample_steps = steps;
    g.sample_params.guidance.txt_cfg = cfg;
    sd_image_t * images = nullptr;
    int count = 0;
    if (!generate_image(ctx, &g, &images, &count) || images == nullptr || count < 1) {
        if (images != nullptr) free(images);
        return nullptr;
    }
    const sd_image_t & img = images[0];
    jbyteArray out = nullptr;
    if (img.data != nullptr && img.channel >= 3) {
        const jsize pixels = static_cast<jsize>(img.width * img.height);
        out = env->NewByteArray(pixels * 3);
        if (img.channel == 3) {
            env->SetByteArrayRegion(out, 0, pixels * 3, reinterpret_cast<const jbyte *>(img.data));
        } else {
            std::string rgb(static_cast<size_t>(pixels) * 3, '\0');
            for (jsize i = 0; i < pixels; i++) std::memcpy(&rgb[i * 3], img.data + static_cast<size_t>(i) * img.channel, 3);
            env->SetByteArrayRegion(out, 0, pixels * 3, reinterpret_cast<const jbyte *>(rgb.data()));
        }
    }
    for (int i = 0; i < count; i++) free(images[i].data);
    free(images);
    return out;
}

JNIEXPORT void JNICALL
Java_io_trimio_engine_illustration_generate_SdNative_free(JNIEnv *, jobject, jlong handle) {
    if (handle != 0) free_sd_ctx(reinterpret_cast<sd_ctx_t *>(handle));
}

}  // extern "C"
