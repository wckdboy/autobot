package dev.wckdboy.autobot.shared.cascade

import dev.wckdboy.autobot.agent.core.llm.ModelRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The cascade's pure parts: policy resolution, routing, the judge and the risk classifier. */
class CascadeRulesTest {

    // ---------------------------------------------------------------------------------------
    // Policy

    @Test
    fun policiesParseLenientlyAndChatsFollowSettingsUnlessTheyChose() {
        assertEquals(CascadePolicy.ASK, CascadePolicy.parse(" ask "))
        assertNull(CascadePolicy.parse("always"))
        assertNull(CascadePolicy.parse(null))
        assertEquals(CascadePolicy.OFF, CascadePolicy.DEFAULT)
        assertEquals(CascadePolicy.AUTO, CascadePolicy.resolve(null, CascadePolicy.AUTO))
        assertEquals(CascadePolicy.NEVER, CascadePolicy.resolve("NEVER", CascadePolicy.AUTO))
        // A damaged chat value follows Settings.
        assertEquals(CascadePolicy.ASK, CascadePolicy.resolve("sometimes", CascadePolicy.ASK))
        // Picking what Settings says stores nothing: the chat follows Settings again.
        assertNull(CascadePolicy.override(CascadePolicy.ASK, CascadePolicy.ASK))
        assertEquals("AUTO", CascadePolicy.override(CascadePolicy.AUTO, CascadePolicy.OFF))
        assertEquals("OFF", CascadePolicy.override(CascadePolicy.OFF, CascadePolicy.ASK))
    }

    @Test
    fun theCascadeRoutesOnlyPlainCloudChats() {
        fun route(policy: CascadePolicy, cloud: Boolean = true, tools: Boolean = false, local: Boolean = true, tokens: Int = 100, window: Int? = 4096) =
            CascadeRoutes.route(policy, cloud, tools, local, tokens, window)

        assertEquals(CascadeRoute.NotApplicable, route(CascadePolicy.OFF))
        assertEquals(CascadeRoute.NotApplicable, route(CascadePolicy.ASK, cloud = false))
        // Agent runs (tools on) are out of scope for this version.
        assertEquals(CascadeRoute.NotApplicable, route(CascadePolicy.AUTO, tools = true))
        assertEquals(CascadeRoute.Local, route(CascadePolicy.ASK))
        assertEquals(CascadeRoute.Local, route(CascadePolicy.AUTO))
        assertEquals(CascadeRoute.Local, route(CascadePolicy.NEVER))

        // No on-device model: the cloud answers, or "Never" refuses.
        assertEquals(CascadeRoute.Direct(CascadeRoutes.NO_LOCAL_MODEL), route(CascadePolicy.ASK, local = false))
        assertEquals(CascadeRoute.Direct(CascadeRoutes.NO_LOCAL_MODEL), route(CascadePolicy.AUTO, local = false))
        assertTrue(route(CascadePolicy.NEVER, local = false) is CascadeRoute.Blocked)

        // A chat too long for the phone's window goes straight to the cloud; "Never" still tries.
        assertEquals(CascadeRoute.Direct(CascadeRoutes.TOO_LONG), route(CascadePolicy.ASK, tokens = 3500))
        assertEquals(CascadeRoute.Local, route(CascadePolicy.ASK, tokens = 3000))
        assertEquals(CascadeRoute.Local, route(CascadePolicy.NEVER, tokens = 9000))
        assertEquals(CascadeRoute.Local, route(CascadePolicy.ASK, tokens = 9000, window = null))
    }

    @Test
    fun autoEscalatesOnlyWeakAnswersToLowRiskPrompts() {
        val low = CascadeRisk.LOW
        val personal = CascadeRisk(listOf(CascadeRisk.PERSONAL))
        fun act(p: CascadePolicy, v: CascadeVerdict, r: CascadeRisk = low) = CascadeRoutes.action(p, v, r)

        for (p in CascadePolicy.entries) assertEquals(CascadeAction.KEPT, act(p, CascadeVerdict.GOOD))
        assertEquals(CascadeAction.OFFERED, act(CascadePolicy.ASK, CascadeVerdict.WEAK))
        assertEquals(CascadeAction.OFFERED, act(CascadePolicy.ASK, CascadeVerdict.UNSURE))
        assertEquals(CascadeAction.ESCALATED, act(CascadePolicy.AUTO, CascadeVerdict.WEAK))
        // Unsure, or sensitive: Auto asks instead.
        assertEquals(CascadeAction.OFFERED, act(CascadePolicy.AUTO, CascadeVerdict.UNSURE))
        for (reason in listOf(CascadeRisk.PERSONAL, CascadeRisk.HEALTH, CascadeRisk.FINANCE, CascadeRisk.ATTACHMENT, CascadeRisk.MEMORY)) {
            assertEquals(reason, CascadeAction.OFFERED, act(CascadePolicy.AUTO, CascadeVerdict.WEAK, CascadeRisk(listOf(reason))))
        }
        assertEquals(CascadeAction.OFFERED, act(CascadePolicy.ASK, CascadeVerdict.WEAK, personal))
        // "Never" never leaves the phone.
        assertEquals(CascadeAction.KEPT, act(CascadePolicy.NEVER, CascadeVerdict.WEAK))
    }

