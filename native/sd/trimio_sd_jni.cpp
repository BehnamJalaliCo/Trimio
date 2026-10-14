// JNI bridge to stable-diffusion.cpp (built alone, on its own pinned ggml): the illustration layer's generator add-ons,
// SDXS-512 (one file, one step) and FLUX.2 klein 4B (diffusion model, Qwen3 text encoder and VAE; four steps, an init
// picture and style references). Kotlin side: io.trimio.engine.illustration.generate.SdNative.
#include <jni.h>

#include <cstdlib>
#include <cstring>
#include <string>
#include <vector>

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

// An RGB picture handed in from Kotlin (width × height × 3 bytes), kept alive for one generation.
struct Picture {
    std::string bytes;
    sd_image_t image{};

    bool read(JNIEnv * env, jbyteArray rgb, int width, int height) {
        if (rgb == nullptr || env->GetArrayLength(rgb) < width * height * 3) return false;
        bytes.resize(static_cast<size_t>(width) * height * 3);
        env->GetByteArrayRegion(rgb, 0, static_cast<jsize>(bytes.size()), reinterpret_cast<jbyte *>(&bytes[0]));
        image = {static_cast<uint32_t>(width), static_cast<uint32_t>(height), 3, reinterpret_cast<uint8_t *>(&bytes[0])};
        return true;
    }
};

sd_ctx_t * load(sd_ctx_params_t & p, jint threads) {
    sd_set_log_callback(log_line, nullptr);
    p.n_threads = threads > 0 ? threads : sd_get_num_physical_cores();
    p.enable_mmap = true;
    return new_sd_ctx(&p);
}

// The first picture of a generation as RGB bytes (null when it failed); every picture is freed.
jbyteArray first_rgb(JNIEnv * env, sd_image_t * images, int count) {
    jbyteArray out = nullptr;
    if (images != nullptr && count > 0 && images[0].data != nullptr && images[0].channel >= 3) {
        const sd_image_t & img = images[0];
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
    if (images != nullptr) {
        for (int i = 0; i < count; i++) free(images[i].data);
        free(images);
    }
    return out;
}

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_io_trimio_engine_illustration_generate_SdNative_init(JNIEnv * env, jobject, jstring jpath, jint threads) {
    const std::string path = str(env, jpath);
    sd_ctx_params_t p;
    sd_ctx_params_init(&p);
    p.model_path = path.c_str();
    return reinterpret_cast<jlong>(load(p, threads));
}

// A model in parts (FLUX.2 klein): the diffusion model, its LLM text encoder (Qwen3) and its VAE.
JNIEXPORT jlong JNICALL
Java_io_trimio_engine_illustration_generate_SdNative_initParts(JNIEnv * env, jobject, jstring jdiffusion, jstring jllm, jstring jvae, jint threads) {
    const std::string diffusion = str(env, jdiffusion);
    const std::string llm = str(env, jllm);
    const std::string vae = str(env, jvae);
    sd_ctx_params_t p;
    sd_ctx_params_init(&p);
    p.diffusion_model_path = diffusion.c_str();
    p.llm_path = llm.c_str();
    if (!vae.empty()) p.vae_path = vae.c_str();
    return reinterpret_cast<jlong>(load(p, threads));
}

// RGB bytes (width × height × 3), or null when generation failed. [init] (RGB, same size; null: none) makes it
// image-to-image at [strength]; [refs] (RGB pictures of the same size) are style references for models that edit.
JNIEXPORT jbyteArray JNICALL
Java_io_trimio_engine_illustration_generate_SdNative_generate(
    JNIEnv * env, jobject, jlong handle, jstring jprompt, jstring jnegative, jint width, jint height, jlong seed, jint steps, jfloat cfg,
    jbyteArray jinit, jfloat strength, jobjectArray jrefs) {
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
    Picture init;
    if (init.read(env, jinit, width, height)) {
        g.init_image = init.image;
        g.strength = strength;
    }
    std::vector<Picture> refs(jrefs == nullptr ? 0 : static_cast<size_t>(env->GetArrayLength(jrefs)));
    std::vector<sd_image_t> ref_images;
    for (size_t i = 0; i < refs.size(); i++) {
        auto bytes = static_cast<jbyteArray>(env->GetObjectArrayElement(jrefs, static_cast<jsize>(i)));
        if (refs[i].read(env, bytes, width, height)) ref_images.push_back(refs[i].image);
    }
    if (!ref_images.empty()) {
        g.ref_images = ref_images.data();
        g.ref_images_count = static_cast<int>(ref_images.size());
    }
    sd_image_t * images = nullptr;
    int count = 0;
    if (!generate_image(ctx, &g, &images, &count)) return first_rgb(env, images, 0);
    return first_rgb(env, images, count);
}

JNIEXPORT void JNICALL
Java_io_trimio_engine_illustration_generate_SdNative_free(JNIEnv *, jobject, jlong handle) {
    if (handle != 0) free_sd_ctx(reinterpret_cast<sd_ctx_t *>(handle));
}

}  // extern "C"
