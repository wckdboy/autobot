import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.dsl.LibraryExtension
import dev.wckdboy.autobot.buildlogic.lib
import dev.wckdboy.autobot.buildlogic.libs
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.PathSensitivity
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies
import org.jetbrains.kotlin.compose.compiler.gradle.ComposeCompilerGradlePluginExtension

/**
 * `autobot.android.compose`: enables Jetpack Compose (Kotlin Compose compiler plugin + BOM)
 * on a module that already applies `autobot.android.application` or `autobot.android.library`.
 */
class AndroidComposeConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("org.jetbrains.kotlin.plugin.compose")

            // Shared (KMP) session types are immutable data; let Compose skip on them.
            val stability = rootProject.layout.projectDirectory.file("compose-stability.conf")
            val reports = providers.gradleProperty("autobot.composeReports").isPresent
            extensions.configure<ComposeCompilerGradlePluginExtension> {
                stabilityConfigurationFiles.add(stability)
                // `-Pautobot.composeReports`: which composables skip and which parameters are
                // unstable, per module under build/compose-reports (looking for recomposition work).
                if (reports) {
                    reportsDestination.set(layout.buildDirectory.dir("compose-reports"))
                    metricsDestination.set(layout.buildDirectory.dir("compose-reports"))
                }
            }
            // AGP's built-in Kotlin compile tasks do not see these compiler plugin options as
            // inputs: editing compose-stability.conf (or asking for reports) left modules up to
            // date, still compiled with the old stability. Declare both explicitly.
            tasks.matching { it.name.startsWith("compile") && it.name.endsWith("Kotlin") }.configureEach {
                inputs.file(stability).withPropertyName("composeStabilityConfiguration").withPathSensitivity(PathSensitivity.NONE)
                inputs.property("composeReports", reports)
            }

            pluginManager.withPlugin("com.android.application") {
                extensions.configure<ApplicationExtension> { buildFeatures { compose = true } }
            }
            pluginManager.withPlugin("com.android.library") {
                extensions.configure<LibraryExtension> { buildFeatures { compose = true } }
            }

            dependencies {
                val bom = platform(libs.lib("androidx-compose-bom"))
                add("implementation", bom)
                add("implementation", libs.lib("androidx-compose-ui"))
                add("implementation", libs.lib("androidx-compose-ui-graphics"))
                add("implementation", libs.lib("androidx-compose-foundation"))
                add("implementation", libs.lib("androidx-compose-material3"))
                add("implementation", libs.lib("androidx-compose-material-icons-core"))
                add("implementation", libs.lib("androidx-compose-ui-tooling-preview"))
                add("debugImplementation", libs.lib("androidx-compose-ui-tooling"))
                // Compose UI tests run on the JVM (Robolectric) in `testDebugUnitTest`.
                add("testImplementation", bom)
                add("testImplementation", libs.lib("androidx-compose-ui-test-junit4"))
                add("testImplementation", libs.lib("androidx-compose-ui-test-manifest"))
            }
        }
    }
}
