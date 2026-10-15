import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework

// Code both apps run: the agent host (provider adapter, workspace tools, AGENTS.md / skills,
// web_fetch), the OpenAI-compatible wire format and SSE decoding, the on-device engine request
// codec, model management (catalogs, fit rules, hub clients, download state machine), and the
// Swift-facing bridge. Android consumes the JVM target; iOS links
// AutobotShared.xcframework (`./gradlew :shared:assembleAutobotSharedReleaseXCFramework`).
plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("org.jetbrains.kotlin.plugin.serialization")
}

kotlin {
    jvm()

    val xcf = XCFramework("AutobotShared")
    listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.binaries.framework {
            baseName = "AutobotShared"
            isStatic = true
            export(projects.agent.core)
            binaryOption("bundleId", "ai.wckd.autobot.shared")
            xcf.add(this)
        }
    }

    jvmToolchain(17)

    compilerOptions {
        optIn.add("kotlin.concurrent.atomics.ExperimentalAtomicApi")
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    sourceSets {
        commonMain.dependencies {
            api(projects.agent.core)
            api(libs.okio)
            api(libs.kotlinx.coroutines.core)
            api(libs.kotlinx.serialization.json)
        }
        jvmTest.dependencies {
            implementation(libs.junit)
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.okio.fakefilesystem)
        }
    }
}

// Optional: `-Pkokoro.dir=<folder with the misaki lexicons>` runs the frontend against the full
// lexicons (KokoroFrontendTest.fullLexicons); skipped otherwise.
// `-Paudiogen.dir=<Stable Audio folder with tokenizer.json>` checks T5Tokenizer against the full vocabulary.
tasks.withType<Test>().configureEach {
    providers.gradleProperty("kokoro.dir").orNull?.let { systemProperty("kokoro.dir", it) }
    providers.gradleProperty("audiogen.dir").orNull?.let { systemProperty("audiogen.dir", it) }
}
