package dev.wckdboy.autobot.shared.studio

import kotlin.math.roundToInt
import kotlin.random.Random
import kotlinx.serialization.Serializable

/*
 * Prompt tools: wildcard expansion into batches, seed lock / walk, the X/Y grid planner, the
 * prompt library (snippets and styles), negative-prompt presets and the weighted-token helper.
 * All deterministic: the same inputs (and seed) give the same prompts in the same order.
 */

/** What [Wildcards.expand] made: [prompts] (at most the cap) out of [total] combinations. */
data class Expansion(val prompts: List<String>, val total: Long) {
    val truncated: Boolean get() = total > prompts.size
}

/**
 * `{red|blue|green}` choices in a prompt. Choices nest (`{a {big|small} cat|a dog}`), an option
 * may end in a weight (`{red:3|blue}`: red three times as likely when sampling, three copies in
 * a full expansion; `:0` drops it), and `\{`, `\|`, `\}` are literal. SD emphasis like
 * `(red:1.2)` is left alone (a weight only counts directly before `|` or `}`).
 */
object Wildcards {
    /** Default cap on prompts per expansion (a batch of 64 is already a lot on a phone). */
    const val DEFAULT_CAP = 64

    private sealed interface Node
    private class Text(val text: String) : Node
    private class Choice(val options: List<Option>) : Node
    private class Option(val parts: List<Node>, val weight: Double)

    /** True when [template] has at least one `{…|…}` choice. */
    fun hasWildcards(template: String): Boolean = parse(template).any { it is Choice }

    /**
     * Every combination, left-most choice varying slowest, options in written order (weights as
     * copies), cut at [cap]. Unbalanced braces are taken literally.
     */
    fun expand(template: String, cap: Int = DEFAULT_CAP): Expansion {
        val nodes = parse(template)
        return Expansion(expandSeq(nodes, cap), countSeq(nodes))
    }

    /** [count] prompts, each choice drawn by weight from a generator seeded with [seed]. */
    fun sample(template: String, count: Int, seed: Long): List<String> {
        val nodes = parse(template)
        val random = Random(seed)
        return List(count.coerceAtLeast(0)) { buildString { sampleSeq(nodes, random, this) } }
    }

    private fun expandSeq(nodes: List<Node>, cap: Int): List<String> {
        var acc = listOf("")
        for (node in nodes) {
            val parts = when (node) {
                is Text -> listOf(node.text)
                is Choice -> node.options.flatMap { o -> val e = expandSeq(o.parts, cap); List(copies(o.weight)) { e }.flatten() }.take(cap)
            }
            val next = ArrayList<String>()
            outer@ for (a in acc) for (p in parts) {
                next += a + p
                if (next.size >= cap) break@outer
            }
            acc = next
        }
        return acc
    }

    private fun countSeq(nodes: List<Node>): Long = nodes.fold(1L) { acc, node ->
        val n = when (node) {
            is Text -> 1L
            is Choice -> node.options.fold(0L) { s, o -> sat(s + sat(copies(o.weight).toLong() * countSeq(o.parts))) }
        }
        if (n == 0L || acc == 0L) 0L else if (acc > Long.MAX_VALUE / n) Long.MAX_VALUE else acc * n
    }

    private fun sat(v: Long) = if (v < 0) Long.MAX_VALUE else v

    private fun copies(weight: Double): Int = if (weight <= 0) 0 else weight.roundToInt().coerceIn(1, 100)

    private fun sampleSeq(nodes: List<Node>, random: Random, out: StringBuilder) {
        for (node in nodes) when (node) {
            is Text -> out.append(node.text)
            is Choice -> {
                val usable = node.options.filter { it.weight > 0 }
                if (usable.isEmpty()) continue
                var pick = random.nextDouble() * usable.sumOf { it.weight }
                val chosen = usable.firstOrNull { pick -= it.weight; pick < 0 } ?: usable.last()
                sampleSeq(chosen.parts, random, out)
            }
        }
    }

    // --- parser -----------------------------------------------------------------------------

    private fun parse(template: String): List<Node> {
        val p = Parser(template)
        return p.sequence(topLevel = true)
    }

