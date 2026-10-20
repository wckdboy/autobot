package dev.wckdboy.autobot.core.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FirstModelTest {
    private val gb = 1_000_000_000L

    private fun phone(ramGb: Double, freeGb: Double = 64.0, platform: AppPlatform = AppPlatform.ANDROID) =
        DeviceCapabilities(platform, (ramGb * gb).toLong(), (freeGb * gb).toLong())

    @Test
    fun picksTheStrongestModelThatFitsWell() {
        assertEquals("catalog:qwen3-4b-2507-q4_0", FirstModel.pickId(phone(12.0)))
        assertEquals("catalog:qwen3.5-2b-q4_0", FirstModel.pickId(phone(8.0, platform = AppPlatform.IOS)))
        assertEquals("catalog:qwen3.5-2b-q4_0", FirstModel.pickId(phone(6.0)))
        // The emulator's 2 GB: the 0.8B is the one that fits.
        assertEquals("catalog:qwen3.5-0.8b-q4_0", FirstModel.pickId(phone(2.1)))
    }

    @Test
    fun aTightFitBeatsNothing() {
        // 0.8B needs ~0.63 GB working: good under 0.7 × RAM/2, tight under RAM/2.
        assertEquals("catalog:qwen3.5-0.8b-q4_0", FirstModel.pickId(phone(1.5)))
        assertNull(FirstModel.pickId(phone(1.0)))
    }

    @Test
    fun needsRoomOnDisk() {
        assertEquals("catalog:qwen3.5-2b-q4_0", FirstModel.pickId(phone(12.0, freeGb = 3.0)))
        assertNull(FirstModel.pickId(phone(12.0, freeGb = 0.5)))
    }

    @Test
    fun stepsFollowTheDownload() {
        fun step(status: String?, localChat: Boolean = false, hasChats: Boolean = false, picked: Boolean = true) =
            FirstModel.step(picked, status, localChat, hasChats)
        assertEquals(FirstModelStep.OFFER, step(null))
        assertEquals(FirstModelStep.QUEUED, step("QUEUED"))
        assertEquals(FirstModelStep.DOWNLOADING, step("DOWNLOADING"))
        assertEquals(FirstModelStep.PAUSED, step("PAUSED"))
        assertEquals(FirstModelStep.FAILED, step("FAILED"))
        assertEquals(FirstModelStep.READY, step("READY", localChat = true))
        // Ready and chatting: the card has done its job.
        assertEquals(FirstModelStep.HIDDEN, step("READY", localChat = true, hasChats = true))
        // Another local model already chats: no offer.
        assertEquals(FirstModelStep.HIDDEN, step(null, localChat = true))
        assertEquals(FirstModelStep.NONE_FITS, step(null, picked = false))
        assertEquals(FirstModelStep.HIDDEN, step(null, localChat = true, picked = false))
    }

    @Test
    fun words() {
        assertEquals("2.4 GB · fits in 8.0 GB of RAM", FirstModel.sizeLine(2_266L * 1_048_576, 8 * gb))
        assertEquals("1.1 GB of 2.3 GB · 47%", FirstModel.progressLine(FirstModelStep.DOWNLOADING, 1_100_000_000, 2_300_000_000))
        assertEquals("Waiting to start", FirstModel.progressLine(FirstModelStep.QUEUED, 0, 2_300_000_000))
        assertEquals("1.1 GB of 2.3 GB · 47% · paused, resumes where it stopped", FirstModel.progressLine(FirstModelStep.PAUSED, 1_100_000_000, 2_300_000_000))
    }
}
