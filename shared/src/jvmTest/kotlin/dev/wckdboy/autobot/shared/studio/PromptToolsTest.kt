package dev.wckdboy.autobot.shared.studio

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptToolsTest {

    @Test
    fun expandsChoicesLeftMostSlowest() {
        val e = Wildcards.expand("a {red|blue} {cat|dog}")
        assertEquals(listOf("a red cat", "a red dog", "a blue cat", "a blue dog"), e.prompts)
        assertEquals(4L, e.total)
        assertFalse(e.truncated)
    }

    @Test
    fun nestedChoicesExpandInsideTheirOption() {
        val e = Wildcards.expand("{a {big|small} cat|a dog}, oil")
        assertEquals(listOf("a big cat, oil", "a small cat, oil", "a dog, oil"), e.prompts)
        assertEquals(3L, e.total)
    }

    @Test
    fun weightsAreCopiesAndZeroDrops() {
        assertEquals(listOf("red", "red", "red", "blue"), Wildcards.expand("{red:3|blue}").prompts)
        assertEquals(listOf("blue"), Wildcards.expand("{red:0|blue}").prompts)
        // SD emphasis and colons inside text are not wildcard weights.
        assertEquals(listOf("(red:1.2) sky", "blue sky"), Wildcards.expand("{(red:1.2)|blue} sky").prompts)
        assertEquals(listOf("time: 12"), Wildcards.expand("time: 12").prompts)
    }

    @Test
    fun capTruncatesButCountsEverything() {
        val e = Wildcards.expand("{1|2|3|4|5|6|7|8|9|10} {a|b|c|d|e|f|g|h|i|j}", cap = 16)
        assertEquals(16, e.prompts.size)
        assertEquals(100L, e.total)
        assertTrue(e.truncated)
        assertEquals("1 a", e.prompts.first())
        assertEquals("2 f", e.prompts.last())
    }

    @Test
    fun escapesAndUnbalancedBracesAreLiteral() {
        assertEquals(listOf("{x|y}"), Wildcards.expand("\\{x\\|y\\}").prompts)
        assertEquals(listOf("open { brace"), Wildcards.expand("open { brace").prompts)
        assertEquals(listOf("a | b } c"), Wildcards.expand("a | b } c").prompts)
        assertFalse(Wildcards.hasWildcards("no choices {here"))
        assertTrue(Wildcards.hasWildcards("{a|b}"))
    }

    @Test
    fun samplingIsDeterministicAndFollowsWeights() {
        val a = Wildcards.sample("{red:9|blue}", 1000, seed = 7)
        assertEquals(a, Wildcards.sample("{red:9|blue}", 1000, seed = 7))
        val reds = a.count { it == "red" }
        assertTrue("$reds", reds in 850..950)
    }

    @Test
    fun seedsLockWalkAndWrap() {
        assertEquals(listOf(5L, 5L, 5L), Seeds.plan(SeedMode.LOCKED, 5, 3))
        assertEquals(listOf(5L, 6L, 7L), Seeds.plan(SeedMode.WALK, 5, 3))
        assertEquals(listOf(Seeds.MAX, 0L), Seeds.plan(SeedMode.WALK, Seeds.MAX, 2))
        val r = Seeds.plan(SeedMode.RANDOM, 0, 50, Random(1))
        assertTrue(r.all { it in 0..Seeds.MAX })
        assertTrue(r.toSet().size > 45)
    }

    @Test
    fun batchPlannerCombinesWildcardsCountAndSeeds() {
        val base = Recipe(EngineKinds.DIFFUSION, params = mapOf("prompt" to ParamValue.of("a {red|blue} car")), seed = 100)
        val jobs = BatchPlanner.plan(base, batchCount = 2, seedMode = SeedMode.WALK)
        assertEquals(listOf("a red car", "a red car", "a blue car", "a blue car"), jobs.map { it.recipe.string("prompt") })
        assertEquals(listOf(100L, 101L, 102L, 103L), jobs.map { it.recipe.seed })
        assertEquals("a red car · seed 100", jobs.first().label)
        val locked = BatchPlanner.plan(base.copy(params = mapOf("prompt" to ParamValue.of("x"))), batchCount = 3, seedMode = SeedMode.LOCKED)
        assertEquals(listOf(100L, 100L, 100L), locked.map { it.recipe.seed })
        assertEquals("seed 100", locked.first().label)
    }

    @Test
    fun quickRunsKeepAPlainPromptAsOneBatchedCall() {
        assertEquals(listOf(PromptRun("a cat", 4, 7)), PromptRuns.plan("a cat", batch = 4, seed = 7))
        assertEquals(null, PromptRuns.summary("a cat", 4))
        // SD emphasis is not a wildcard.
        assertEquals(1, PromptRuns.plan("(a cat:1.2)", batch = 1, seed = -1).size)
    }

    @Test
    fun quickRunsExpandWildcardsWithTheSameSeedPerPrompt() {
        val runs = PromptRuns.plan("a {red|blue} cat", batch = 2, seed = 42)
        assertEquals(listOf(PromptRun("a red cat", 2, 42), PromptRun("a blue cat", 2, 42)), runs)
        assertEquals("wildcards · 2 prompts × 2 = 4 images", PromptRuns.summary("a {red|blue} cat", 2))
        assertEquals("wildcards · 1 prompt = 1 image", PromptRuns.summary("{a cat}", 1))
        // Random stays random for each call.
        assertTrue(PromptRuns.plan("{a|b}", 1, -1).all { it.seed == -1L })
    }

    @Test
    fun quickRunsAreCappedInImagesNotJustPrompts() {
        val template = "{a|b|c|d|e|f|g|h|i|j} {1|2|3}"
        val runs = PromptRuns.plan(template, batch = 4, seed = 1)
        assertEquals(4, runs.size)
        assertEquals(PromptRuns.MAX_IMAGES, runs.sumOf { it.count })
        assertEquals(PromptRuns.MAX_IMAGES, PromptRuns.images(template, 4))
        assertEquals("wildcards · 4 of 30 prompts × 4 = 16 images", PromptRuns.summary(template, 4))
        // A batch bigger than the cap is cut to it (one call).
        assertEquals(2, PromptRuns.plan("x", batch = 3, seed = 1, maxImages = 2).single().count)
    }

    @Test
    fun quickRunsTrimAndSkipEmptyExpansions() {
        assertEquals(listOf("a cat", "cat"), PromptRuns.plan("{a |}cat", 1, -1).map { it.prompt })
        // Every option weighted out: the prompt runs as typed rather than not at all.
        assertEquals(listOf("{x:0}"), PromptRuns.plan("{x:0}", 1, -1).map { it.prompt })
    }

    @Test
    fun gridVariesTwoParamsRowByRow() {
        val base = Recipe(EngineKinds.DIFFUSION, seed = 1)
        val plan = GridPlanner.plan(
            base,
            GridAxis("steps", listOf(ParamValue.of(10), ParamValue.of(20), ParamValue.of(30)), "Steps"),
            GridAxis("cfg", listOf(ParamValue.of(4.0), ParamValue.of(7.5)), "CFG"),
        )
        assertEquals(6, plan.jobs.size)
        assertEquals(listOf("10", "20", "30"), plan.columns)
        assertEquals(listOf("4", "7.5"), plan.rows)
        assertEquals("Steps=20 · CFG=7.5", plan.jobs[4].label)
        assertEquals(1 to 1, plan.jobs[4].row to plan.jobs[4].column)
        assertEquals(ParamValue.of(20), plan.jobs[4].recipe.params["steps"])
        assertEquals(ParamValue.of(7.5), plan.jobs[4].recipe.params["cfg"])
        assertFalse(plan.truncated)
    }

    @Test
    fun gridCanVarySeedAndModelAndIsCapped() {
        val plan = GridPlanner.plan(
            Recipe(EngineKinds.DIFFUSION),
            GridAxis("sampler", GridPlanner.parseValues("euler, dpmpp_2m")),
            GridAxis("seed", GridPlanner.parseValues("1-5", integer = true)),
            cap = 8,
        )
        assertEquals(8, plan.jobs.size)
        assertTrue(plan.truncated)
        assertEquals(ParamValue.of("dpmpp_2m"), plan.jobs[3].recipe.params["sampler"])
        assertEquals(2L, plan.jobs[3].recipe.seed)
        val model = GridPlanner.plan(Recipe(EngineKinds.DIFFUSION), GridAxis("model", listOf(ParamValue.of("a"), ParamValue.of("b"))))
        assertEquals(listOf("a", "b"), model.jobs.map { it.recipe.model })
    }

    @Test
    fun axisValuesParseListsAndRanges() {
        assertEquals(listOf(ParamValue.of(10), ParamValue.of(20)), GridPlanner.parseValues("10, 20"))
        assertEquals(listOf(ParamValue.of(1.0), ParamValue.of(1.25), ParamValue.of(1.5)), GridPlanner.parseValues("1-1.5 (+0.25)"))
        assertEquals(listOf(ParamValue.of(5), ParamValue.of(6), ParamValue.of(7)), GridPlanner.parseValues("5-7", integer = true))
        assertEquals(listOf(ParamValue.of(0.5), ParamValue.of("karras")), GridPlanner.parseValues("0.5, karras"))
    }

    @Test
    fun snippetsInsertWithCommas() {
        assertEquals("a cat, oil painting" to 19, PromptLibrary.insert("a cat", 5, "oil painting"))
        assertEquals("oil painting, a cat" to 12, PromptLibrary.insert("a cat", 0, "oil painting"))
        assertEquals("a cat, moody, at night", PromptLibrary.insert("a cat, at night", 6, "moody").first)
        assertEquals("x", PromptLibrary.insert("", 0, "x").first)
    }

    @Test
    fun stylesWrapThePromptAndMergeNegatives() {
        val style = PromptLibrary.builtInStyles.first { it.id == "style-cinematic" }
        val (p, n) = PromptLibrary.applyStyle(" a fox ", "blurry", style)
        assertEquals("cinematic still of a fox, shallow depth of field, film grain, dramatic lighting", p)
        assertEquals("blurry, flat lighting", n)
        val tail = PromptSnippet("s", "Tail", "4k", PromptSnippet.STYLE)
        assertEquals("a fox, 4k", PromptLibrary.applyStyle("a fox", "", tail).first)
        // A second tap on the same style does not wrap the prompt again.
        assertEquals(p, PromptLibrary.applyStyle(p, n, style).first)
        assertEquals("a fox, 4k", PromptLibrary.applyStyle("a fox, 4k", "", tail).first)
        val photo = PromptLibrary.builtInStyles.first { it.id == "style-photo" }
        val once = PromptLibrary.applyStyle("a cat", "", photo).first
        assertEquals(once, PromptLibrary.applyStyle(once, "", photo).first)
        // Another style still wraps a styled prompt.
        assertEquals("cinematic still of $once, shallow depth of field, film grain, dramatic lighting", PromptLibrary.applyStyle(once, "", style).first)
        assertEquals("lowres, blurry, text", PromptLibrary.joinNegative("lowres, blurry", "Blurry, text"))
        assertTrue(PromptLibrary.negativePresets.isNotEmpty())
    }

    @Test
    fun weightHelperWrapsAdjustsAndUnwraps() {
        val (once, s, e) = PromptLibrary.adjustWeight("a red cat", 2, 5)
        assertEquals("a (red:1.1) cat", once)
        assertEquals("(red:1.1)", once.substring(s, e))
        assertEquals("a (red:1.2) cat", PromptLibrary.adjustWeight(once, s, e).first)
        // Cursor inside a weighted group, weight back to 1 → plain text again.
        assertEquals("a red cat", PromptLibrary.adjustWeight(once, 5, 5, -0.1).first)
        // Cursor in a plain word selects it.
        assertEquals("a (red:0.9) cat", PromptLibrary.adjustWeight("a red cat", 3, 3, -0.1).first)
    }
}
