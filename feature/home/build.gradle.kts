// RUN hub (start chat / agent / code / image, recent runs), first-run welcome and the REMOTE hub.
plugins {
    alias(libs.plugins.autobot.android.library)
    alias(libs.plugins.autobot.android.compose)
    alias(libs.plugins.autobot.hilt)
}

android {
    namespace = "dev.wckdboy.autobot.feature.home"
}

dependencies {
    implementation(projects.core.designsystem)
    implementation(projects.core.data)
    implementation(projects.core.models)
    implementation(projects.core.diffusion)
    implementation(projects.core.network)
    implementation(projects.providers.remote)
    implementation(projects.agent.runtime)
    implementation(projects.feature.imagine)

    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.hilt.lifecycle.viewmodel.compose)
    implementation(libs.kotlinx.coroutines.android)
}
