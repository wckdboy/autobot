plugins {
    alias(libs.plugins.autobot.android.library)
    alias(libs.plugins.autobot.hilt)
}

android {
    namespace = "dev.wckdboy.autobot.core.security"
}

dependencies {
    // The PIN verifier, attempt counter and wipe plan (`dev.wckdboy.autobot.shared.panic`).
    implementation(projects.shared)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.kotlinx.coroutines.android)
}
