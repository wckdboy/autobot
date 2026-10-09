// Image generation engines behind one interface. Clean-room: written from public API specs
// (AUTOMATIC1111 `/sdapi/v1`, a local HTTP+SSE protocol); no code from xororz/local-dream.
plugins {
    alias(libs.plugins.autobot.android.library)
    alias(libs.plugins.autobot.hilt)
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "dev.wckdboy.autobot.core.diffusion"
}

dependencies {
    api(projects.core.network)
    implementation(projects.core.data)

    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.core)
    api(libs.kotlinx.serialization.json)

    testImplementation(libs.okhttp.mockwebserver)
}
