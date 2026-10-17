// JNI bridge for whisper.cpp, used only inside the isolated ":asr" process. A thin wrapper over
// the platform-neutral core (core/autobot_whisper.h), which the iOS app links too.

#include <jni.h>

#include <unistd.h>

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
 * Transcribes [n_samples] 16 kHz mono float samples read from the file descriptor [fd] (an
 * anonymous memfd from the app process; nothing on storage) with the model at [model_path] (kept
 * loaded between calls). [language] is a code like "en", or "auto". [partial]: a live partial pass
 * (AUTOBOT_WHISPER_PARTIAL). Returns {text, language} or {error}.
 */
extern "C" JNIEXPORT jbyteArray JNICALL
Java_dev_wckdboy_autobot_engine_speech_NativeWhisper_transcribeFd(JNIEnv * env, jobject, jstring jmodel, jint fd, jint n_samples, jstring jlang,
                                                                  jboolean partial) {
    const std::string model = jstr(env, jmodel);
    const std::string lang = jstr(env, jlang);
    std::vector<float> pcm(n_samples > 0 ? (size_t) n_samples : 0);
    const size_t want = pcm.size() * sizeof(float);
    size_t got = 0;
    auto * bytes = reinterpret_cast<char *>(pcm.data());
    while (got < want) {
        const ssize_t n = pread(fd, bytes + got, want - got, (off_t) got);
        if (n <= 0) break;
        got += (size_t) n;
    }
    pcm.resize(got / sizeof(float));
    return owned_bytes(env, autobot_whisper_transcribe_ex(model.c_str(), pcm.data(), pcm.size(), lang.c_str(), 0,
                                                          partial ? AUTOBOT_WHISPER_PARTIAL : 0));
}

extern "C" JNIEXPORT void JNICALL
Java_dev_wckdboy_autobot_engine_speech_NativeWhisper_cancel(JNIEnv *, jobject) {
    autobot_whisper_cancel();
}

extern "C" JNIEXPORT void JNICALL
Java_dev_wckdboy_autobot_engine_speech_NativeWhisper_free(JNIEnv *, jobject) {
    autobot_whisper_free();
}
