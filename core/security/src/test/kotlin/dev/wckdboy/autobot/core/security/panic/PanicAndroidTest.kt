package dev.wckdboy.autobot.core.security.panic

import dev.wckdboy.autobot.shared.panic.LockRecord
import dev.wckdboy.autobot.shared.panic.PinCrypto
import dev.wckdboy.autobot.shared.panic.UnlockOutcome
import dev.wckdboy.autobot.shared.panic.UnlockPolicy
import dev.wckdboy.autobot.shared.panic.WipeLanding
import dev.wckdboy.autobot.shared.panic.WipePlan
import dev.wckdboy.autobot.shared.panic.WipeRunner
import dev.wckdboy.autobot.shared.panic.WipeStep
import java.io.File
import java.nio.file.Files
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The Android half of panic protection on plain files: the lock store, the PIN lock with its
 * counter, and whole wipes (and interrupted ones) run on a temporary folder that stands in for
 * the app's data folder. Nothing here touches a real app folder or the Keystore.
 */
class PanicAndroidTest {
    private lateinit var root: File
    private lateinit var dataDir: File
    private lateinit var store: PinLockStore

    @Before
    fun setUp() {
        root = Files.createTempDirectory("panic-test").toFile()
        dataDir = File(root, "data").apply { mkdirs() }
        store = PinLockStore(File(dataDir, PinLockStore.DIR))
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    /** A software stand-in for the Keystore HMAC key. */
    private class SoftKey(private var bytes: ByteArray = ByteArray(32) { it.toByte() }) : PinMacKey {
        var destroyed = false
        override fun mac(data: ByteArray): ByteArray = Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(bytes, "HmacSHA256"))
            doFinal(data)
        }