    private class Parser(val s: String) {
        var i = 0

        fun sequence(topLevel: Boolean): List<Node> {
            val nodes = ArrayList<Node>()
            val text = StringBuilder()
            fun flush() {
                if (text.isNotEmpty()) nodes += Text(text.toString())
                text.clear()
            }
            while (i < s.length) {
                val c = s[i]
                when {
                    c == '\\' && i + 1 < s.length && s[i + 1] in "{}|\\" -> {
                        text.append(s[i + 1])
                        i += 2
                    }
                    c == '{' && closes(i) -> {
                        flush()
                        i++
                        nodes += choice()
                    }
                    !topLevel && (c == '|' || c == '}') -> break
                    else -> {
                        text.append(c)
                        i++
                    }
                }
            }
            flush()
            return nodes
        }

        private fun choice(): Choice {
            val options = ArrayList<Option>()
            while (true) {
                val parts = sequence(topLevel = false).toMutableList()
                options += weighted(parts)
                if (i >= s.length) break
                val c = s[i++]
                if (c == '}') break
            }
            return Choice(options)
        }

        private fun weighted(parts: MutableList<Node>): Option {
            val last = parts.lastOrNull() as? Text ?: return Option(parts, 1.0)
            val m = WEIGHT.find(last.text) ?: return Option(parts, 1.0)
            val weight = m.groupValues[1].toDoubleOrNull() ?: return Option(parts, 1.0)
            val rest = last.text.substring(0, m.range.first)
            parts.removeAt(parts.size - 1)
            if (rest.isNotEmpty()) parts += Text(rest)
            return Option(parts, weight)
        }

        /** Whether the `{` at [start] has a matching `}` (otherwise it is literal). */
        private fun closes(start: Int): Boolean {
            var depth = 0
            var j = start
            while (j < s.length) {
                when (s[j]) {
                    '\\' -> j++
                    '{' -> depth++
                    '}' -> if (--depth == 0) return true
                }
                j++
            }
            return false
        }
    }

    private val WEIGHT = Regex(":(\\d+(?:\\.\\d+)?)\\s*$")
}

/** How seeds advance over a batch. */
@Serializable
enum class SeedMode {
    /** A fresh random seed per image. */
    RANDOM,

    /** The same seed every time (compare prompts or params). */
    LOCKED,

    /** base, base+1, base+2, … */
    WALK,
}

object Seeds {
    /** Largest seed every engine accepts (32-bit unsigned). */
    const val MAX = 0xFFFF_FFFFL

    fun random(random: Random = Random.Default): Long = random.nextLong(0, MAX + 1)

    /** [count] seeds from [base] by [mode]; walking wraps past [MAX]. */
    fun plan(mode: SeedMode, base: Long, count: Int, random: Random = Random.Default): List<Long> = List(count.coerceAtLeast(0)) { i ->
        when (mode) {
            SeedMode.LOCKED -> base.coerceIn(0, MAX)
            SeedMode.WALK -> (base.coerceIn(0, MAX) + i) % (MAX + 1)
            SeedMode.RANDOM -> random(random)
        }
    }
}

/** One job of a batch or grid: the recipe to run and its label. */
data class PlannedJob(val recipe: Recipe, val label: String, val row: Int = 0, val column: Int = 0)

/**
 * Turns one recipe into a batch: the prompt's wildcards expanded (or [sampled] when it has more
 * combinations than [cap]), times [batchCount], with seeds by [seedMode] from the recipe's seed.
 */
object BatchPlanner {
    fun plan(
        base: Recipe,
        batchCount: Int = 1,
        seedMode: SeedMode = SeedMode.RANDOM,
        cap: Int = Wildcards.DEFAULT_CAP,
        random: Random = Random.Default,
        sampled: Boolean = false,
    ): List<PlannedJob> {
        val template = base.string(Recipe.PROMPT).orEmpty()
        val prompts = when {
            !Wildcards.hasWildcards(template) -> listOf(template)
            sampled -> Wildcards.sample(template, cap, random.nextLong())
            else -> Wildcards.expand(template, cap).prompts
        }
        val count = (prompts.size * batchCount.coerceAtLeast(1)).coerceAtMost(cap)
        val seeds = Seeds.plan(seedMode, base.seed ?: Seeds.random(random), count, random)
        return List(count) { i ->
            val prompt = prompts[i / batchCount.coerceAtLeast(1)]
            val recipe = base.with(Recipe.PROMPT, ParamValue.StringValue(prompt)).copy(seed = seeds[i])
            val label = buildList {
                if (prompts.size > 1) add(prompt)
                add("seed ${seeds[i]}")
            }.joinToString(" · ")
            PlannedJob(recipe, label, row = i, column = 0)
        }
    }
}

