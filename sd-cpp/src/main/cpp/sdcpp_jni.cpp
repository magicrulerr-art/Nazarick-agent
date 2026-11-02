// JNI shim between SdCppJni.kt and sd.cpp. Deliberately small: model loading and one
// generation call, because a diffusion context is heavy enough that this module keeps at most
// one loaded at a time and lets the Kotlin side own that policy.
#include <jni.h>

#include <android/log.h>
#include <atomic>
#include <string>
#include <vector>

#include "stable-diffusion.h"

#define TAG "SdCpp"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace {

JavaVM *g_vm = nullptr;
jclass g_raster_class = nullptr;
jmethodID g_raster_ctor = nullptr;

// sd.cpp's progress callback is process-global, not per-context. That is only sound because
// SdCppEngine serialises generation behind a mutex, so at most one call is in flight and the
// sink it installed is the sink that fires. Widen this if that ever changes.
std::atomic<jobject> g_progress_sink{nullptr};

void sd_log_bridge(enum sd_log_level_t level, const char *text, void *) {
    if (text == nullptr) {
        return;
    }
    const int prio = (level == SD_LOG_DEBUG || level == SD_LOG_VERBOSE) ? ANDROID_LOG_DEBUG
                     : (level == SD_LOG_INFO)                           ? ANDROID_LOG_INFO
                     : (level == SD_LOG_WARN)                           ? ANDROID_LOG_WARN
                                                                        : ANDROID_LOG_ERROR;
    __android_log_write(prio, TAG, text);
}

void sd_progress_bridge(int step, int steps, float time, void *) {
    jobject sink = g_progress_sink.load(std::memory_order_acquire);
    if (sink == nullptr || g_vm == nullptr) {
        return;
    }
    JNIEnv *env = nullptr;
    if (g_vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) != JNI_OK) {
        return;  // a thread sd.cpp created that never attached; progress is cosmetic, drop it
    }
    env->CallVoidMethod(sink, env->GetMethodID(env->GetObjectClass(sink), "onStep", "(IIF)V"));
    if (env->ExceptionCheck()) {
        env->ExceptionClear();  // a throwing sink must not unwind through C++ frames
    }
}

jstring to_jstring(JNIEnv *env, const char *text) {
    return env->NewStringUTF(text == nullptr ? "" : text);
}

}  // namespace

extern "C" {

JNIEXPORT jint JNI_OnLoad(JavaVM *vm, void *) {
    g_vm = vm;
    JNIEnv *env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) != JNI_OK) {
        return JNI_ERR;
    }
    jclass raster = env->FindClass("me/rerere/sdcpp/SdRaster");
    if (raster == nullptr) {
        return JNI_ERR;
    }
    g_raster_class = reinterpret_cast<jclass>(env->NewGlobalRef(raster));
    g_raster_ctor = env->GetMethodID(g_raster_class, "<init>", "(III[B)V");
    if (g_raster_ctor == nullptr) {
        return JNI_ERR;
    }
    sd_set_log_callback(sd_log_bridge, nullptr);
    return JNI_VERSION_1_6;
}

JNIEXPORT jlong JNICALL Java_me_rerere_sdcpp_SdCppJni_nativeLoadModel(JNIEnv *env, jobject, jstring model_path,
                                                                      jint n_threads, jboolean enable_mmap,
                                                                      jobject progress_sink) {
    const char *path = env->GetStringUTFChars(model_path, nullptr);

    // Out-parameter C init, not a factory: this header declares `void sd_ctx_params_init(sd_ctx_params_t*)`.
    // The braces zero the whole struct -- including nested structs this initializer does not touch --
    // before the library fills in its defaults, so nothing downstream reads uninitialised memory.
    sd_ctx_params_t params{};
    sd_ctx_params_init(&params);
    params.model_path = path;
    params.n_threads = n_threads > 0 ? n_threads : -1;  // -1 = let ggml read the machine
    params.enable_mmap = enable_mmap == JNI_TRUE;

    sd_ctx_t *ctx = new_sd_ctx(&params);
    env->ReleaseStringUTFChars(model_path, path);

    if (ctx == nullptr) {
        LOGE("new_sd_ctx failed for %s", path);
        return 0;
    }
    if (progress_sink != nullptr) {
        if (jobject previous = g_progress_sink.exchange(env->NewGlobalRef(progress_sink))) {
            env->DeleteGlobalRef(previous);
        }
        g_progress_sink.store(env->NewGlobalRef(progress_sink), std::memory_order_release);
    }
    return reinterpret_cast<jlong>(ctx);
}

