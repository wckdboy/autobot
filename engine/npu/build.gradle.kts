import dev.wckdboy.autobot.buildlogic.FetchZipEntriesTask

// NPU/GPU image engine: Qualcomm QNN (HTP) for UNet/VAE/upscaler context binaries and MNN
// (pinned submodule) for text encoders and GPU (OpenCL) diffusion, in the isolated ":npu"
// process behind Binder.
plugins {
    alias(libs.plugins.autobot.android.library)
    alias(libs.plugins.autobot.hilt)
}

val qairtVersion = libs.versions.qairt.get()
val qairtDir = rootProject.layout.projectDirectory.dir("third_party/qairt/$qairtVersion")
val qairtInclude: String = qairtDir.dir("include/QNN").asFile.invariantSeparatorsPath

// QNN headers are proprietary ("Confidential and Proprietary"): they are fetched from
// Qualcomm's public SDK download into the git-ignored third_party/qairt and never committed.
val fetchQairtHeaders by tasks.registering(FetchZipEntriesTask::class) {
    url.set("https://softwarecenter.qualcomm.com/api/download/software/sdks/Qualcomm_AI_Runtime_Community/All/$qairtVersion/v$qairtVersion.zip")
    prefixes.set(listOf("qairt/$qairtVersion/include/QNN/"))
    stripPrefix.set("qairt/$qairtVersion/")
    outputDir.set(qairtDir)
}

android {
    namespace = "dev.wckdboy.autobot.engine.npu"
    ndkVersion = libs.versions.ndk.get()

    buildFeatures {
        aidl = true
    }

    defaultConfig {
        ndk {
            abiFilters += "arm64-v8a"
        }
        externalNativeBuild {
            cmake {
                arguments += listOf(
                    "-DCMAKE_BUILD_TYPE=Release",
                    "-DANDROID_STL=c++_shared",
                    "-DQAIRT_INCLUDE=$qairtInclude",
                )
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = libs.versions.cmake.get()
        }
    }
}

tasks.matching { it.name.startsWith("configureCMake") || it.name.startsWith("buildCMake") }.configureEach {
    dependsOn(fetchQairtHeaders)
}

dependencies {
    // Qualcomm QNN runtime (HTP backend + DSP skeletons V68–V81), QTI AI Stack license:
    // redistributed in object code as part of the app only. Unused parts are excluded in :app.
    implementation(libs.qnn.runtime)
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
}