/** One engine call of a quick Image run: [prompt] for [count] images from [seed] (`-1` = random). */
data class PromptRun(val prompt: String, val count: Int, val seed: Long)

/**
 * The quick Image screen's plan (no queue): a plain prompt is one engine call with the batch; a
 * prompt with `{a|b}` wildcards is one call per expanded prompt, each with the batch and the same
 * seed (so a fixed seed compares the prompts side by side), at most [MAX_IMAGES] images in all.
 */
object PromptRuns {
    const val MAX_IMAGES = 16

    fun plan(template: String, batch: Int, seed: Long, maxImages: Int = MAX_IMAGES): List<PromptRun> {
        val per = batch.coerceIn(1, maxImages.coerceAtLeast(1))
        if (!Wildcards.hasWildcards(template)) return listOf(PromptRun(template, per, seed))
        val prompts = Wildcards.expand(template, cap = (maxImages / per).coerceAtLeast(1)).prompts
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        if (prompts.isEmpty()) return listOf(PromptRun(template, per, seed))
        return prompts.map { PromptRun(it, per, seed) }
    }

    /** Images [plan] makes in all. */
    fun images(template: String, batch: Int, maxImages: Int = MAX_IMAGES): Int =
        plan(template, batch, -1, maxImages).sumOf { it.count }

    /**
     * What the wildcards will run, for the line under the prompt (`wildcards · 3 prompts × 2 = 6
     * images`), or null for a plain prompt.
     */
    fun summary(template: String, batch: Int, maxImages: Int = MAX_IMAGES): String? {
        if (!Wildcards.hasWildcards(template)) return null
        val total = Wildcards.expand(template, cap = 1).total
        val runs = plan(template, batch, -1, maxImages)
        val per = runs.first().count
        val images = runs.size * per
        val prompts = if (total > runs.size) "${runs.size} of $total prompts" else "${runs.size} prompt${if (runs.size == 1) "" else "s"}"
        return "wildcards · $prompts" + (if (per > 1) " × $per" else "") + " = $images image${if (images == 1) "" else "s"}"
    }
}

/** One axis of an X/Y grid: [param] (any recipe param, `seed` or `model`) over [values]. */
data class GridAxis(val param: String, val values: List<ParamValue>, val label: String = param)

/** A planned grid: cells row by row (y), column by column (x), with axis labels. */
data class GridPlan(val jobs: List<PlannedJob>, val columns: List<String>, val rows: List<String>, val truncated: Boolean)

/** The X/Y grid: vary one or two params over value lists (steps × cfg, sampler × seed, LoRA weight). */
object GridPlanner {
    const val DEFAULT_CAP = 64

    fun plan(base: Recipe, x: GridAxis, y: GridAxis? = null, cap: Int = DEFAULT_CAP): GridPlan {
        require(x.values.isNotEmpty()) { "The X axis has no values" }
        require(y == null || y.values.isNotEmpty()) { "The Y axis has no values" }
        require(y == null || y.param != x.param) { "X and Y vary the same parameter" }
        val rows = y?.values ?: listOf(null)
        val jobs = ArrayList<PlannedJob>()
        var truncated = false
        loop@ for ((r, yv) in rows.withIndex()) for ((c, xv) in x.values.withIndex()) {
            if (jobs.size >= cap) {
                truncated = true
                break@loop
            }
            var recipe = base.with(x.param, xv)
            if (y != null && yv != null) recipe = recipe.with(y.param, yv)
            val label = listOfNotNull("${x.label}=${xv.display()}", yv?.let { "${y!!.label}=${it.display()}" }).joinToString(" · ")
            jobs += PlannedJob(recipe, label, r, c)
        }
        return GridPlan(jobs, x.values.map { it.display() }, y?.values?.map { it.display() }.orEmpty(), truncated)
    }