    // ---------------------------------------------------------------------------------------
    // Judge

    private val judge = CascadeJudge()

    @Test
    fun hardSignalsMakeAnAnswerWeakWhateverTheSelfCheckSays() {
        val cases = mapOf(
            "" to CascadeJudge.EMPTY,
            "   " to CascadeJudge.EMPTY,
            "I'm sorry, but I can't help with that request." to CascadeJudge.REFUSAL,
            "As an AI language model, I cannot provide medical advice." to CascadeJudge.REFUSAL,
            "I don’t know who won the match." to CascadeJudge.DONT_KNOW_CODE,
            "I don't have access to real-time data, so I can't say." to CascadeJudge.DONT_KNOW_CODE,
            "As of my last update in 2023, the record was 9.58 s." to CascadeJudge.DONT_KNOW_CODE,
            "Here is the code:\n```kotlin\nfun main() {" to CascadeJudge.TRUNCATED,
            "The answer is yes. The answer is yes. The answer is yes. The answer is yes." to CascadeJudge.LOOPING,
        )
        for ((answer, code) in cases) {
            val signals = judge.signals("question?", answer)
            assertTrue("$answer → ${signals.hard}", code in signals.hard)
            val result = judge.judge(signals, selfCheck = 5)
            assertEquals(answer, CascadeVerdict.WEAK, result.verdict)
            assertEquals(code, result.reasons.first())
        }
        assertEquals(listOf(CascadeJudge.TRUNCATED), judge.signals("q", "A full sentence", truncated = true).hard)
        assertEquals(listOf(CascadeJudge.FAILED), judge.signals("q", "", failed = true).hard)
        // A reasoning span is not the answer.
        assertEquals(listOf(CascadeJudge.EMPTY), judge.signals("q", "<think>let me see</think>").hard)
    }

    @Test
    fun aGoodAnswerIsKeptAndSoftSignalsLowerTheScore() {
        val plain = judge.signals("What is the capital of France?", "The capital of France is Paris.")
        assertTrue(plain.hard.isEmpty() && plain.soft.isEmpty())
        assertEquals(CascadeVerdict.GOOD, judge.judge(plain, null).verdict) // rules only: the prior
        assertEquals(CascadeVerdict.GOOD, judge.judge(plain, 5).verdict)
        assertEquals(CascadeVerdict.GOOD, judge.judge(plain, 4).verdict)
        assertEquals(CascadeVerdict.UNSURE, judge.judge(plain, 3).verdict)
        val low = judge.judge(plain, 2)
        assertEquals(CascadeVerdict.WEAK, low.verdict)
        assertEquals(listOf(CascadeJudge.SELF_CHECK), low.reasons)
        assertTrue(judge.judge(plain, 5).reasons.isEmpty())

        // Fresh information: unsure without a confident self-check.
        val fresh = judge.signals("What's the weather in Berlin today?", "It is probably sunny and 20 degrees.")
        assertEquals(listOf(CascadeJudge.FRESH), fresh.soft)
        assertEquals(CascadeVerdict.UNSURE, judge.judge(fresh, null).verdict)
        assertEquals(listOf(CascadeJudge.FRESH), judge.judge(fresh, null).reasons)
        assertEquals(CascadeVerdict.GOOD, judge.judge(fresh, 5).verdict)

        val hedge = judge.signals("Who wrote it?", "I'm not sure, but I think it might be Kafka.")
        assertTrue(CascadeJudge.HEDGE in hedge.soft)
        val short = judge.signals("x".repeat(200), "Yes.")
        assertTrue(CascadeJudge.SHORT in short.soft)
        assertEquals(CascadeVerdict.WEAK, judge.judge(judge.signals("Search the web for the latest news, ${"x".repeat(150)}", "Ok."), 3).verdict)
    }

