// Model manager: device profile, Hugging Face / Civitai catalogs and accounts, curated catalog,
// resumable SHA-256-verified downloads (through the network kill switch) and the installed
// model registry used by the on-device engines.
plugins {
    alias(libs.plugins.autobot.android.library)
    alias(libs.plugins.autobot.hilt)
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "dev.wckdboy.autobot.core.models"
    // The downloader runs end to end under Robolectric (notifications need the resources).
    testOptions.unitTests.isIncludeAndroidResources = true
}

dependencies {
    api(projects.core.network)
    implementation(projects.core.data)
    implementation(projects.core.security)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)
    api(libs.kotlinx.serialization.json)

    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.room.runtime)
}
