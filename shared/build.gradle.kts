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
            api(libs.kotlinx.datetime)
        }
        jvmMain.dependencies {
            // Only org.bouncycastle.math.ec.rfc8032.Ed25519 (no provider is registered).
            implementation(libs.bouncycastle.bcprov)
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

// A2UI eval (AUTO-104): how often a model produces valid A2UI through show_ui, against any
// OpenAI-compatible endpoint. `./gradlew :shared:a2uiEval -Pmodel=qwen3:4b` (Ollama by default);
// options: -Pendpoint -Pkey-env -Ponly -Ptemperature -Pmax-tokens -Ptimeout -Pextra -Ptasks -Pout.
// Writes build/a2ui-eval/ (report.md, per-model JSONL of every attempt). See README "A2UI eval".
tasks.register<JavaExec>("a2uiEval") {
    group = "verification"
    description = "Runs the A2UI show_ui eval against an OpenAI-compatible endpoint"
    val test = kotlin.jvm().compilations.getByName("test")
    dependsOn(test.compileAllTaskName)
    classpath = files(test.output.allOutputs, test.runtimeDependencyFiles)
    mainClass.set("dev.wckdboy.autobot.shared.a2ui.eval.A2uiEvalMainKt")
    val names = listOf("model", "endpoint", "key-env", "only", "temperature", "max-tokens", "timeout", "extra", "tasks", "salvage", "args")
    val values = names.map { n -> providers.gradleProperty(n).map { "--$n=$it" } }
    val out = providers.gradleProperty("out").orElse(rootProject.layout.buildDirectory.dir("a2ui-eval").map { it.asFile.absolutePath })
    argumentProviders += CommandLineArgumentProvider { values.mapNotNull { it.orNull } + "--out=${out.get()}" }
}
