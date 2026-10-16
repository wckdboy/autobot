// Creative Studio: the image / audio / video rooms, workflows and library on top of the shared
// Studio core (`:shared` dev.wckdboy.autobot.shared.studio): the Android store, the job queue with
// its engines (on-device diffusion, upscaler, Stable Audio, Kokoro voiceover, A1111 / Local SSE
// and ComfyUI backends) and the agent's run_workflow tool.
plugins {
    alias(libs.plugins.autobot.android.library)
    alias(libs.plugins.autobot.android.compose)
    alias(libs.plugins.autobot.hilt)
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "dev.wckdboy.autobot.feature.studio"
    // StudioSnapshotTest renders the rooms to PNG with Robolectric's native graphics.
    testOptions.unitTests.isIncludeAndroidResources = true
    // Compose binds its frame clock and window recomposer to the first Robolectric test in a JVM:
    // each test class that renders gets its own JVM.
    testOptions.unitTests.all { it.forkEvery = 1 }
}

dependencies {
    implementation(projects.core.designsystem)
    implementation(projects.core.data)
    implementation(projects.core.diffusion)
    implementation(projects.core.models)
    implementation(projects.core.network)
    implementation(projects.core.security)
    implementation(projects.agent.runtime)
    implementation(projects.engine.audiogen)
    implementation(projects.engine.tts)
    implementation(projects.feature.imagine)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.hilt.lifecycle.viewmodel.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
}
