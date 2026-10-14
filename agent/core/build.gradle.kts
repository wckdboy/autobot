// Port of the DeepSeek Harness (MIT) agent architecture: a plugin context, an append-only session
// log, the step/turn loop, the tool execution pipeline, approvals, retry and compaction.
// Kotlin Multiplatform with no platform APIs: Android consumes the JVM target, iOS gets it
// through :shared's AutobotShared.xcframework, and the whole runtime is covered by JVM tests.
plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("org.jetbrains.kotlin.plugin.serialization")
}

kotlin {
    jvm()
    iosArm64()
    iosSimulatorArm64()

    jvmToolchain(17)

    compilerOptions {
        optIn.add("kotlin.concurrent.atomics.ExperimentalAtomicApi")
    }

    sourceSets {
        commonMain.dependencies {
            api(libs.kotlinx.coroutines.core)
            api(libs.kotlinx.serialization.json)
            implementation(libs.atomicfu)
        }
        jvmTest.dependencies {
            implementation(libs.junit)
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
