// Root build script.
//
// The convention plugins from `build-logic` are declared here with `apply false` so that the
// Android, Kotlin (compose / serialization), KSP, Hilt and Room Gradle plugins they bring in are
// loaded once into the root class loader and shared by every module. Modules then apply
// third-party plugins by id only (e.g. `id("org.jetbrains.kotlin.plugin.serialization")`).
plugins {
    alias(libs.plugins.autobot.android.application) apply false
    alias(libs.plugins.autobot.android.library) apply false
    alias(libs.plugins.autobot.android.compose) apply false
    alias(libs.plugins.autobot.hilt) apply false
}
