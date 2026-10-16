import com.android.build.api.variant.LibraryAndroidComponentsExtension
import dev.wckdboy.autobot.buildlogic.FetchPinnedZipTask

// On-device LLM engine: llama.cpp (pinned submodule) behind a bound service in the isolated
// ":llm" process. The app talks to it over Binder only, never over a socket.
plugins {
    alias(libs.plugins.autobot.android.library)
    alias(libs.plugins.autobot.hilt)
}

android {
    namespace = "dev.wckdboy.autobot.engine.llama"
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
                arguments += listOf("-DCMAKE_BUILD_TYPE=Release", "-DANDROID_STL=c++_shared")
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

// llama.cpp's Hexagon NPU backend: libggml-hexagon.so (host side) plus one HTP op library per
// Hexagon generation, which the DSP loads through FastRPC from ADSP_LIBRARY_PATH. They need the
// Linux-only Hexagon SDK, so .github/workflows/hexagon.yml builds them from the same llama.cpp
// commit as third_party/llama.cpp; the build fetches that release and checks every file against
// the hashes pinned here. Bump the tag and hashes together with the submodule.
val hexagonRelease = "hexagon-d81235049384"
val fetchHexagonLibs by tasks.registering(FetchPinnedZipTask::class) {
    url.set("https://github.com/wckdboy/autobot/releases/download/$hexagonRelease/hexagon-libs.zip")
    into.set("arm64-v8a")
    sha256.set(
        mapOf(
            "libggml-hexagon.so" to "1bb5cbbba750717e2d0723fbe927f61f1c05dbeee46a8cda6201e3759f30b757",
            "libggml-htp-v73.so" to "f8b3134acf9458c645c36856b532c25540c0e59dd9d728aa1845a46a6ccc1ebc",
            "libggml-htp-v75.so" to "07ff1b359e89a92e048b2262f8f5f87bc14af6990738615ee241a1f498bca2a1",
            "libggml-htp-v79.so" to "d52e89a9b88813229d8bd09b1e90d08e90b7ae9d491292642669f270b8f8cef0",
            "libggml-htp-v81.so" to "b7343ed48e73693885af3a657597d454cc8163e27f0aef0e055aedcf68ee54c6",
        ),
    )
    outputDir.set(layout.buildDirectory.dir("hexagon/$hexagonRelease"))
}

extensions.configure<LibraryAndroidComponentsExtension> {
    onVariants { variant ->
        variant.sources.jniLibs?.addGeneratedSourceDirectory(fetchHexagonLibs, FetchPinnedZipTask::outputDir)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)

    // The client against a fake engine binder (death, timeouts, cancel); no native code runs.
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
}
