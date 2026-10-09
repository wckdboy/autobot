import com.android.build.api.dsl.ApplicationExtension
import dev.wckdboy.autobot.buildlogic.AutobotJavaVersion
import dev.wckdboy.autobot.buildlogic.intVersion
import dev.wckdboy.autobot.buildlogic.lib
import dev.wckdboy.autobot.buildlogic.libs
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies

/**
 * `autobot.android.application`: Android application with AGP built-in Kotlin, arm64-only,
 * R8 full mode + resource shrinking for release and reproducible-build friendly packaging
 * (no dependency metadata blob, no VCS info).
 */
class AndroidApplicationConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("com.android.application")

            extensions.configure<ApplicationExtension> {
                compileSdk {
                    version = release(libs.intVersion("compileSdk"))
                }
                defaultConfig {
                    minSdk = libs.intVersion("minSdk")
                    targetSdk = libs.intVersion("targetSdk")
                    ndk {
                        abiFilters += "arm64-v8a"
                    }
                }
                compileOptions {
                    sourceCompatibility = AutobotJavaVersion
                    targetCompatibility = AutobotJavaVersion
                }
                buildTypes {
                    getByName("release") {
                        isMinifyEnabled = true
                        isShrinkResources = true
                        proguardFiles(
                            getDefaultProguardFile("proguard-android-optimize.txt"),
                            "proguard-rules.pro",
                        )
                        vcsInfo {
                            include = false
                        }
                    }
                }
                // Reproducible builds: do not embed the (signed, Google-encrypted) dependency blob.
                dependenciesInfo {
                    includeInApk = false
                    includeInBundle = false
                }
                packaging {
                    resources {
                        excludes += setOf(
                            "/META-INF/{AL2.0,LGPL2.1}",
                            "/META-INF/*.version",
                            "/META-INF/version-control-info.textproto",
                            "/kotlin-tooling-metadata.json",
                            "DebugProbesKt.bin",
                        )
                    }
                }
                testOptions {
                    unitTests.isReturnDefaultValues = true
                }
            }

            dependencies {
                add("testImplementation", libs.lib("junit"))
                add("testImplementation", libs.lib("kotlinx-coroutines-test"))
            }
        }
    }
}