        override fun destroy() {
            destroyed = true
            bytes = ByteArray(32) { 0x55 }
        }
    }

    /** Fast derivation for counter tests (PBKDF2 at full strength is covered once below). */
    private class FastCrypto : PinCrypto {
        private var n = 0
        override fun newSalt(): String = "salt${n++}"
        override fun derive(pin: String, salt: String, iterations: Int): String = "$salt:$pin".hashCode().toString()
    }

    private fun lock(now: () -> Long = { 1_000_000L }) =
        PinLock(store, FastCrypto(), clock = now, io = Dispatchers.Unconfined, compute = Dispatchers.Unconfined)

    // Store

    @Test
    fun storeRoundTripsAndDeletesAnEmptyRecord() {
        assertEquals(LockRecord(), store.read())
        val record = LockRecord(wipeAfter = 0, failures = 3, lastFailureAt = 42)
        store.write(record)
        assertEquals(record, store.read())
        assertFalse(File(store.directory, "lock.json.tmp").exists())
        store.write(LockRecord())
        assertFalse(File(store.directory, "lock.json").exists())
    }

    @Test
    fun storeReadsAGarbledRecordAsEmptyAndAGarbledJournalAsAWipe() {
        store.directory.mkdirs()
        File(store.directory, "lock.json").writeText("{not json")
        assertEquals(LockRecord(), store.read())
        assertNull(store.journal())
        File(store.directory, "wipe.json").writeText("garbage")
        val journal = store.journal()
        assertNotNull(journal)
        assertEquals(WipeLanding.FIRST_RUN, journal!!.landing)
        assertFalse(journal.keepModels)
    }

    // Device crypto

    @Test
    fun deviceCryptoDependsOnTheDeviceKey() {
        val key = SoftKey()
        val crypto = DevicePinCrypto(key)
        val salt = crypto.newSalt()
        val tag = crypto.derive("2468", salt, 1_000)
        assertEquals(tag, crypto.derive("2468", salt, 1_000))
        assertNotEquals(tag, crypto.derive("2469", salt, 1_000))
        assertNotEquals(tag, crypto.derive("2468", crypto.newSalt(), 1_000))
        key.destroy()
        assertNotEquals("a destroyed key opens nothing", tag, crypto.derive("2468", salt, 1_000))
    }

    @Test
    fun deviceCryptoVerifierAtFullStrength() = runTest {
        val pin = PinLock(store, DevicePinCrypto(SoftKey()), io = Dispatchers.Unconfined, compute = Dispatchers.Unconfined)
        assertNull(pin.setPin("2468", "2468"))
        assertEquals(UnlockPolicy.WipeChoices.size, 3)
        assertTrue(pin.verify("2468"))
        assertFalse(pin.verify("1357"))
        assertFalse("the file holds no PIN", File(store.directory, "lock.json").readText().contains("2468"))
    }

    // PIN lock

    @Test
    fun pinSettingsFollowTheRules() = runTest {
        val pin = lock()
        assertEquals("Use 4 to 12 digits", pin.setPin("12", "12"))
        assertEquals("The PINs do not match", pin.setPin("2468", "2469"))
        assertNull(pin.setPin("2468", "2468"))
        assertTrue(pin.hasPin)
        assertEquals("A PIN is already set", pin.setPin("1357", "1357"))
        assertEquals(PinLock.WRONG_PIN, pin.setWipeAfter(10, "0000"))
        assertNull(pin.setWipeAfter(10, "2468"))
        assertEquals("The duress PIN must differ from your PIN", pin.setDuress("2468", "2468", "2468"))
        assertNull(pin.setDuress("2468", "9753", "9753"))
        assertEquals("Your PIN must differ from the duress PIN", pin.changePin("2468", "9753", "9753"))
        assertNull(pin.changePin("2468", "1357", "1357"))
        assertTrue(pin.verify("1357"))
        // Persisted: a new lock on the same folder sees the same settings.
        val again = lock()
        assertTrue(again.hasPin)
        assertEquals(10, again.record.value.wipeAfter)
        assertNotNull(again.record.value.duress)
        // Removing the PIN removes everything that depends on it.
        assertEquals(PinLock.WRONG_PIN, again.removePin("2468"))
        assertNull(again.removePin("1357"))
        assertEquals(LockRecord(), store.read())
    }

    @Test
    fun attemptsAreCountedBeforeTheCheckAndBackOff() = runTest {
        var now = 1_000_000L
        val pin = lock { now }
        pin.setPin("2468", "2468")
        repeat(4) { i ->
            val result = pin.attempt("0000") as PinAttempt.Done
            assertTrue(result.outcome is UnlockOutcome.Failed)
            assertEquals(i + 1, store.read().failures)
        }
        // The fourth failure starts a 30 s wait: the next attempt is refused and not counted.
        val refused = pin.attempt("2468")
        assertTrue(refused is PinAttempt.Refused)
        assertEquals(4, store.read().failures)
        assertFalse("no biometrics after a wrong PIN", store.read().biometricsAllowed)
        now += 30_000
        val ok = pin.attempt("2468") as PinAttempt.Done
        assertTrue(ok.outcome is UnlockOutcome.Unlocked)
        assertEquals(0, store.read().failures)
        assertTrue(store.read().biometricsAllowed)
    }

    @Test
    fun aKilledAttemptStaysCounted() = runTest {
        val pin = lock()
        pin.setPin("2468", "2468")
        // Simulate a kill between begin and resolve: the begun record is what is on disk.
        val begun = UnlockPolicy.begin(store.read(), 1_000_000L)!!
        store.write(begun)
        assertEquals(1, lock().status().failures)
    }

    @Test
    fun theLastAllowedFailureAndTheDuressPinAskForAWipe() = runTest {
        var now = 1_000_000L
        val pin = lock { now }
        pin.setPin("2468", "2468")
        pin.setWipeAfter(5, "2468")
        pin.setDuress("2468", "9753", "9753")
        val duress = pin.attempt("9753") as PinAttempt.Done
        assertTrue(duress.outcome is UnlockOutcome.Duress)
        var last: UnlockOutcome? = null
        repeat(5) {
            now += 31 * 60_000L
            last = (pin.attempt("0000") as PinAttempt.Done).outcome
        }
        assertTrue(last is UnlockOutcome.Wipe)
    }

    // Wipes

    private class Keys {
        var destroyed = 0
    }

    private fun seed(files: File) {
        File(dataDir, "databases").apply { mkdirs() }.resolve("autobot.db").writeText("db")
        File(dataDir, "shared_prefs").apply { mkdirs() }.resolve("p.xml").writeText("x")
        File(dataDir, "cache").apply { mkdirs() }.resolve("c").writeText("c")
        File(dataDir, "no_backup").apply { mkdirs() }.resolve("n").writeText("n")
        File(files, "gallery").apply { mkdirs() }.resolve("img.enc").writeText("img")
        File(files, "workspace/a").apply { mkdirs() }.resolve("notes.md").writeText("notes")
        File(files, "datastore").apply { mkdirs() }.resolve("settings.preferences_pb").writeText("s")
        File(files, "models/qwen").apply { mkdirs() }.resolve("model.gguf").writeText("weights")
    }

    private fun host(files: WipeFiles, keys: Keys, pinKey: SoftKey, failingDelete: Boolean = false) = FileWipeHost(
        files = files,
        store = store,
        pinKey = pinKey,
        destroyKeys = { keys.destroyed++ },
        deleteDatabases = { if (failingDelete) error("cut off") },
    )

    @Test
    fun aFirstRunWipeDeletesEverythingAndTheModelsWithIt() = runTest {
        val filesDir = File(dataDir, "files")
        val external = File(root, "external").apply { mkdirs() }.also { it.resolve("e").writeText("e") }
        seed(filesDir)
        store.write(LockRecord(failures = 5))
        val files = WipeFiles(dataDir, filesDir, listOf(external))
        val keys = Keys()
        val pinKey = SoftKey()
        val ran = WipeRunner(host(files, keys, pinKey)).run(WipePlan.journal(WipeLanding.FIRST_RUN, keepModels = false), resumed = false)
        assertEquals(WipePlan.Steps, ran)
        assertEquals(1, keys.destroyed)
        assertTrue(pinKey.destroyed)
        assertEquals(emptyList<File>(), files.leftovers(keepModels = false))
        assertEquals(LockRecord(), store.read())
        assertNull(store.journal())
        assertEquals(0, files.failed)
        assertFalse(external.exists())
    }

    @Test
    fun aQuietWipeKeepsModelsAndLandsOnTheDuressPin() = runTest {
        val filesDir = File(dataDir, "files")
        seed(filesDir)
        val pin = lock()
        pin.setPin("2468", "2468")
        pin.setDuress("2468", "9753", "9753")
        val duress = store.read().duress
        val files = WipeFiles(dataDir, filesDir)
        val pinKey = SoftKey()
        val journal = WipePlan.journal(WipeLanding.QUIET, keepModels = true, keptModels = "[{\"id\":\"qwen\"}]", carriedPin = duress)
        WipeRunner(host(files, Keys(), pinKey)).run(journal, resumed = false)
        assertEquals(emptyList<File>(), files.leftovers(keepModels = true))
        assertEquals("weights", File(filesDir, "models/qwen/model.gguf").readText())
        assertEquals("[{\"id\":\"qwen\"}]", File(filesDir, "models/${WipeFiles.KEPT_REGISTRY}").readText())
        assertFalse("the duress PIN keeps its key", pinKey.destroyed)
        val landed = store.read()
        assertEquals(duress, landed.pin)
        assertNull(landed.duress)
        assertTrue(landed.quietStart)
        assertEquals(0, landed.wipeAfter)
    }

    @Test
    fun aWipeCutOffAfterTheKeysFinishesOnTheNextStart() = runTest {
        val filesDir = File(dataDir, "files")
        seed(filesDir)
        val files = WipeFiles(dataDir, filesDir)
        val keys = Keys()
        val journal = WipePlan.journal(WipeLanding.FIRST_RUN, keepModels = true, keptModels = "[]")
        // What the lock screen does: commit, destroy keys, restart.
        val first = WipeRunner(host(files, keys, SoftKey())).run(journal, resumed = false, through = WipeStep.DESTROY_KEYS)
        assertEquals(listOf(WipeStep.COMMIT, WipeStep.DESTROY_KEYS), first)
        assertEquals(WipeStep.DESTROY_KEYS, store.journal()!!.done)
        assertTrue(File(filesDir, "gallery/img.enc").exists())
        // The next start.
        val pending = store.journal()!!
        val second = WipeRunner(host(files, keys, SoftKey())).run(pending, resumed = true)
        assertFalse(WipeStep.STOP_WORK in second)
        assertEquals(2, keys.destroyed)
        assertNull(store.journal())
        assertEquals(emptyList<File>(), files.leftovers(keepModels = true))
        assertTrue(File(filesDir, "models/qwen/model.gguf").isFile)
    }

    @Test
    fun aWipeKilledMidDeleteIsFinishedAndParkedModelsSurvive() = runTest {
        val filesDir = File(dataDir, "files")
        seed(filesDir)
        val files = WipeFiles(dataDir, filesDir)
        val journal = WipePlan.journal(WipeLanding.FIRST_RUN, keepModels = true, keptModels = "[]")
        WipeRunner(host(files, Keys(), SoftKey())).run(journal, resumed = false, through = WipeStep.PARK_MODELS)
        assertFalse(File(filesDir, "models").exists())
        // A half-finished delete: some items already in the tomb.
        File(dataDir, "panic/tomb").mkdirs()
        File(dataDir, "databases").renameTo(File(dataDir, "panic/tomb/0-databases"))
        WipeRunner(host(files, Keys(), SoftKey())).run(store.journal()!!, resumed = true)
        assertEquals(emptyList<File>(), files.leftovers(keepModels = true))
        assertFalse(File(dataDir, "panic/tomb").exists())
        assertTrue(File(filesDir, "models/qwen/model.gguf").isFile)
    }

    @Test
    fun oneFailingStepDoesNotStopTheRest() = runTest {
        val filesDir = File(dataDir, "files")
        seed(filesDir)
        val files = WipeFiles(dataDir, filesDir)
        WipeRunner(host(files, Keys(), SoftKey(), failingDelete = true))
            .run(WipePlan.journal(WipeLanding.FIRST_RUN, keepModels = false), resumed = false)
        assertEquals(emptyList<File>(), files.leftovers(keepModels = false))
        assertNull(store.journal())
    }

    @Test
    fun theWipeNeverTouchesFilesOutsideItsFolders() = runTest {
        val outside = File(root, "elsewhere").apply { mkdirs() }.resolve("keep.txt").apply { writeText("mine") }
        val filesDir = File(dataDir, "files")
        seed(filesDir)
        val files = WipeFiles(dataDir, filesDir)
        WipeRunner(host(files, Keys(), SoftKey())).run(WipePlan.journal(WipeLanding.FIRST_RUN, keepModels = false), resumed = false)
        assertEquals("mine", outside.readText())
    }
}
