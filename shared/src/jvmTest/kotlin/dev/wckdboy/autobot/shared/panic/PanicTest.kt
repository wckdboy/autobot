package dev.wckdboy.autobot.shared.panic

import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A software stand-in for the device PIN key (the platforms keep it in the Keystore / Keychain). */
internal class SoftPinCrypto(private val deviceKey: ByteArray = ByteArray(32).also(SecureRandom()::nextBytes)) : PinCrypto {
    var derivations = 0
    override fun newSalt(): String = Base64.getEncoder().encodeToString(ByteArray(16).also(SecureRandom()::nextBytes))
    override fun derive(pin: String, salt: String, iterations: Int): String {
        derivations++
        val spec = PBEKeySpec(pin.toCharArray(), Base64.getDecoder().decode(salt), iterations, 256)
        val stretched = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(deviceKey, "HmacSHA256")) }
        return Base64.getEncoder().encodeToString(mac.doFinal(stretched))
    }
}

class PinVaultTest {
    private val crypto = SoftPinCrypto()
    private val vault = PinVault(crypto)

    @Test
    fun verifierMatchesOnlyItsPin() {
        val v = vault.create("482913")
        assertTrue(vault.matches("482913", v))
        assertFalse(vault.matches("482914", v))
        assertFalse(vault.matches("48291", v))
        assertFalse(vault.matches("", v))
    }

    @Test
    fun saltsDifferSoEqualPinsGiveDifferentVerifiers() {
        val a = vault.create("482913")
        val b = vault.create("482913")
        assertNotEquals(a.salt, b.salt)
        assertNotEquals(a.tag, b.tag)
    }

    @Test
    fun anotherDeviceKeyCannotCheckAVerifier() {
        val v = vault.create("482913")
        assertFalse(PinVault(SoftPinCrypto()).matches("482913", v))
    }

    @Test
    fun matchTellsRealDuressAndNone() {
        val record = LockRecord(pin = vault.create("482913"), duress = vault.create("1357"))
        assertEquals(PinMatch.REAL, vault.match("482913", record))
        assertEquals(PinMatch.DURESS, vault.match("1357", record))
        assertEquals(PinMatch.NONE, vault.match("0000", record))
    }

    @Test
    fun everyCheckDerivesTwiceWhateverTheOutcome() {
        val withDuress = LockRecord(pin = vault.create("482913"), duress = vault.create("1357"))
        val without = LockRecord(pin = vault.create("482913"))
        for ((record, pin) in listOf(withDuress to "482913", withDuress to "1357", withDuress to "9999", without to "482913", without to "9999")) {
            crypto.derivations = 0
            vault.match(pin, record)
            assertEquals("$pin", 2, crypto.derivations)
        }
    }

    @Test
    fun noPinNeverMatches() {
        assertEquals(PinMatch.NONE, vault.match("1234", LockRecord()))
    }

    @Test
    fun constantTimeEquals() {
        assertTrue(PinVault.constantTimeEquals("abc", "abc"))
        assertFalse(PinVault.constantTimeEquals("abc", "abd"))
        assertFalse(PinVault.constantTimeEquals("abc", "abcd"))
        assertFalse(PinVault.constantTimeEquals("", "a"))
        assertTrue(PinVault.constantTimeEquals("", ""))
    }

    @Test
    fun pinRules() {
        assertNull(PinRules.problem("4829"))
        assertNull(PinRules.problem("482913482913"))
        assertNotNull(PinRules.problem("482"))
        assertNotNull(PinRules.problem("4829134829131"))
        assertNotNull(PinRules.problem("48a9"))
        assertNotNull(PinRules.problem("1111"))
        assertNotNull(PinRules.duressProblem("4829", matchesReal = true))
        assertNull(PinRules.duressProblem("4829", matchesReal = false))
        assertNotNull(PinRules.confirmProblem("4829", "4828"))
        assertNull(PinRules.confirmProblem("4829", "4829"))
    }
}

class UnlockPolicyTest {
    private val pin = PinVerifier("salt", "tag")
    private val t0 = 1_800_000_000_000L

    /** Fails [times] attempts from [record], each at the earliest allowed moment. Returns the record and the clock. */
    private fun fail(record: LockRecord, times: Int, start: Long = t0): Pair<LockRecord, Long> {
        var r = record
        var now = start
        repeat(times) {
            now += UnlockPolicy.waitMillis(r, now)
            val begun = UnlockPolicy.begin(r, now)!!
            r = UnlockPolicy.resolve(begun, PinMatch.NONE, now).record
        }
        return r to now
    }

