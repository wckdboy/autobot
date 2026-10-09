import com.android.build.api.dsl.LibraryExtension
import dev.wckdboy.autobot.buildlogic.AutobotJavaVersion
import dev.wckdboy.autobot.buildlogic.intVersion
import dev.wckdboy.autobot.buildlogic.lib
import dev.wckdboy.autobot.buildlogic.libs
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies

/**
 * `autobot.android.library`: Android library with AGP built-in Kotlin and JVM unit tests.
 * Each library may ship `consumer-rules.pro` keep rules that are merged into the app's R8 run.
 */
class AndroidLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("com.android.library")

            extensions.configure<LibraryExtension> {
                compileSdk {
                    version = release(libs.intVersion("compileSdk"))
                }
                defaultConfig {
                    minSdk = libs.intVersion("minSdk")
                    consumerProguardFiles("consumer-rules.pro")
                }
                compileOptions {
                    sourceCompatibility = AutobotJavaVersion
                    targetCompatibility = AutobotJavaVersion
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
