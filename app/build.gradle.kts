plugins {
    alias(libs.plugins.autobot.android.application)
    alias(libs.plugins.autobot.android.compose)
    alias(libs.plugins.autobot.hilt)
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "dev.wckdboy.autobot"

    defaultConfig {
        applicationId = "dev.wckdboy.autobot"
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        getByName("debug") {
            applicationIdSuffix = ".debug"
        }
    }
}

dependencies {
    implementation(projects.core.designsystem)
    implementation(projects.core.security)
    implementation(projects.core.network)
    implementation(projects.core.data)
    implementation(projects.providers.remote)
    implementation(projects.core.diffusion)
    implementation(projects.agent.runtime)
    implementation(projects.feature.chat)
    implementation(projects.feature.settings)
    implementation(projects.feature.imagine)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.viewmodel.navigation3)
    implementation(libs.androidx.hilt.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.navigation3.ui)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
}