    @Test
    fun anAttemptCountsBeforeThePinIsChecked() {
        val begun = UnlockPolicy.begin(LockRecord(pin = pin), t0)!!
        assertEquals(1, begun.failures)
        assertEquals(t0, begun.lastFailureAt)
        // The app is killed here: the persisted record still counts the attempt.
        val afterRestart = LockRecord.decode(LockRecord.encode(begun))
        assertEquals(1, afterRestart.failures)
    }

    @Test
    fun theRealPinResetsTheCounter() {
        val (r, now) = fail(LockRecord(pin = pin), 3)
        val outcome = UnlockPolicy.resolve(UnlockPolicy.begin(r, now)!!, PinMatch.REAL, now)
        assertTrue(outcome is UnlockOutcome.Unlocked)
        assertEquals(0, outcome.record.failures)
        assertEquals(0, outcome.record.lastFailureAt)
    }

    @Test
    fun theDuressPinLooksLikeAnUnlockToTheCounter() {
        val (r, now) = fail(LockRecord(pin = pin, duress = pin, wipeAfter = 5), 2)
        val outcome = UnlockPolicy.resolve(UnlockPolicy.begin(r, now)!!, PinMatch.DURESS, now)
        assertTrue(outcome is UnlockOutcome.Duress)
        assertEquals(0, outcome.record.failures)
    }

    @Test
    fun wipesAtTheLimitAndWarnsTwoBefore() {
        for (limit in UnlockPolicy.WipeChoices) {
            var r = LockRecord(pin = pin, wipeAfter = limit)
            var now = t0
            for (attempt in 1..limit) {
                now += UnlockPolicy.waitMillis(r, now)
                val outcome = UnlockPolicy.resolve(UnlockPolicy.begin(r, now)!!, PinMatch.NONE, now)
                if (attempt < limit) {
                    outcome as UnlockOutcome.Failed
                    assertEquals(limit - attempt, outcome.remaining)
                    assertEquals("limit $limit attempt $attempt", attempt >= limit - 2, outcome.warn)
                } else {
                    assertTrue("limit $limit", outcome is UnlockOutcome.Wipe)
                }
                r = outcome.record
            }
        }
    }

    @Test
    fun withoutAWipeLimitFailuresNeverWipe() {
        var r = LockRecord(pin = pin)
        var now = t0
        repeat(40) {
            now += UnlockPolicy.waitMillis(r, now)
            val outcome = UnlockPolicy.resolve(UnlockPolicy.begin(r, now)!!, PinMatch.NONE, now)
            outcome as UnlockOutcome.Failed
            assertNull(outcome.remaining)
            assertFalse(outcome.warn)
            r = outcome.record
        }
        assertEquals(40, r.failures)
    }

    @Test
    fun killedDuringTheLastAttemptTheNextWrongPinWipesAndTheRightOneUnlocks() {
        val (r, now) = fail(LockRecord(pin = pin, wipeAfter = 5), 4)
        val later = now + UnlockPolicy.waitMillis(r, now)
        val begun = UnlockPolicy.begin(r, later)!! // persisted, then the app is killed
        assertEquals(5, begun.failures)
        val next = later + UnlockPolicy.waitMillis(begun, later)
        val again = UnlockPolicy.begin(begun, next)!!
        assertTrue(UnlockPolicy.resolve(again, PinMatch.NONE, next) is UnlockOutcome.Wipe)
        assertTrue(UnlockPolicy.resolve(again, PinMatch.REAL, next) is UnlockOutcome.Unlocked)
    }

    @Test
    fun backoffGrowsAndRefusesEarlyAttemptsWithoutCountingThem() {
        assertEquals(0, UnlockPolicy.backoffMillis(3))
        assertTrue(UnlockPolicy.backoffMillis(4) > 0)
        for (n in 4..20) assertTrue(UnlockPolicy.backoffMillis(n) >= UnlockPolicy.backoffMillis(n - 1))
        assertEquals(30 * 60_000L, UnlockPolicy.backoffMillis(100))

        val (r, now) = fail(LockRecord(pin = pin), 4)
        val wait = UnlockPolicy.waitMillis(r, now)
        assertTrue(wait > 0)
        assertNull(UnlockPolicy.begin(r, now + wait - 1))
        assertNotNull(UnlockPolicy.begin(r, now + wait))
        // A refused attempt is not counted.
        assertEquals(4, r.failures)
    }

