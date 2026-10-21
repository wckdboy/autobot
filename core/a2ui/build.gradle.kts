// The Compose renderer of A2UI v1.0 (generative UI): draws the shared core's render trees
// (`:shared` `a2ui`) with standard Material 3 components in the app's theme. Self-contained:
// it knows nothing about chats; a screen hands it an A2uiHost and a surface id.
plugins {
    alias(libs.plugins.autobot.android.library)
    alias(libs.plugins.autobot.android.compose)
}

android {
    namespace = "dev.wckdboy.autobot.core.a2ui"
    testOptions.unitTests.isIncludeAndroidResources = true
    // Compose's frame clock binds to the first Robolectric main looper in a JVM: one JVM per test class.
    testOptions.unitTests.all { it.forkEvery = 1 }
}

dependencies {
    api(projects.shared)
    implementation(projects.core.designsystem)

    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.activity.compose)
}
