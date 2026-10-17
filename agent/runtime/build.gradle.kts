// Android host for :agent:core — adapters to the configured chat providers, the encrypted
// session store, workspace file/search/web tools, skills and AGENTS.md instructions, Hilt wiring,
// and scheduled recipes (WorkManager).
plugins {
    alias(libs.plugins.autobot.android.library)
    alias(libs.plugins.autobot.hilt)
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "dev.wckdboy.autobot.agent.runtime"
    // WorkManager's test helpers run on Robolectric.
    testOptions.unitTests.isIncludeAndroidResources = true
}

dependencies {
    api(projects.agent.core)
    api(projects.shared)
    api(projects.providers.remote)
    implementation(projects.core.data)
    implementation(projects.core.network)
    implementation(projects.core.security)
    implementation(projects.core.models)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.work.runtime)

    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.work.testing)
}
