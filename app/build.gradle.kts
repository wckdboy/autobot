import java.util.Properties

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
        versionName = "1.0.0"
    }

    // Release signing is optional and local: when `keystore.properties` (git-ignored) exists at the
    // repository root, release builds are signed with it; otherwise they stay unsigned for the
    // distributor (F-Droid / GitHub releases) to sign.
    val keystoreFile = rootProject.file("keystore.properties")
    val releaseSigning = if (keystoreFile.isFile) {
        val props = Properties().apply { keystoreFile.inputStream().use(::load) }
        signingConfigs.create("release") {
            storeFile = file(props.getProperty("storeFile"))
            storePassword = props.getProperty("storePassword")
            keyAlias = props.getProperty("keyAlias")
            keyPassword = props.getProperty("keyPassword")
        }
    } else {
        null
    }

    buildTypes {
        getByName("debug") {
            applicationIdSuffix = ".debug"
        }
        getByName("release") {
            signingConfig = releaseSigning
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
