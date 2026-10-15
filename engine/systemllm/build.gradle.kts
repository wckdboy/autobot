// Gemini Nano, Android's built-in model, through ML Kit's GenAI Prompt API (AICore).
//
// Only the opt-in `playServices` app flavor depends on this module: ML Kit brings Google Play
// services client libraries, Firebase components and datatransport, and sends ML Kit usage
// metrics to Google (see README › "Gemini Nano"). The default `foss` build never includes it.
plugins {
    alias(libs.plugins.autobot.android.library)
    alias(libs.plugins.autobot.hilt)
}

android {
    namespace = "dev.wckdboy.autobot.engine.systemllm"
}

dependencies {
    implementation(projects.shared)
    implementation(libs.mlkit.genai.prompt)
    implementation(libs.kotlinx.coroutines.android)
}
