// JNI bridge for stable-diffusion.cpp, used only inside the isolated ":sd" process.
//
// load() creates a context for one model bundle (single checkpoint, or diffusion model + text
// encoder(s) + VAE). generate() runs txt2img / img2img / inpaint with explicit LoRA files and
// reports progress, cheap latent previews and finished RGB images through a Kotlin sink.

#include <android/log.h>
#include <jni.h>

#include <random>
#include <string>
#include <vector>

#include "stable-diffusion.h"

#define TAG "autobot-sd"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace {

struct Sink {
    JNIEnv * env;
    jobject obj;
    jmethodID on_progress;
    jmethodID on_preview;
    jmethodID on_image;
};

thread_local Sink * g_sink = nullptr;

void log_cb(enum sd_log_level_t level, const char * text, void *) {
    const int prio = level == SD_LOG_ERROR ? ANDROID_LOG_ERROR : level == SD_LOG_WARN ? ANDROID_LOG_WARN : ANDROID_LOG_DEBUG;
    __android_log_write(prio, TAG, text);
}

jbyteArray rgb_bytes(JNIEnv * env, const sd_image_t & img) {
    const jsize n = (jsize) (img.width * img.height * img.channel);
    jbyteArray arr = env->NewByteArray(n);
    env->SetByteArrayRegion(arr, 0, n, reinterpret_cast<const jbyte *>(img.data));
    return arr;
}

void progress_cb(int step, int steps, float, void *) {
    if (!g_sink) return;
    g_sink->env->CallVoidMethod(g_sink->obj, g_sink->on_progress, (jint) step, (jint) steps);
}

void preview_cb(int, int frame_count, sd_image_t * frames, bool is_noisy, void *) {
    if (!g_sink || frame_count < 1 || is_noisy || !frames || !frames[0].data) return;
    jbyteArray arr = rgb_bytes(g_sink->env, frames[0]);
    g_sink->env->CallVoidMethod(g_sink->obj, g_sink->on_preview, arr, (jint) frames[0].width, (jint) frames[0].height,
                                (jint) frames[0].channel);
    g_sink->env->DeleteLocalRef(arr);
}

std::string jstr(JNIEnv * env, jstring s) {
    if (!s) return {};
    const char * c = env->GetStringUTFChars(s, nullptr);
    std::string out(c);
    env->ReleaseStringUTFChars(s, c);
    return out;
}

const char * or_null(const std::string & s) { return s.empty() ? nullptr : s.c_str(); }

}  // namespace

extern "C" JNIEXPORT void JNICALL
Java_dev_wckdboy_autobot_engine_diffusion_NativeSd_init(JNIEnv *, jobject) {
    sd_set_log_callback(log_cb, nullptr);
    sd_set_progress_callback(progress_cb, nullptr);
}

extern "C" JNIEXPORT jstring JNICALL
Java_dev_wckdboy_autobot_engine_diffusion_NativeSd_systemInfo(JNIEnv * env, jobject) {
    return env->NewStringUTF(sd_get_system_info());
}

extern "C" JNIEXPORT jlong JNICALL
Java_dev_wckdboy_autobot_engine_diffusion_NativeSd_load(
        JNIEnv * env, jobject, jstring model, jstring diffusion_model, jstring llm, jstring clip_l, jstring clip_g,
        jstring t5xxl, jstring vae, jstring taesd, jint n_threads, jboolean flash_attn) {
    // Strings must outlive new_sd_ctx(): keep them in locals.
    const std::string s_model = jstr(env, model), s_diff = jstr(env, diffusion_model), s_llm = jstr(env, llm),
                      s_clip_l = jstr(env, clip_l), s_clip_g = jstr(env, clip_g), s_t5 = jstr(env, t5xxl),
                      s_vae = jstr(env, vae), s_taesd = jstr(env, taesd);
    sd_ctx_params_t p;
    sd_ctx_params_init(&p);
    p.model_path = or_null(s_model);
    p.diffusion_model_path = or_null(s_diff);
    p.llm_path = or_null(s_llm);
    p.clip_l_path = or_null(s_clip_l);
    p.clip_g_path = or_null(s_clip_g);
    p.t5xxl_path = or_null(s_t5);
    p.vae_path = or_null(s_vae);
    p.taesd_path = or_null(s_taesd);
    p.n_threads = n_threads;
    p.enable_mmap = true;
    p.flash_attn = flash_attn;
    p.diffusion_flash_attn = flash_attn;
    p.lora_apply_mode = LORA_APPLY_AT_RUNTIME;
    sd_ctx_t * ctx = new_sd_ctx(&p);
    if (!ctx) LOGE("new_sd_ctx failed");
    return reinterpret_cast<jlong>(ctx);
}

extern "C" JNIEXPORT jstring JNICALL
Java_dev_wckdboy_autobot_engine_diffusion_NativeSd_modelVersion(JNIEnv * env, jobject, jlong handle) {
    return env->NewStringUTF(sd_get_model_version_name(reinterpret_cast<sd_ctx_t *>(handle)));
}

extern "C" JNIEXPORT void JNICALL
Java_dev_wckdboy_autobot_engine_diffusion_NativeSd_cancel(JNIEnv *, jobject, jlong handle) {
    sd_cancel_generation(reinterpret_cast<sd_ctx_t *>(handle), SD_CANCEL_ALL);
}

