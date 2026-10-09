// Android host for :agent:core — adapters to the configured chat providers, the encrypted
// session store, workspace file/search/web tools, skills and AGENTS.md instructions, Hilt wiring.
plugins {
    alias(libs.plugins.autobot.android.library)
    alias(libs.plugins.autobot.hilt)
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "dev.wckdboy.autobot.agent.runtime"
}

dependencies {
    api(projects.agent.core)
    api(projects.providers.remote)
    implementation(projects.core.data)
    implementation(projects.core.network)
    implementation(projects.core.security)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.okhttp.mockwebserver)
}
