import dev.wckdboy.autobot.buildlogic.AutobotJavaVersion

// Baseline Profile generator and startup benchmarks for :app (on a device or emulator, API 33+).
//
//   ./gradlew :app:generateFossReleaseBaselineProfile    regenerates
//       app/src/fossRelease/generated/baselineProfiles/baseline-prof.txt (committed);
//   ./gradlew :baselineprofile:connectedFossBenchmarkReleaseAndroidTest
//       measures cold start with and without that profile.
//
// Nothing here runs in a normal build: release builds only read the committed text file, so the
// APK stays reproducible without a device.
plugins {
    id("com.android.test")
    id("androidx.baselineprofile")
}

android {
    namespace = "dev.wckdboy.autobot.baselineprofile"
    compileSdk {
        version = release(libs.versions.compileSdk.get().toInt())
    }

    defaultConfig {
        // Profile generation needs API 33+ without root.
        minSdk = 34
        targetSdk = libs.versions.targetSdk.get().toInt()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = AutobotJavaVersion
        targetCompatibility = AutobotJavaVersion
    }

    flavorDimensions += "distribution"
    productFlavors {
        create("foss") { dimension = "distribution" }
        create("playServices") { dimension = "distribution" }
    }

    targetProjectPath = ":app"
}

baselineProfile {
    // The emulator or phone attached over adb; no Gradle-managed device is downloaded.
    useConnectedDevices = true
}

dependencies {
    implementation(libs.androidx.test.ext.junit)
    implementation(libs.androidx.test.uiautomator)
    implementation(libs.androidx.benchmark.macro.junit4)
}

