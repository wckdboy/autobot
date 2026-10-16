package dev.wckdboy.autobot.buildlogic

import org.gradle.api.JavaVersion
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalog
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.tasks.testing.Test
import java.time.Duration
import org.gradle.kotlin.dsl.getByType

/** The shared `libs` version catalog. */
val Project.libs: VersionCatalog
    get() = extensions.getByType<VersionCatalogsExtension>().named("libs")

fun VersionCatalog.lib(alias: String) = findLibrary(alias).get()

fun VersionCatalog.intVersion(alias: String): Int = findVersion(alias).get().requiredVersion.toInt()

/** Java/Kotlin bytecode level for every module. Built-in Kotlin follows the Java target. */
val AutobotJavaVersion: JavaVersion = JavaVersion.VERSION_17

/**
 * No unit test task may hang a build: a stuck test (a frame loop that never idles, an awaited
 * reply that never comes) fails the task after [TEST_TASK_TIMEOUT] instead of running for hours.
 */
fun Project.boundTestTasks() {
    tasks.withType(Test::class.java).configureEach { timeout.set(TEST_TASK_TIMEOUT) }
}

val TEST_TASK_TIMEOUT: Duration = Duration.ofMinutes(20)