extern "C" JNIEXPORT void JNICALL
Java_dev_wckdboy_autobot_engine_diffusion_NativeSd_free(JNIEnv *, jobject, jlong handle) {
    free_sd_ctx(reinterpret_cast<sd_ctx_t *>(handle));
}

/**
 * Generates `batch` images. `init_rgb` (width*height*3) enables img2img; `mask_gray`
 * (width*height, 255 = repaint) additionally enables inpainting. Images are delivered through
 * sink.onImage; the return value is the base seed used, or -1 on failure.
 */
extern "C" JNIEXPORT jlong JNICALL
Java_dev_wckdboy_autobot_engine_diffusion_NativeSd_generate(
        JNIEnv * env, jobject, jlong handle, jstring prompt, jstring negative, jint width, jint height, jint steps,
        jfloat cfg, jstring sampler, jstring scheduler, jlong seed, jint batch, jfloat strength, jint clip_skip,
        jobjectArray lora_paths, jfloatArray lora_weights, jbyteArray init_rgb, jbyteArray mask_gray, jobject jsink) {
    auto * ctx = reinterpret_cast<sd_ctx_t *>(handle);
    sd_cancel_generation(ctx, SD_CANCEL_RESET);

    jclass cls = env->GetObjectClass(jsink);
    Sink sink{env, jsink, env->GetMethodID(cls, "onProgress", "(II)V"), env->GetMethodID(cls, "onPreview", "([BIII)V"),
              env->GetMethodID(cls, "onImage", "(I[BIIIJ)V")};
    g_sink = &sink;
    sd_set_preview_callback(preview_cb, PREVIEW_PROJ, 2, true, false, nullptr);

    const std::string s_prompt = jstr(env, prompt), s_negative = jstr(env, negative);
    const std::string s_sampler = jstr(env, sampler), s_scheduler = jstr(env, scheduler);

    std::vector<std::string> paths;
    std::vector<sd_lora_t> loras;
    const jsize n_loras = lora_paths ? env->GetArrayLength(lora_paths) : 0;
    if (n_loras > 0) {
        jfloat * weights = env->GetFloatArrayElements(lora_weights, nullptr);
        paths.reserve((size_t) n_loras);
        for (jsize i = 0; i < n_loras; ++i) {
            paths.push_back(jstr(env, (jstring) env->GetObjectArrayElement(lora_paths, i)));
        }
        for (jsize i = 0; i < n_loras; ++i) loras.push_back({false, weights[i], paths[(size_t) i].c_str()});
        env->ReleaseFloatArrayElements(lora_weights, weights, JNI_ABORT);
    }

    std::vector<uint8_t> init, mask;
    if (init_rgb) {
        init.resize((size_t) env->GetArrayLength(init_rgb));
        env->GetByteArrayRegion(init_rgb, 0, (jsize) init.size(), reinterpret_cast<jbyte *>(init.data()));
    }
    if (mask_gray) {
        mask.resize((size_t) env->GetArrayLength(mask_gray));
        env->GetByteArrayRegion(mask_gray, 0, (jsize) mask.size(), reinterpret_cast<jbyte *>(mask.data()));
    }

    int64_t base_seed = seed;
    if (base_seed < 0) base_seed = (int64_t) (std::random_device{}() & 0x7fffffff);

    sd_img_gen_params_t p;
    sd_img_gen_params_init(&p);
    p.prompt = s_prompt.c_str();
    p.negative_prompt = s_negative.c_str();
    p.loras = loras.empty() ? nullptr : loras.data();
    p.lora_count = (uint32_t) loras.size();
    p.clip_skip = clip_skip;
    p.width = width;
    p.height = height;
    p.seed = base_seed;
    p.batch_count = batch;
    p.strength = strength;
    p.sample_params.sample_steps = steps;
    p.sample_params.guidance.txt_cfg = cfg;
    if (!s_sampler.empty()) p.sample_params.sample_method = str_to_sample_method(s_sampler.c_str());
    if (!s_scheduler.empty()) p.sample_params.scheduler = str_to_scheduler(s_scheduler.c_str());
    if (!init.empty()) p.init_image = {(uint32_t) width, (uint32_t) height, 3, init.data()};
    if (!mask.empty()) p.mask_image = {(uint32_t) width, (uint32_t) height, 1, mask.data()};

    sd_image_t * images = nullptr;
    int count = 0;
    const bool ok = generate_image(ctx, &p, &images, &count);
    sd_set_preview_callback(nullptr, PREVIEW_NONE, 1, false, false, nullptr);

    if (ok && images) {
        for (int i = 0; i < count; ++i) {
            if (!images[i].data) continue;
            jbyteArray arr = rgb_bytes(env, images[i]);
            env->CallVoidMethod(jsink, sink.on_image, (jint) i, arr, (jint) images[i].width, (jint) images[i].height,
                                (jint) images[i].channel, (jlong) (base_seed + i));
            env->DeleteLocalRef(arr);
        }
        free_sd_images(images, count);
    }
    g_sink = nullptr;
    return ok ? (jlong) base_seed : (jlong) -1;
}