JNIEXPORT void JNICALL Java_me_rerere_sdcpp_SdCppJni_nativeFreeModel(JNIEnv *env, jobject, jlong handle) {
    if (handle == 0) {
        return;
    }
    // The sink outlives nothing useful once the context is gone; drop it before freeing so a
    // late callback cannot reach a Kotlin object the caller has already released.
    if (jobject sink = g_progress_sink.exchange(nullptr)) {
        env->DeleteGlobalRef(sink);
    }
    free_sd_ctx(reinterpret_cast<sd_ctx_t *>(handle));
}

JNIEXPORT void JNICALL Java_me_rerere_sdcpp_SdCppJni_nativeCancel(JNIEnv *, jobject, jlong handle) {
    if (handle == 0) {
        return;
    }
    // NEW_LATENTS rather than ALL: a cancelled batch still returns the images it finished,
    // which is what a user who changed their mind mid-batch wants to keep.
    sd_cancel_generation(reinterpret_cast<sd_ctx_t *>(handle), SD_CANCEL_NEW_LATENTS);
}

JNIEXPORT jobject JNICALL Java_me_rerere_sdcpp_SdCppJni_nativeGenerate(
    JNIEnv *env, jobject, jlong handle, jstring prompt, jstring negative_prompt, jint width, jint height,
    jint steps, jfloat cfg, jlong seed, jint batch, jbyteArray init_rgba, jint init_w, jint init_h,
    jfloat strength) {
    auto *ctx = reinterpret_cast<sd_ctx_t *>(handle);
    if (ctx == nullptr) {
        LOGE("generate on a null context");
        return nullptr;
    }

    const char *prompt_chars = env->GetStringUTFChars(prompt, nullptr);
    const char *negative_chars = env->GetStringUTFChars(negative_prompt, nullptr);

    sd_img_gen_params_t params{};
    sd_img_gen_params_init(&params);
    params.prompt = prompt_chars;
    params.negative_prompt = negative_chars;
    params.width = width;
    params.height = height;
    params.batch_count = batch > 0 ? batch : 1;
    params.seed = static_cast<int64_t>(seed);
    params.sample_params.sample_steps = steps > 0 ? steps : 20;
    // cfg lives in the guidance struct: txt for text conditioning, img for image conditioning.
    // One value for both is what sd-cli's --cfg-scale does, and it is what a caller here means.
    params.sample_params.guidance.txt_cfg = cfg;
    params.sample_params.guidance.img_cfg = cfg;

    jbyte *init_pixels = nullptr;
    if (init_rgba != nullptr && init_w > 0 && init_h > 0) {
        init_pixels = env->GetByteArrayElements(init_rgba, nullptr);
        params.init_image.width = static_cast<uint32_t>(init_w);
        params.init_image.height = static_cast<uint32_t>(init_h);
        params.init_image.channel = 4;
        params.init_image.data = reinterpret_cast<uint8_t *>(init_pixels);
        params.strength = strength;
    }

    sd_image_generation_result_t *result = nullptr;
    const bool ok = generate_image(ctx, &params, &result);

    jobject raster = nullptr;
    if (ok && result != nullptr && result->count > 0) {
        const sd_image_t &image = result->items[0].image;
        const jsize bytes = static_cast<jsize>(image.width * image.height * image.channel);
        jbyteArray pixels = env->NewByteArray(bytes);
        if (pixels != nullptr) {
            env->SetByteArrayRegion(pixels, 0, bytes, reinterpret_cast<const jbyte *>(image.data));
            raster = env->NewObject(g_raster_class, g_raster_ctor, static_cast<jint>(image.width),
                                    static_cast<jint>(image.height), static_cast<jint>(image.channel), pixels);
            env->DeleteLocalRef(pixels);
        }
    } else {
        LOGE("generate_image failed (prompt=%.60s, %dx%d, %d steps)", prompt_chars, width, height, steps);
    }

    // Results own their pixels; copy first, then free.
    free_sd_image_generation_result(result);
    if (init_pixels != nullptr) {
        env->ReleaseByteArrayElements(init_rgba, init_pixels, JNI_ABORT);  // read-only on our side
    }
    env->ReleaseStringUTFChars(prompt, prompt_chars);
    env->ReleaseStringUTFChars(negative_prompt, negative_chars);
    (void)to_jstring;  // kept for the metadata path when generation_info is surfaced
    return raster;
}

}  // extern "C"
