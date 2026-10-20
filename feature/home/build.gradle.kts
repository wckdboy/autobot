// RUN hub (start chat / agent / code / image, recent runs), first-run welcome and the REMOTE hub.
plugins {
    alias(libs.plugins.autobot.android.library)
    alias(libs.plugins.autobot.android.compose)
    alias(libs.plugins.autobot.hilt)
}

android {
    namespace = "dev.wckdboy.autobot.feature.home"
    // Compose UI tests render screens with Robolectric, one JVM per test class (see :feature:chat).
    testOptions.unitTests.isIncludeAndroidResources = true
    testOptions.unitTests.all { it.forkEvery = 1 }
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
    implementation(projects.core.network)
    implementation(projects.providers.remote)
    implementation(projects.agent.runtime)
    implementation(projects.feature.imagine)

    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.hilt.lifecycle.viewmodel.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.activity.compose)

    testImplementation(libs.robolectric)
}
