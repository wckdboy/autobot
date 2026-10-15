plugins {
    alias(libs.plugins.autobot.android.library)
    alias(libs.plugins.autobot.hilt)
    id("androidx.room")
}

android {
    namespace = "dev.wckdboy.autobot.core.data"
    // Migration tests read the exported schemas (Robolectric, framework SQLite instead of SQLCipher).
    sourceSets { named("test") { assets.directories.add("$projectDir/schemas") } }
    testOptions.unitTests.isIncludeAndroidResources = true
}

room {
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    api(projects.core.security)
    api(projects.core.network)
    api(projects.shared)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.sqlcipher.android)
    implementation(libs.androidx.sqlite)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.room.testing)
}
