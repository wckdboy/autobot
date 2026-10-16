plugins {
    alias(libs.plugins.autobot.android.library)
    alias(libs.plugins.autobot.android.compose)
}

android {
    namespace = "dev.wckdboy.autobot.core.designsystem"
    // InkSnapshotTest renders ink states to PNG with Robolectric's native graphics.
    testOptions.unitTests.isIncludeAndroidResources = true
    // InkFrameTimingTest (FrameMetrics on a device/emulator): `connectedDebugAndroidTest`.
    defaultConfig.testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)

    testImplementation(libs.kotlinx.serialization.json)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.activity.compose)

    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.activity.compose)
}
