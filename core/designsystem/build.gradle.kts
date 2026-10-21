plugins {
    alias(libs.plugins.autobot.android.library)
    alias(libs.plugins.autobot.android.compose)
}

android {
    namespace = "dev.wckdboy.autobot.core.designsystem"
    // InkSnapshotTest renders ink states to PNG with Robolectric's native graphics.
    testOptions.unitTests.isIncludeAndroidResources = true
    // Compose binds its frame clock and window recomposer to the first Robolectric test in a JVM:
    // each test class that renders gets its own JVM.
    testOptions.unitTests.all { it.forkEvery = 1 }
    // InkFrameTimingTest (FrameMetrics on a device/emulator): `connectedDebugAndroidTest`.
    defaultConfig.testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    // The brand color scheme (Material's color utilities, from the brand purple).
    implementation(libs.material.kolor)

    testImplementation(libs.kotlinx.serialization.json)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.activity.compose)

    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.activity.compose)
}
