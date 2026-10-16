import java.util.Properties
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction

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
        applicationId = "ai.wckd.autobot"
        versionCode = 13
        versionName = "1.6.1"
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
            // Hexagon DSP binaries (llama.cpp HTP ops) are not ARM ELF files; the NDK strip
            // cannot handle them, and the DSP loader expects them unmodified.
            keepDebugSymbols += "**/libggml-htp-v*.so"
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

    // `foss` is the build Autobot ships: no Google Play services, Firebase or telemetry.
    // `playServices` adds Gemini Nano (ML Kit GenAI → AICore) as the system model, which brings
    // GMS client libraries, Firebase components and ML Kit's usage metrics to Google; it is opt-in,
    // installs next to the default app and is never published by scripts/release.sh.
    flavorDimensions += "distribution"
    productFlavors {
        create("foss") {
            dimension = "distribution"
            isDefault = true
        }
        create("playServices") {
            dimension = "distribution"
            applicationIdSuffix = ".gms"
            versionNameSuffix = "-gms"
        }
    }
}

/**
 * Launcher shortcuts (`res/xml/shortcuts.xml`). A static shortcut's intent needs a literal target
 * package, which differs per variant (`.debug`, `.gms`), so the file is generated per variant.
 */
abstract class GenerateShortcuts : DefaultTask() {
    @get:Input
    abstract val applicationId: Property<String>

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun generate() {
        val shortcuts = listOf(
            Triple("new_chat", "NEW_CHAT", "chat"),
            Triple("voice", "VOICE", "voice"),
            Triple("imagine", "IMAGINE", "imagine"),
        ).joinToString("\n") { (id, action, name) ->
            """
            |    <shortcut
            |        android:enabled="true"
            |        android:icon="@drawable/ic_shortcut_$name"
            |        android:shortcutId="$id"
            |        android:shortcutShortLabel="@string/shortcut_$name">
            |        <intent
            |            android:action="ai.wckd.autobot.action.$action"
            |            android:targetClass="dev.wckdboy.autobot.MainActivity"
            |            android:targetPackage="${applicationId.get()}" />
            |    </shortcut>
            """.trimMargin()
        }
        val file = outputDirectory.get().file("xml/shortcuts.xml").asFile
        file.parentFile.mkdirs()
        file.writeText(
            "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n" +
                "<shortcuts xmlns:android=\"http://schemas.android.com/apk/res/android\">\n$shortcuts\n</shortcuts>\n",
        )
    }
}

androidComponents {
    onVariants { variant ->
        val task = tasks.register<GenerateShortcuts>("generate${variant.name.replaceFirstChar(Char::uppercase)}Shortcuts") {
            applicationId.set(variant.applicationId)
        }
        variant.sources.res?.addGeneratedSourceDirectory(task, GenerateShortcuts::outputDirectory)
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
    implementation(projects.feature.studio)
    implementation(projects.core.models)
    implementation(projects.engine.llama)
    implementation(projects.engine.diffusion)
    implementation(projects.engine.npu)
    implementation(projects.engine.speech)
    implementation(projects.engine.tts)
    implementation(projects.engine.audiogen)
    // Gemini Nano: only in the opt-in flavor, never on the default (foss) classpath.
    "playServicesImplementation"(projects.engine.systemllm)

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