    /** Parses an axis value list typed by the user: `10, 20, 30`, `5-9`, `1-2 (+0.25)`, `euler, dpmpp_2m`. */
    fun parseValues(text: String, integer: Boolean = false): List<ParamValue> {
        val range = Regex("^\\s*(-?[\\d.]+)\\s*-\\s*(-?[\\d.]+)\\s*(?:\\(\\s*\\+\\s*([\\d.]+)\\s*\\))?\\s*$").find(text)
        if (range != null) {
            val (a, b, s) = range.destructured
            val from = a.toDouble()
            val to = b.toDouble()
            val step = s.toDoubleOrNull() ?: 1.0
            if (step > 0 && to >= from) {
                val n = ((to - from) / step + 1e-9).toInt().coerceAtMost(256)
                return (0..n).map { i -> number(from + i * step, integer) }
            }
        }
        return text.split(',').map { it.trim() }.filter { it.isNotEmpty() }.map { v ->
            v.toDoubleOrNull()?.let { number(it, integer || (v.toLongOrNull() != null)) } ?: ParamValue.StringValue(v)
        }
    }

    private fun number(v: Double, integer: Boolean): ParamValue =
        if (integer) ParamValue.IntValue(kotlin.math.round(v).toLong()) else ParamValue.FloatValue(kotlin.math.round(v * 1e6) / 1e6)
}

/** A prompt-library entry: a [SNIPPET] inserted at the cursor, or a [STYLE] wrapping the prompt. */
@Serializable
data class PromptSnippet(
    val id: String,
    val name: String,
    /** The snippet text; for a style a template where `{prompt}` stands for the prompt. */
    val text: String,
    val kind: String = SNIPPET,
    /** Added to the negative prompt when a style is applied. */
    val negative: String = "",
) {
    companion object {
        const val SNIPPET = "snippet"
        const val STYLE = "style"
    }
}

/** Prompt editing helpers and the built-in styles / negative presets. */
object PromptLibrary {
    /**
     * Inserts [snippet] at [cursor] with comma separation (`a cat|` + `oil painting` →
     * `a cat, oil painting`). Returns the new prompt and cursor.
     */
    fun insert(prompt: String, cursor: Int, snippet: String): Pair<String, Int> {
        val at = cursor.coerceIn(0, prompt.length)
        val before = prompt.substring(0, at).trimEnd()
        val after = prompt.substring(at).trimStart()
        val lead = if (before.isEmpty() || before.endsWith(",") || before.endsWith("(")) (if (before.isEmpty()) "" else " ") else ", "
        val tail = if (after.isEmpty() || after.startsWith(",") || after.startsWith(")")) "" else ", "
        val head = before + lead + snippet.trim()
        return (head + tail + after) to head.length
    }

    /**
     * Applies a style: `{prompt}` replaced (or the style appended), negatives joined. A prompt
     * that already carries this style stays as it is (a second tap does not wrap it twice).
     */
    fun applyStyle(prompt: String, negative: String, style: PromptSnippet): Pair<String, String> {
        val trimmed = prompt.trim()
        val p = when {
            hasStyle(trimmed, style) -> trimmed
            "{prompt}" in style.text -> style.text.replace("{prompt}", trimmed)
            else -> listOf(trimmed, style.text.trim()).filter { it.isNotEmpty() }.joinToString(", ")
        }
        return p to joinNegative(negative, style.negative)
    }

    /** Whether [prompt] is already wrapped in [style] (its text around `{prompt}`, or appended). */
    private fun hasStyle(prompt: String, style: PromptSnippet): Boolean {
        val text = style.text.trim()
        if (text.isEmpty()) return false
        val at = text.indexOf("{prompt}")
        if (at < 0) return prompt.endsWith(", $text", ignoreCase = true) || prompt.equals(text, ignoreCase = true)
        val head = text.substring(0, at)
        val tail = text.substring(at + "{prompt}".length)
        return prompt.length > head.length + tail.length && prompt.startsWith(head, ignoreCase = true) && prompt.endsWith(tail, ignoreCase = true)
    }

