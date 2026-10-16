plugins {
    alias(libs.plugins.autobot.android.library)
    alias(libs.plugins.autobot.android.compose)
    alias(libs.plugins.autobot.hilt)
}

android {
    namespace = "dev.wckdboy.autobot.feature.settings"
    // Compose UI tests render screens with Robolectric, one JVM per test class (see :feature:chat).
    testOptions.unitTests.isIncludeAndroidResources = true
    testOptions.unitTests.all { it.forkEvery = 1 }
}

dependencies {
    implementation(projects.core.designsystem)
    implementation(projects.core.data)
    implementation(projects.core.network)
    implementation(projects.core.security)
    implementation(projects.providers.remote)
    implementation(projects.agent.runtime)

    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.hilt.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    testImplementation(libs.robolectric)
}
