package dev.wckdboy.autobot.core.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The download queue's rules: order, recovery after process death, blocks and the panel. */
class DownloadQueueTest {

    private fun model(id: String, status: ModelStatus, addedAt: Long, error: String? = null, done: Long = 0) = InstalledModel(
        id = id, kind = ModelKind.CHAT, source = ModelSource.HUGGING_FACE, title = id, subtitle = "", format = ModelFormat.GGUF,
        engine = EngineKind.LLAMA, baseModel = null, license = null, nsfw = false, totalBytes = 4_000_000_000, downloadedBytes = done,
        status = status, error = error, manifest = ModelManifest(emptyList()), addedAt = addedAt, paths = emptyMap(),
    )

    @Test
    fun queueRunsOldestFirstAndIsJustTheRegistry() {
        val rows = listOf(
            model("c", ModelStatus.QUEUED, 30),
            model("ready", ModelStatus.READY, 1),
            model("a", ModelStatus.QUEUED, 10),
            model("paused", ModelStatus.PAUSED, 5),
            model("b", ModelStatus.QUEUED, 20),
        )
        assertEquals(listOf("a", "b", "c"), DownloadQueue.queued(rows).map { it.id })
        assertEquals("a", DownloadQueue.next(rows)?.id)
        // The rows read back in any order (a new process) give the same queue: nothing else is kept.
        assertEquals(DownloadQueue.queued(rows), DownloadQueue.queued(rows.reversed()))
        assertNull(DownloadQueue.next(rows.filter { it.status != ModelStatus.QUEUED }))
    }

    @Test
    fun interruptedDownloadQueuesAgainAfterRestartInItsPlace() {
        val rows = listOf(
            model("running", ModelStatus.DOWNLOADING, 10, done = 1_000_000_000),
            model("next", ModelStatus.QUEUED, 20),
            model("paused", ModelStatus.PAUSED, 5),
        )
        val recovered = DownloadQueue.recover(rows)
        assertEquals(listOf("running"), recovered.map { it.id })
        val after = rows.map { m -> if (m in recovered) m.copy(status = ModelStatus.QUEUED) else m }
        // It keeps its place ahead of later models, and a user's pause stays a pause.
        assertEquals(listOf("running", "next"), DownloadQueue.queued(after).map { it.id })
        assertEquals(ModelStatus.PAUSED, after.single { it.id == "paused" }.status)
        assertTrue(DownloadQueue.recover(after).isEmpty())
    }

    @Test
    fun blocksAreReportedInOrder() {
        assertEquals(DownloadBlock.KILL_SWITCH, DownloadQueue.block(DownloadEnvironment(networkOff = true, connected = false)))
        assertEquals(DownloadBlock.NO_NETWORK, DownloadQueue.block(DownloadEnvironment(connected = false)))
        assertEquals(DownloadBlock.WAITING_FOR_WIFI, DownloadQueue.block(DownloadEnvironment(metered = true, wifiOnly = true)))
        assertNull(DownloadQueue.block(DownloadEnvironment(metered = true)))
    }

    @Test
    fun killSwitchPausesResumeAndPausedReasonsAreNamed() {
        val rows = listOf(
            model("k", ModelStatus.PAUSED, 1, DownloadQueue.KILL_SWITCH_ERROR),
            model("s", ModelStatus.PAUSED, 2, "${DownloadQueue.LOW_STORAGE_PREFIX}: needs 4 GB, 1 GB free"),
            model("u", ModelStatus.PAUSED, 3),
        )
        assertEquals(listOf("k"), DownloadQueue.blockedByKillSwitch(rows).map { it.id })
        assertEquals(DownloadBlock.KILL_SWITCH, DownloadQueue.pausedReason(rows[0]))
        assertEquals(DownloadBlock.LOW_STORAGE, DownloadQueue.pausedReason(rows[1]))
        assertNull(DownloadQueue.pausedReason(rows[2]))
    }

    @Test
    fun meteredWarningOnlyForBigDownloadsOnMobileData() {
        val mobile = DownloadEnvironment(metered = true)
        assertTrue(DownloadQueue.warnMetered(2_000_000_000, mobile))
        assertFalse(DownloadQueue.warnMetered(500_000_000, mobile))
        assertFalse(DownloadQueue.warnMetered(2_000_000_000, mobile.copy(wifiOnly = true)))
        assertFalse(DownloadQueue.warnMetered(2_000_000_000, DownloadEnvironment()))
    }

    @Test
    fun etaAndSpeed() {
        assertEquals(10L, DownloadQueue.eta(100, 10))
        assertEquals(11L, DownloadQueue.eta(101, 10))
        assertNull(DownloadQueue.eta(100, 0))
        assertEquals("45 s", DownloadQueue.formatEta(45))
        assertEquals("2 min", DownloadQueue.formatEta(61))
        assertEquals("1 h 05 min", DownloadQueue.formatEta(3900))
        assertEquals(100L, DownloadQueue.smooth(0, 100))
        assertEquals(130L, DownloadQueue.smooth(100, 200))
    }

    @Test
    fun panelShowsActiveQueueAndPausesWithTheBlock() {
        val rows = listOf(
            model("a", ModelStatus.DOWNLOADING, 1),
            model("b", ModelStatus.QUEUED, 2),
            model("c", ModelStatus.PAUSED, 3),
            model("d", ModelStatus.READY, 0),
        )
        val p = DownloadProgress("a", "a", 1_000, 4_000, 100)
        val state = DownloadQueue.state(rows, p, DownloadEnvironment(connected = false))
        assertEquals("a", state.active?.id)
        assertEquals(30L, state.active?.etaSeconds)
        assertEquals(listOf("b"), state.queued.map { it.id })
        assertEquals(listOf("c"), state.paused.map { it.id })
        assertEquals(DownloadBlock.NO_NETWORK, state.block)
        // Nothing to download: no block, and an empty panel.
        val idle = DownloadQueue.state(rows.filter { it.status == ModelStatus.READY }, null, DownloadEnvironment(networkOff = true))
        assertTrue(idle.isEmpty)
        assertNull(idle.block)
    }
}
