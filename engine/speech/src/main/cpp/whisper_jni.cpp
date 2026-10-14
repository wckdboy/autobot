// JNI bridge for whisper.cpp, used only inside the isolated ":asr" process. A thin wrapper over
// the platform-neutral core (core/autobot_whisper.h), which the iOS app links too.

#include <jni.h>

#include <cstdio>
#include <string>
#include <vector>

#include "core/autobot_whisper.h"

namespace {

std::string jstr(JNIEnv * env, jstring s) {
    if (!s) return {};
    const char * c = env->GetStringUTFChars(s, nullptr);
    std::string out(c);
    env->ReleaseStringUTFChars(s, c);
    return out;
}

jbyteArray owned_bytes(JNIEnv * env, char * s) {
    const std::string str = s ? s : "";
    autobot_whisper_string_free(s);
    jbyteArray arr = env->NewByteArray((jsize) str.size());
    env->SetByteArrayRegion(arr, 0, (jsize) str.size(), reinterpret_cast<const jbyte *>(str.data()));
    return arr;
}

}  // namespace

/**
 * Transcribes 16 kHz mono float PCM from [pcm_path] with the model at [model_path] (kept loaded
 * between calls). [language] is a code like "en", or "auto". Returns {text, language} or {error}.
 */
extern "C" JNIEXPORT jbyteArray JNICALL
Java_dev_wckdboy_autobot_engine_speech_NativeWhisper_transcribe(JNIEnv * env, jobject, jstring jmodel, jstring jpcm, jstring jlang) {
    const std::string model = jstr(env, jmodel);
    const std::string lang = jstr(env, jlang);
    std::vector<float> pcm;
    if (FILE * f = std::fopen(jstr(env, jpcm).c_str(), "rb")) {
        float buf[4096];
        size_t n;
        while ((n = std::fread(buf, sizeof(float), 4096, f)) > 0) pcm.insert(pcm.end(), buf, buf + n);
        std::fclose(f);
    }
    return owned_bytes(env, autobot_whisper_transcribe(model.c_str(), pcm.data(), pcm.size(), lang.c_str(), 0));
}

extern "C" JNIEXPORT void JNICALL
Java_dev_wckdboy_autobot_engine_speech_NativeWhisper_free(JNIEnv *, jobject) {
    autobot_whisper_free();
}
