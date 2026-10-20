plugins {
    alias(libs.plugins.autobot.android.library)
    alias(libs.plugins.autobot.android.compose)
    alias(libs.plugins.autobot.hilt)
}

android {
    namespace = "dev.wckdboy.autobot.feature.chat"
    // Recomposition-count tests render screens with Robolectric.
    testOptions.unitTests.isIncludeAndroidResources = true
    // Compose's frame clock binds to the first Robolectric main looper in a JVM; each test
    // class that renders gets its own JVM, or the second one never recomposes.
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
    implementation(projects.core.a2ui)
    implementation(projects.core.data)
    implementation(projects.core.network)
    implementation(projects.providers.remote)
    implementation(projects.agent.runtime)
    implementation(projects.engine.speech)
    implementation(projects.engine.tts)
    implementation(projects.core.models)

    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.hilt.lifecycle.viewmodel.compose)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.activity.compose)
}
