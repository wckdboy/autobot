plugins {
    alias(libs.plugins.autobot.android.library)
    alias(libs.plugins.autobot.android.compose)
    alias(libs.plugins.autobot.hilt)
}

android {
    namespace = "dev.wckdboy.autobot.feature.models"
    // Compose UI tests render the screen with Robolectric.
    testOptions.unitTests.isIncludeAndroidResources = true
}

// The large-text audit (renders at 200 % font and lists text that does not fit), shared by the
// feature modules' LargeTextTest.
androidComponents {
    onVariants { variant ->
        variant.hostTests.values.forEach { it.sources.kotlin?.addStaticSourceDirectory(rootProject.file("core/designsystem/src/largeTextTest/kotlin").path) }
    }
}

dependencies {
    implementation(projects.core.designsystem)
    implementation(projects.core.data)
    implementation(projects.core.models)
    implementation(projects.core.diffusion)
    implementation(projects.core.security)
    implementation(projects.core.network)
    implementation(projects.shared)
    implementation(projects.agent.runtime)

    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.hilt.lifecycle.viewmodel.compose)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.robolectric)
}