    /** Joins negative prompts, dropping terms already present. */
    fun joinNegative(current: String, add: String): String {
        val have = current.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        val seen = have.map { it.lowercase() }.toMutableSet()
        val extra = add.split(',').map { it.trim() }.filter { it.isNotEmpty() && seen.add(it.lowercase()) }
        return (have + extra).joinToString(", ")
    }

    /**
     * The weighted-token helper: changes the emphasis of `prompt[start, end)` by [delta]
     * (`cat` → `(cat:1.1)`, `(cat:1.1)` → `(cat:1.2)`, back to 1.0 removes the brackets).
     * Returns the new prompt and the new selection.
     */
    fun adjustWeight(prompt: String, start: Int, end: Int, delta: Double = 0.1): Triple<String, Int, Int> {
        var s = start.coerceIn(0, prompt.length)
        var e = end.coerceIn(s, prompt.length)
        if (s == e) {
            // Select the word (or weighted group) under the cursor.
            val group = Regex("\\(([^()]*):(\\d+(?:\\.\\d+)?)\\)").findAll(prompt).firstOrNull { s >= it.range.first && s <= it.range.last + 1 }
            if (group != null) {
                s = group.range.first
                e = group.range.last + 1
            } else {
                while (s > 0 && prompt[s - 1].isLetterOrDigit()) s--
                while (e < prompt.length && prompt[e].isLetterOrDigit()) e++
            }
        }
        val selected = prompt.substring(s, e)
        val existing = Regex("^\\((.*):(\\d+(?:\\.\\d+)?)\\)$").find(selected)
        val text = existing?.groupValues?.get(1) ?: selected
        val weight = ((existing?.groupValues?.get(2)?.toDouble() ?: 1.0) + delta).let { kotlin.math.round(it * 100) / 100 }.coerceIn(0.0, 3.0)
        val replacement = if (kotlin.math.abs(weight - 1.0) < 1e-9) text else "($text:${ParamValue.formatNumber(weight)})"
        return Triple(prompt.substring(0, s) + replacement + prompt.substring(e), s, s + replacement.length)
    }

    /** Built-in styles (the user's own come from [StudioStore.snippets]). */
    val builtInStyles: List<PromptSnippet> = listOf(
        PromptSnippet("style-photo", "Photo", "{prompt}, photograph, natural light, 35mm, sharp focus", PromptSnippet.STYLE, "illustration, painting, cartoon"),
        PromptSnippet("style-cinematic", "Cinematic", "cinematic still of {prompt}, shallow depth of field, film grain, dramatic lighting", PromptSnippet.STYLE, "flat lighting"),
        PromptSnippet("style-riso", "Risograph", "{prompt}, risograph print, two-colour, halftone texture, paper grain", PromptSnippet.STYLE, "photo, 3d render"),
        PromptSnippet("style-ink", "Ink", "{prompt}, ink drawing, cross-hatching, black ink on paper", PromptSnippet.STYLE, "colour, photo"),
        PromptSnippet("style-watercolour", "Watercolour", "watercolour painting of {prompt}, soft washes, paper texture", PromptSnippet.STYLE, "photo, 3d render"),
    )

    /** Negative-prompt presets, by name. */
    val negativePresets: List<PromptSnippet> = listOf(
        PromptSnippet("neg-general", "General", "lowres, blurry, jpeg artifacts, watermark, text, signature", PromptSnippet.SNIPPET),
        PromptSnippet("neg-anatomy", "Anatomy", "bad anatomy, extra fingers, missing fingers, deformed hands, extra limbs", PromptSnippet.SNIPPET),
        PromptSnippet("neg-photo", "Photo", "illustration, painting, cartoon, cgi, oversaturated", PromptSnippet.SNIPPET),
        PromptSnippet("neg-video", "Video", "static, flicker, jitter, morphing, low quality, worst quality", PromptSnippet.SNIPPET),
        PromptSnippet("neg-audio", "Audio", "low quality, distortion, clipping, noise, muffled", PromptSnippet.SNIPPET),
    )
}
