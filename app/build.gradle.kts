import java.util.Properties

plugins {
    alias(libs.plugins.autobot.android.application)
    alias(libs.plugins.autobot.android.compose)
    alias(libs.plugins.autobot.hilt)
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "dev.wckdboy.autobot"
    // Same NDK as the engines, so packaging can strip their debug symbols.
    ndkVersion = libs.versions.ndk.get()

    defaultConfig {
        applicationId = "dev.wckdboy.autobot"
        versionCode = 2
        versionName = "1.1.0"
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

    // llama.cpp loads its CPU backend variants (libggml-cpu-*.so) by scanning nativeLibraryDir, so
    // native libraries must be extracted on install rather than mapped from the APK.
    packaging {
        jniLibs {
            useLegacyPackaging = true
            // Link-time OpenCL ICD loader: the phone's own vendor libOpenCL.so is used instead.
            excludes += "**/libOpenCL.so"
            // QNN runtime parts Autobot does not use: the on-device graph compiler (it only runs
            // precompiled context binaries), the legacy DSP backend and the QNN GPU backend.
            excludes += listOf("**/libQnnHtpPrepare.so", "**/libQnnDsp*.so", "**/libQnnGpu.so")
        }
    }

    buildFeatures {
        buildConfig = true
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
    implementation(projects.feature.models)
    implementation(projects.feature.home)
    implementation(projects.core.models)
    implementation(projects.engine.llama)
    implementation(projects.engine.diffusion)
    implementation(projects.engine.npu)

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