    @Test
    fun theWaitSurvivesARestartAndAClockSetBack() {
        val (r, now) = fail(LockRecord(pin = pin), 5)
        val restored = LockRecord.decode(LockRecord.encode(r))
        assertEquals(UnlockPolicy.waitMillis(r, now), UnlockPolicy.waitMillis(restored, now))
        // Setting the clock back a day does not end the wait; it starts it again.
        assertEquals(UnlockPolicy.backoffMillis(5), UnlockPolicy.waitMillis(restored, now - 86_400_000L))
    }

    @Test
    fun statusForTheLockScreen() {
        val (r, now) = fail(LockRecord(pin = pin, wipeAfter = 10), 8)
        val s = UnlockPolicy.status(r, now)
        assertEquals(8, s.failures)
        assertEquals(2, s.remaining)
        assertTrue(s.warn)
        assertTrue(s.waitMillis > 0)
    }

    @Test
    fun noPinNoAttempt() {
        assertNull(UnlockPolicy.begin(LockRecord(), t0))
    }

    @Test
    fun biometricsOnlyWhileNoWrongPin() {
        assertTrue(LockRecord(pin = pin).biometricsAllowed)
        assertFalse(LockRecord(pin = pin, biometrics = false).biometricsAllowed)
        assertFalse(LockRecord().biometricsAllowed)
        val (r, _) = fail(LockRecord(pin = pin), 1)
        assertFalse(r.biometricsAllowed)
    }

    @Test
    fun wipeThresholdChoices() {
        val r = LockRecord(pin = pin)
        assertEquals(10, UnlockPolicy.withWipeAfter(r, 10).wipeAfter)
        assertEquals(0, UnlockPolicy.withWipeAfter(r, 0).wipeAfter)
        assertTrue(runCatching { UnlockPolicy.withWipeAfter(r, 7) }.isFailure)
        assertTrue(runCatching { UnlockPolicy.withWipeAfter(LockRecord(), 5) }.isFailure)
    }

    @Test
    fun recordRoundTripsAndToleratesGarbage() {
        val r = LockRecord(pin = pin, duress = pin, wipeAfter = 15, wipeModels = true, biometrics = false, failures = 3, lastFailureAt = t0, quietStart = true)
        assertEquals(r, LockRecord.decode(LockRecord.encode(r)))
        assertEquals(LockRecord(), LockRecord.decode(null))
        assertEquals(LockRecord(), LockRecord.decode("{not json"))
        assertFalse(r.toString().contains("tag"))
        assertEquals(LockRecord(biometrics = false), r.withoutPin())
    }
}

class WipePlanTest {

    private class Host : WipeRunner.Host {
        val calls = mutableListOf<String>()
        var journal: WipeJournal? = null
        var failOn: String? = null
        var landing: LockRecord? = null

        private fun call(name: String) {
            calls += name
            if (failOn == name) error("boom")
        }

        override fun saveJournal(journal: WipeJournal) {
            call("save:${journal.done}")
            this.journal = journal
        }
        override fun destroyKeys() = call("keys")
        override suspend fun stopWork() = call("stop")
        override fun parkModels() = call("park")
        override fun deleteData(keepModels: Boolean) = call("delete:$keepModels")
        override fun restore(journal: WipeJournal, landing: LockRecord?) {
            call("restore")
            this.landing = landing
        }
        override fun clearJournal() {
            call("clear")
            journal = null
        }
    }

    @Test
    fun keysGoFirstRightAfterTheCommit() {
        val host = Host()
        runBlocking { WipeRunner(host).run(WipePlan.journal(WipeLanding.FIRST_RUN, keepModels = false), resumed = false) }
        assertEquals(
            listOf("save:COMMIT", "keys", "save:DESTROY_KEYS", "stop", "save:STOP_WORK", "save:PARK_MODELS", "delete:false", "save:DELETE_DATA", "restore", "save:RESTORE", "clear"),
            host.calls,
        )
        assertNull(host.journal)
        assertNull(host.landing)
    }

    @Test
    fun keptModelsAreParkedBeforeTheDeleteAndRestoredAfter() {
        val host = Host()
        runBlocking { WipeRunner(host).run(WipePlan.journal(WipeLanding.FIRST_RUN, keepModels = true, keptModels = "[]"), resumed = false) }
        val i = host.calls::indexOf
        assertTrue(i("keys") < i("park"))
        assertTrue(i("park") < i("delete:true"))
        assertTrue(i("delete:true") < i("restore"))
    }

