// Port of the DeepSeek Harness (MIT) agent architecture: a plugin context, an append-only session
// log, the step/turn loop, the tool execution pipeline, approvals, retry and compaction.
// Pure Kotlin: no Android APIs, so the whole runtime is covered by JVM unit tests.
plugins {
    alias(libs.plugins.autobot.android.library)
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "dev.wckdboy.autobot.agent.core"
}

dependencies {
    api(libs.kotlinx.coroutines.core)
    api(libs.kotlinx.serialization.json)
}
