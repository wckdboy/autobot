// On-device text to audio: Stable Audio Open Small (ONNX export, 8-bit weight storage) on ONNX
// Runtime's CPU provider, in the app process like the Kokoro voice (no native code of our own; the
// sessions are created per generation and freed after it unless kept). Tokenizer, sampler, seconds
// embedder and WAV encoding are the shared Kotlin pipeline (`dev.wckdboy.autobot.shared.audiogen`).
plugins {
    alias(libs.plugins.autobot.android.library)
    alias(libs.plugins.autobot.hilt)
}

android {
    namespace = "dev.wckdboy.autobot.engine.audiogen"
}

dependencies {
    implementation(projects.shared)
    implementation(libs.onnxruntime.android)
    implementation(libs.kotlinx.coroutines.android)

    // Desktop ONNX Runtime for StableAudioOnnxBenchmarkTest (runs only with -Paudiogen.dir=<model folder>).
    testImplementation(libs.onnxruntime.jvm)
    testImplementation(libs.kotlinx.coroutines.test)
}

tasks.withType<Test>().configureEach {
    providers.gradleProperty("audiogen.dir").orNull?.let { systemProperty("audiogen.dir", it) }
    // The real-model test holds ~1.5 GB of activations while decoding.
    maxHeapSize = "3g"
}