    @Test
    fun stoppingAfterTheKeysLeavesTheRestForTheNextStart() {
        val host = Host()
        val journal = WipePlan.journal(WipeLanding.QUIET, keepModels = true, keptModels = "[m]", carriedPin = PinVerifier("s", "t"))
        val ran = runBlocking { WipeRunner(host).run(journal, resumed = false, through = WipeStep.DESTROY_KEYS) }
        assertEquals(listOf(WipeStep.COMMIT, WipeStep.DESTROY_KEYS), ran)
        val saved = WipeJournal.decode(WipeJournal.encode(host.journal!!))!!
        assertEquals(WipeStep.DESTROY_KEYS, saved.done)
        assertEquals("[m]", saved.keptModels)

        // Next start: everything idempotent again (keys too), nothing to stop, then the landing.
        val next = Host()
        val resumed = runBlocking { WipeRunner(next).run(saved, resumed = true) }
        assertEquals(listOf(WipeStep.DESTROY_KEYS, WipeStep.PARK_MODELS, WipeStep.DELETE_DATA, WipeStep.RESTORE, WipeStep.FINISH), resumed)
        assertFalse("stop" in next.calls)
        assertEquals(PinVerifier("s", "t"), next.landing!!.pin)
        assertTrue(next.landing!!.quietStart)
        assertEquals(0, next.landing!!.failures)
        assertNull(next.journal)
    }

    @Test
    fun aKillAtAnyStepFinishesOnTheNextStart() {
        for (stop in WipeStep.entries) {
            val host = Host()
            val journal = WipePlan.journal(WipeLanding.FIRST_RUN, keepModels = false)
            runBlocking { WipeRunner(host).run(journal, resumed = false, through = stop) }
            val left = host.journal ?: continue // FINISH ran: nothing to resume
            val next = Host()
            runBlocking { WipeRunner(next).run(WipeJournal.decode(WipeJournal.encode(left))!!, resumed = true) }
            assertEquals("after $stop", listOf("keys", "delete:false", "restore", "clear"), next.calls.filterNot { it.startsWith("save") })
        }
    }

    @Test
    fun aFailingStepDoesNotStopTheOthers() {
        val host = Host().apply { failOn = "delete:false" }
        runBlocking { WipeRunner(host).run(WipePlan.journal(WipeLanding.FIRST_RUN, keepModels = false), resumed = false) }
        assertTrue("restore" in host.calls)
        assertTrue("clear" in host.calls)
    }

    @Test
    fun aFailedCommitStopsBeforeAnythingIsDestroyed() {
        val host = Host().apply { failOn = "save:COMMIT" }
        assertTrue(runCatching { runBlocking { WipeRunner(host).run(WipePlan.journal(WipeLanding.FIRST_RUN, keepModels = false), resumed = false) } }.isFailure)
        assertFalse("keys" in host.calls)
    }

    @Test
    fun targetsKeepModelsOnlyWhenAsked() {
        assertFalse(WipeTarget.MODELS in WipePlan.targets(keepModels = true))
        assertTrue(WipeTarget.MODELS in WipePlan.targets(keepModels = false))
        for (t in listOf(WipeTarget.KEYS, WipeTarget.DATABASE, WipeTarget.VAULT, WipeTarget.GALLERY, WipeTarget.WORKSPACE, WipeTarget.CACHES)) {
            assertTrue(t in WipePlan.targets(keepModels = true))
        }
    }

    @Test
    fun journalRules() {
        assertTrue(runCatching { WipePlan.journal(WipeLanding.FIRST_RUN, false, carriedPin = PinVerifier("s", "t")) }.isFailure)
        assertNull(WipePlan.journal(WipeLanding.FIRST_RUN, keepModels = false, keptModels = "[m]").keptModels)
        assertNull(WipeJournal.decode(null))
        // A journal that cannot be read still finishes the wipe, keeping nothing.
        assertEquals(WipeJournal(WipeLanding.FIRST_RUN, keepModels = false), WipeJournal.decode("garbage"))
        assertNull(WipePlan.landingRecord(WipeJournal(WipeLanding.FIRST_RUN, false)))
        assertFalse(WipePlan.journal(WipeLanding.QUIET, true, "[m]", PinVerifier("s", "t")).toString().contains("t)"))
    }
}

class IncognitoImagesTest {
    @Test
    fun ids() {
        assertTrue(IncognitoImages.isUnsaved(IncognitoImages.ID_PREFIX + "x"))
        assertFalse(IncognitoImages.isUnsaved("4c1f"))
        assertTrue(IncognitoImages.isIncognitoSession("incognito-123"))
        assertTrue(IncognitoImages.isIncognitoSession("incognito-123::task::c1"))
        assertFalse(IncognitoImages.isIncognitoSession("123"))
        assertTrue(IncognitoImages.toolNote(2).contains("not saved"))
    }
}