    @Test
    fun theThresholdIsTunable() {
        val plain = judge.signals("Name a prime.", "7 is a prime number.")
        assertEquals(CascadeVerdict.GOOD, CascadeJudge(threshold = 0.5).judge(plain, 3).verdict)
        assertEquals(CascadeVerdict.UNSURE, CascadeJudge(threshold = 0.9).judge(plain, 4).verdict)
        assertEquals(CascadeVerdict.WEAK, CascadeJudge(threshold = 0.9, unsureBand = 0.0).judge(plain, 4).verdict)
        assertEquals(CascadeVerdict.GOOD, CascadeJudge(threshold = 0.0).judge(plain, 1).verdict)
        assertEquals(0.75, judge.judge(plain, 4).score, 1e-9)
    }

    @Test
    fun selfCheckRepliesParseToAGrade() {
        assertEquals(5, CascadeJudge.parseSelfCheck("5"))
        assertEquals(4, CascadeJudge.parseSelfCheck(" 4."))
        assertEquals(2, CascadeJudge.parseSelfCheck("Grade: 2/5"))
        assertEquals(3, CascadeJudge.parseSelfCheck("<think>maybe 5? no</think>3"))
        assertEquals(4, CascadeJudge.parseSelfCheck("Yes, it answers the question."))
        assertEquals(1, CascadeJudge.parseSelfCheck("No."))
        assertNull(CascadeJudge.parseSelfCheck("12"))
        assertNull(CascadeJudge.parseSelfCheck("9"))
        assertNull(CascadeJudge.parseSelfCheck(""))
        assertNull(CascadeJudge.parseSelfCheck("<think>still thinking"))
        assertNull(CascadeJudge.parseSelfCheck("Hmm, hard to say."))
    }

    @Test
    fun theSelfCheckPromptIsShortAndBounded() {
        val messages = judge.selfCheckMessages("q".repeat(10_000), "<think>${"r".repeat(5_000)}</think>" + "a".repeat(10_000))
        assertEquals(listOf(ModelRole.SYSTEM, ModelRole.USER), messages.map { it.role })
        val user = messages[1].text
        assertTrue(user.length < 3_600)
        assertFalse("r".repeat(100) in user) // the reasoning is not graded
        assertTrue(user.endsWith(CascadeJudge.SELF_CHECK_ASK))
        assertTrue(CascadeJudge.SELF_CHECK_MAX_TOKENS <= 8)
    }

    @Test
    fun freshInformationPromptsAreRecognised() {
        for (p in listOf("What's the latest iPhone?", "bitcoin price", "who won the game yesterday", "Search the web for cafés", "Wie ist das Wetter heute?")) {
            assertTrue(p, CascadeJudge.needsFreshInfo(p))
        }
        for (p in listOf("Write a haiku about autumn", "Explain recursion", "What is 12 × 7?")) assertFalse(p, CascadeJudge.needsFreshInfo(p))
    }

    // ---------------------------------------------------------------------------------------
    // Risk

    @Test
    fun personalHealthMoneyAttachmentsAndMemoryAreSensitive() {
        assertFalse(CascadeRiskClassifier.classify("Explain how a hash map works").sensitive)
        assertFalse(CascadeRiskClassifier.classify("Write a haiku about autumn leaves").sensitive)
        assertEquals(listOf(CascadeRisk.PERSONAL), CascadeRiskClassifier.classify("Draft a reply to anna.berg@posteo.de").reasons)
        assertEquals(listOf(CascadeRisk.PERSONAL), CascadeRiskClassifier.classify("Call me at +49 151 23456789 tomorrow").reasons)
        assertTrue(CascadeRisk.HEALTH in CascadeRiskClassifier.classify("What dosage of ibuprofen is safe with my medication?").reasons)
        assertTrue(CascadeRisk.HEALTH in CascadeRiskClassifier.classify("I was diagnosed with diabetes last week").reasons)
        assertTrue(CascadeRisk.HEALTH in CascadeRiskClassifier.classify("Is my blood pressure of 150/95 high?").reasons)
        assertTrue(CascadeRisk.FINANCE in CascadeRiskClassifier.classify("How do I pay off my debt faster?").reasons)
        assertTrue(CascadeRisk.FINANCE in CascadeRiskClassifier.classify("Is my salary fair for a senior dev?").reasons)
        assertEquals(listOf(CascadeRisk.ATTACHMENT), CascadeRiskClassifier.classify("What is in this picture?", images = true).reasons)
        assertEquals(listOf(CascadeRisk.MEMORY), CascadeRiskClassifier.classify("Plan my week", privateContext = true).reasons)
        assertTrue(CascadeRisk.MEMORY in CascadeRiskClassifier.classify("What should I get my sister for her birthday? She loves Zorblax", privateTerms = listOf("zorblax")).reasons)
        // Short memory terms do not match inside other words.
        assertFalse(CascadeRiskClassifier.classify("Explain monads", privateTerms = listOf("mo")).sensitive)
    }

