plugins {
    alias(libs.plugins.autobot.android.library)
    alias(libs.plugins.autobot.android.compose)
}

android {
    namespace = "dev.wckdboy.autobot.core.designsystem"
}

dependencies {
    implementation(libs.androidx.core.ktx)
}
