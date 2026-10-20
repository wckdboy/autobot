import java.util.Properties
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
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
    id("androidx.baselineprofile")
}

/** `2026-10-16`: the committer date of HEAD, or blank without git. */
val commitDate: String = runCatching {
    providers.exec {
        commandLine("git", "log", "-1", "--format=%cs")
        isIgnoreExitValue = true
    }.standardOutput.asText.get().trim()
}.getOrDefault("").takeIf { Regex("\\d{4}-\\d{2}-\\d{2}").matches(it) }.orEmpty()

android {
    namespace = "dev.wckdboy.autobot"
    // Same NDK as the engines, so packaging can strip their debug symbols.
    ndkVersion = libs.versions.ndk.get()

    defaultConfig {
        applicationId = "ai.wckd.autobot"
        versionCode = 26
        versionName = "1.12.0"
        // About shows the build date: the date of the commit it was built from, so the same source
        // always builds the same APK (reproducible builds); blank when built outside git.
        buildConfigField("String", "BUILD_DATE", "\"$commitDate\"")
    }

    // About shows this release's notes: shared/release/whats-new.md (updated by scripts/release.sh)
    // is packaged as the asset `whats-new.md`.
    sourceSets.getByName("main").assets.srcDir(rootProject.file("shared/release"))

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

// The Baseline Profile (classes and methods cold start and first use run, compiled ahead of time
// by ART through profileinstaller) is generated on an emulator or phone by :baselineprofile and
// committed as text under src/fossRelease/generated/baselineProfiles. Builds only read that file:
// no device is involved, and the same source still builds the same APK.
baselineProfile {
    automaticGenerationDuringBuild = false
    saveInSrc = true
    // The startup part also orders the startup classes first in the primary dex file.
    dexLayoutOptimization = true
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

/**
 * Checks the packaged APK the way the phone's DSP will see it. FastRPC loads the Hexagon skeleton
 * libraries (`libQnnHtpV<arch>Skel.so`) from ADSP_LIBRARY_PATH, i.e. from real files in the app's
 * native library directory: they must be in the APK for every HTP generation, compressed (so the
 * installer extracts them: `useLegacyPackaging`) and still Hexagon ELF files. For minified builds
 * it also reads R8's mapping: the JNI classes and the callbacks native code looks up by name
 * (GetMethodID) must keep their names, or every generation fails with NoSuchMethodError.
 */
abstract class VerifyNativePackaging : DefaultTask() {
    @get:org.gradle.api.tasks.InputFiles
    abstract val apkDirectory: DirectoryProperty

    @get:org.gradle.api.tasks.Optional
    @get:org.gradle.api.tasks.InputFile
    abstract val mappingFile: org.gradle.api.file.RegularFileProperty

    @get:org.gradle.api.tasks.OutputFile
    abstract val report: org.gradle.api.file.RegularFileProperty

    @TaskAction
    fun verify() {
        val problems = mutableListOf<String>()
        val apks = apkDirectory.get().asFile.listFiles { f -> f.name.endsWith(".apk") }.orEmpty()
        if (apks.isEmpty()) problems += "no APK in ${apkDirectory.get().asFile}"
        apks.forEach { apk ->
            ZipFile(apk).use { zip ->
                val required = listOf("libQnnHtp.so", "libQnnSystem.so") +
                    listOf(68, 69, 73, 75, 79, 81).flatMap { listOf("libQnnHtpV${it}Skel.so", "libQnnHtpV${it}Stub.so") }
                required.forEach { name ->
                    val entry = zip.getEntry("lib/arm64-v8a/$name")
                    when {
                        entry == null -> problems += "${apk.name}: $name missing"
                        entry.method != ZipEntry.DEFLATED ->
                            problems += "${apk.name}: $name is stored, not compressed: native libraries would not be extracted and the DSP could not load the skel"
                        name.endsWith("Skel.so") -> {
                            val header = zip.getInputStream(entry).use { it.readNBytes(20) }
                            val machine = (header[18].toInt() and 0xff) or ((header[19].toInt() and 0xff) shl 8)
                            if (header.size < 20 || header[0] != 0x7f.toByte() || machine != 164) problems += "${apk.name}: $name is not a Hexagon ELF (e_machine $machine)"
                        }
                    }
                }
            }
        }
        mappingFile.orNull?.asFile?.takeIf { it.isFile }?.let { mapping ->
            val lines = mapping.readLines()
            val jniClasses = listOf(
                "dev.wckdboy.autobot.engine.npu.NativeNpu", "dev.wckdboy.autobot.engine.npu.NativeNpu\$Sink",
                "dev.wckdboy.autobot.engine.diffusion.NativeSd", "dev.wckdboy.autobot.engine.diffusion.NativeSd\$Sink",
                "dev.wckdboy.autobot.engine.llama.NativeLlama", "dev.wckdboy.autobot.engine.llama.NativeLlama\$DeltaSink",
                "dev.wckdboy.autobot.engine.speech.NativeWhisper",
            )
            jniClasses.forEach { cls ->
                val line = lines.firstOrNull { it.startsWith("$cls -> ") }
                if (line != null && line != "$cls -> $cls:") problems += "R8 renamed JNI class: $line"
            }
            // Callbacks looked up with GetMethodID: no Sink implementation may have them renamed.
            val callbacks = setOf("onStatus", "onProgress", "onImage", "onPreview", "onDelta")
            val renamed = Regex("""^\s+(?:[\d:]+:)?\S+ (\w+)\([^)]*\)(?::[\d:]+)? -> (\w+)$""")
            var inSink = false
            var sinks = 0
            lines.forEach { l ->
                if (!l.startsWith(" ") && !l.startsWith("#")) {
                    val cls = l.substringBefore(" -> ")
                    inSink = cls.startsWith("dev.wckdboy.autobot.engine.") && ("\$sink\$" in cls || cls.endsWith("Sink"))
                    if (inSink) sinks++
                    return@forEach
                }
                if (!inSink) return@forEach
                val m = renamed.find(l) ?: return@forEach
                val (from, to) = m.destructured
                if (from in callbacks && from != to) problems += "R8 renamed JNI callback: ${l.trim()}"
            }
            if (sinks == 0) problems += "no JNI Sink classes found in ${mapping.name}: the check is out of date"
        }
        val out = report.get().asFile
        out.parentFile.mkdirs()
        out.writeText(if (problems.isEmpty()) "ok\n" else problems.joinToString("\n", postfix = "\n"))
        if (problems.isNotEmpty()) throw GradleException("Native packaging check failed:\n" + problems.joinToString("\n"))
    }
}

androidComponents {
    onVariants { variant ->
        val name = variant.name.replaceFirstChar(Char::uppercase)
        val task = tasks.register<GenerateShortcuts>("generate${name}Shortcuts") {
            applicationId.set(variant.applicationId)
        }
        variant.sources.res?.addGeneratedSourceDirectory(task, GenerateShortcuts::outputDirectory)

        val verify = tasks.register<VerifyNativePackaging>("verify${name}NativePackaging") {
            apkDirectory.set(variant.artifacts.get(com.android.build.api.artifact.SingleArtifact.APK))
            if (variant.isMinifyEnabled) mappingFile.set(variant.artifacts.get(com.android.build.api.artifact.SingleArtifact.OBFUSCATION_MAPPING_FILE))
            report.set(layout.buildDirectory.file("reports/native-packaging/${variant.name}.txt"))
        }
        afterEvaluate { tasks.named("assemble$name") { dependsOn(verify) } }
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

    baselineProfile(projects.baselineprofile)

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

    // The engine memory coordinator against fake engine binders.
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
}
