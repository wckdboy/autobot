package dev.wckdboy.autobot.core.security

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** "Lock at once" must not lock behind the photo picker, but still locks a real absence. */
class AppLockTimeoutTest {
    @Test
    fun lockAtOnceAllowsAShortPickerTrip() {
        assertFalse(AppLockManager.shouldLock(awayMillis = 20_000, timeoutMillis = 0, fromPicker = true))
        assertTrue(AppLockManager.shouldLock(awayMillis = AppLockManager.PICKER_GRACE_MILLIS, timeoutMillis = 0, fromPicker = true))
        assertTrue(AppLockManager.shouldLock(awayMillis = 1, timeoutMillis = 0, fromPicker = false))
    }

    @Test
    fun aSetTimeoutAppliesToPickerTripsToo() {
        assertFalse(AppLockManager.shouldLock(awayMillis = 30_000, timeoutMillis = 60_000, fromPicker = true))
        assertTrue(AppLockManager.shouldLock(awayMillis = 61_000, timeoutMillis = 60_000, fromPicker = true))
    }
}