    // ---------------------------------------------------------------------------------------
    // Words

    @Test
    fun bothAppsShareTheWords() {
        assertEquals("Ask DeepSeek V3", CascadeLabels.ask("DeepSeek V3"))
        assertEquals("Ask the cloud", CascadeLabels.ask(""))
        assertEquals("The phone's answer looks weak: it declined.", CascadeLabels.offer("WEAK", listOf(CascadeJudge.REFUSAL)))
        assertEquals("The phone isn't sure about this answer: this needs fresh information.", CascadeLabels.offer("UNSURE", listOf(CascadeJudge.FRESH)))
        assertEquals("phone → cloud · auto · it said it doesn't know", CascadeLabels.followUp("auto", listOf(CascadeJudge.DONT_KNOW_CODE)))
        assertEquals("phone → cloud · you asked", CascadeLabels.followUp("asked", listOf(CascadeJudge.DONT_KNOW_CODE)))
        assertEquals("phone first · ask", CascadePolicy.ASK.chip)
        assertEquals("phone only", CascadePolicy.NEVER.chip)
    }

    @Test
    fun theUsersOwnNodeIsNotTheCloud() {
        val low = CascadeRisk(emptyList())
        val sensitive = CascadeRisk(listOf(CascadeRisk.HEALTH))
        // Applicable on a node, with or without "cloud".
        assertEquals(CascadeRoute.Local, CascadeRoutes.route(CascadePolicy.AUTO, cloudRoute = false, toolsEnabled = false, hasLocalModel = true, owned = true))
        assertEquals(CascadeRoute.NotApplicable, CascadeRoutes.route(CascadePolicy.AUTO, cloudRoute = false, toolsEnabled = true, hasLocalModel = true, owned = true))
        // "Never" does not block or keep the phone from stepping aside for a node.
        assertEquals(CascadeRoute.Direct(CascadeRoutes.NO_LOCAL_MODEL), CascadeRoutes.route(CascadePolicy.NEVER, false, false, hasLocalModel = false, owned = true))
        assertEquals(CascadeRoute.Direct(CascadeRoutes.TOO_LONG), CascadeRoutes.route(CascadePolicy.NEVER, false, false, true, promptTokens = 1000, localWindow = 100, owned = true))
        assertTrue(CascadeRoutes.route(CascadePolicy.NEVER, true, false, hasLocalModel = false) is CascadeRoute.Blocked)
        // Weak answers go on for Auto and Never, sensitive or not; Ask offers; good stays.
        for (risk in listOf(low, sensitive)) {
            assertEquals(CascadeAction.ESCALATED, CascadeRoutes.action(CascadePolicy.AUTO, CascadeVerdict.WEAK, risk, owned = true))
            assertEquals(CascadeAction.ESCALATED, CascadeRoutes.action(CascadePolicy.NEVER, CascadeVerdict.WEAK, risk, owned = true))
        }
        assertEquals(CascadeAction.OFFERED, CascadeRoutes.action(CascadePolicy.ASK, CascadeVerdict.WEAK, low, owned = true))
        assertEquals(CascadeAction.OFFERED, CascadeRoutes.action(CascadePolicy.NEVER, CascadeVerdict.UNSURE, low, owned = true))
        assertEquals(CascadeAction.KEPT, CascadeRoutes.action(CascadePolicy.AUTO, CascadeVerdict.GOOD, low, owned = true))
        assertEquals(CascadeAction.KEPT, CascadeRoutes.action(CascadePolicy.OFF, CascadeVerdict.WEAK, low, owned = true))
        // To the cloud, unchanged.
        assertEquals(CascadeAction.KEPT, CascadeRoutes.action(CascadePolicy.NEVER, CascadeVerdict.WEAK, low))
        assertEquals(CascadeAction.OFFERED, CascadeRoutes.action(CascadePolicy.AUTO, CascadeVerdict.WEAK, sensitive))
        assertEquals("phone → my node · you asked", CascadeLabels.followUp("asked", emptyList(), "node"))
        assertTrue(CascadeLabels.sheetNote("studio", node = true).contains("never sends these on to a cloud model"))
    }
}
