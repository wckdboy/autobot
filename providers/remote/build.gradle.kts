plugins {
    alias(libs.plugins.autobot.android.library)
    alias(libs.plugins.autobot.hilt)
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "dev.wckdboy.autobot.providers.remote"
    defaultConfig.testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
}

dependencies {
    api(projects.core.data)
    api(projects.shared)
    api(projects.core.network)
    implementation(projects.core.models)
    implementation(projects.engine.llama)

    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.okhttp.mockwebserver)
    // On-device chat failures end to end: the registry in Room, the engine a fake binder.
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.room.runtime)

    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
}
