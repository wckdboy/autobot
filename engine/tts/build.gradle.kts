// On-device neural voice: Kokoro-82M (ONNX export, 8-bit) on ONNX Runtime's CPU provider, in the
// app process (one ~100 MB session; no native code of our own). Text → phonemes → token ids is
// the shared Kotlin frontend (`dev.wckdboy.autobot.shared.tts`).
plugins {
    alias(libs.plugins.autobot.android.library)
    alias(libs.plugins.autobot.hilt)
}

android {
    namespace = "dev.wckdboy.autobot.engine.tts"
}

dependencies {
    implementation(projects.shared)
    implementation(libs.onnxruntime.android)
    implementation(libs.kotlinx.coroutines.android)

    // Desktop ONNX Runtime for KokoroOnnxBenchmarkTest (runs only with -Pkokoro.dir=<voice folder>).
    testImplementation(libs.onnxruntime.jvm)
}

tasks.withType<Test>().configureEach {
    providers.gradleProperty("kokoro.dir").orNull?.let { systemProperty("kokoro.dir", it) }
}
