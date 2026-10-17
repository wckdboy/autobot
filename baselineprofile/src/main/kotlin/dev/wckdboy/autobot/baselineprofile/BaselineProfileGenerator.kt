package dev.wckdboy.autobot.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Records the classes and methods cold start and the first minutes of use run, so release builds
 * ship them precompiled (`baseline-prof.txt`). Run with `./gradlew :app:generateFossReleaseBaselineProfile`
 * on an API 33+ emulator or phone; the text file it writes is committed.
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {
    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    fun startupAndTabs() = rule.collect(packageName = targetPackage, includeInStartupProfile = true) {
        startAndWait()
        skipWelcome()
        visitTabs()
    }
}
