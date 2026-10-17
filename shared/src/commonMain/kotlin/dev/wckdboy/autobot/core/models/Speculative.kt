package dev.wckdboy.autobot.core.models

/**
 * Speculative decoding (Settings › Compute): a small model of the same family drafts a few tokens
 * and the chat model checks them in one batch, so replies come faster with the same text.
 * [AUTO] uses a draft when one is installed and the pair fits this phone comfortably, and the
 * engine stops speculating while it does not pay; [ON] uses it whenever the pair fits at all and
 * keeps speculating; [OFF] never loads a draft.
 */
enum class SpeculativeMode(val id: String, val label: String) {
    AUTO("auto", "auto"),
    ON("on", "on"),
    OFF("off", "off"),
    ;

    companion object {
        fun of(value: String?): SpeculativeMode = entries.firstOrNull { it.id == value || it.name == value } ?: AUTO
    }
}

/** The draft model one chat request carries to the llama.cpp engine (`draft`, `speculative`). */
data class DraftChoice(
    val modelId: String,
    val title: String,
    /** Absolute path of the draft's GGUF. */
    val path: String,
    /** Where the engine runs the draft: "cpu" or "gpu" ([SpeculativeDrafts.draftBackend]). */
    val backend: String,
    val mode: SpeculativeMode,
)

/**
 * Which installed model drafts for which, whether the pair fits, and where the draft runs. Pure
 * rules, the same on both platforms; the apps resolve installed models and device capabilities.
 */
object SpeculativeDrafts {
    /**
     * Catalog chat models and the smaller models of the same family that draft for them (same
     * tokenizer, token for token; the engine checks again and refuses a mismatch), best first.
     *
     * Not paired: Qwen3.5 (hybrid attention + recurrent layers: rejected drafts cannot be cut from
     * their memory cheaply, so the engine does not speculate on them), Gemma 4, Phi-4 mini, SmolLM3
     * (no smaller sibling in the catalog) and the vision models (images are not drafted).
     */
    val pairs: Map<String, List<String>> = mapOf(
        "catalog:qwen3-4b-2507-q4_0" to listOf("catalog:qwen3-0.6b-q4_0"),
        "catalog:llama-3.2-3b-q4_0" to listOf("catalog:llama-3.2-1b-q4_0"),
        "catalog:qwen2.5-coder-7b-q4_k_m" to listOf("catalog:qwen2.5-coder-0.5b-q4_0", "catalog:qwen2.5-coder-1.5b-q4_0"),
        "catalog:qwen2.5-coder-1.5b-q4_0" to listOf("catalog:qwen2.5-coder-0.5b-q4_0"),
    )

    /** Draft model ids for [targetId], best first (empty: it has none). */
    fun draftsFor(targetId: String): List<String> = pairs[targetId].orEmpty()

    /** Chat models that [draftId] can draft for. */
    fun targetsOf(draftId: String): List<String> = pairs.filterValues { draftId in it }.keys.toList()

    /**
     * The draft's context in the engine: it sees a window of the most recent 1-2k tokens, not the
     * whole conversation (its KV cache is large per token, and catching it up costs a prefill).
     */
    const val DRAFT_MAX_CONTEXT = 2048

    /**
     * Where the draft runs. Android: the CPU, whatever the chat model uses (the GPU and NPU pay a
     * large fixed cost per single-token step, the draft's whole job; a 0.5-1B draft on the big
     * cores keeps up). iOS: Metal when the chat model runs there (llama.cpp has no Neural Engine
     * backend), the CPU otherwise.
     */
    fun draftBackend(platform: AppPlatform, target: Backend): String = when (platform) {
        AppPlatform.IOS -> if (target == Backend.CPU) "cpu" else "gpu"
        AppPlatform.ANDROID -> "cpu"
    }

    /**
     * Rough working set of a draft of [bytes] at [contextTokens]: weights and buffers, plus its KV
     * cache (8-bit, [DRAFT_MAX_CONTEXT] tokens at most). Small models keep many layers and KV
     * heads for their size, so their cache is about three times the usual rule per weight byte.
     */
    fun draftWorkingBytes(bytes: Long, contextTokens: Int): Long =
        bytes * 13 / 10 + DeviceCapabilities.kvCacheBytes(bytes, minOf(contextTokens, DRAFT_MAX_CONTEXT)) * 3

    /** How the chat model of [targetBytes] and a draft of [draftBytes] fit together at [contextTokens]. */
    fun fit(device: DeviceCapabilities, targetBytes: Long, draftBytes: Long, contextTokens: Int): Fit =
        device.fitWorking(device.chatWorkingBytes(targetBytes, contextTokens) + draftWorkingBytes(draftBytes, contextTokens))

    /**
     * The draft for [target] among [installed], or null. [mode] OFF: none. AUTO: only when the
     * chat model alone fits well and the pair still fits ([Fit.GOOD] or [Fit.TIGHT]). ON: whenever
     * the pair is not [Fit.TOO_BIG]. [device] null (unknown) skips the memory rules. [usable]
     * lets the app reject drafts whose file is gone.
     */
    fun pick(
        mode: SpeculativeMode,
        target: InstalledModel,
        installed: List<InstalledModel>,
        device: DeviceCapabilities?,
        contextTokens: Int,
        targetBackend: Backend,
        platform: AppPlatform = HostPlatform,
        usable: (String) -> Boolean = { true },
    ): DraftChoice? {
        if (mode == SpeculativeMode.OFF || !target.isLocalChat) return null
        val byId = installed.associateBy { it.id }
        for (id in draftsFor(target.id)) {
            val draft = byId[id]?.takeIf { it.isLocalChat } ?: continue
            val path = draft.paths[FileRole.MODEL]?.takeIf(usable) ?: continue
            if (!allows(mode, device, target.totalBytes, draft.totalBytes, contextTokens)) continue
            return DraftChoice(draft.id, draft.title, path, draftBackend(platform, targetBackend), mode)
        }
        return null
    }

    /**
     * The memory rule of [pick] on its own (iOS resolves its installed models itself): AUTO needs
     * the chat model of [targetBytes] to fit well alone and the pair to fit; ON only the pair.
     */
    fun allows(mode: SpeculativeMode, device: DeviceCapabilities?, targetBytes: Long, draftBytes: Long, contextTokens: Int): Boolean {
        if (mode == SpeculativeMode.OFF) return false
        if (device == null) return true
        val pair = fit(device, targetBytes, draftBytes, contextTokens)
        val alone = device.fitWorking(device.chatWorkingBytes(targetBytes, contextTokens))
        return when (mode) {
            SpeculativeMode.AUTO -> alone == Fit.GOOD && (pair == Fit.GOOD || pair == Fit.TIGHT)
            else -> pair == Fit.GOOD || pair == Fit.TIGHT
        }
    }
}

/** What the engine reported about speculation in one reply (`usage.speculative`). */
data class SpeculativeStats(
    /** "active", "off", "no_draft", "refused", "unsupported", "images" or "draft_failed". */
    val status: String,
    val draft: String? = null,
    val drafted: Int = 0,
    val accepted: Int = 0,
    val reason: String? = null,
) {
    /** Share of drafted tokens the model kept, 0..1. */
    val acceptance: Double get() = if (drafted > 0) accepted.toDouble() / drafted else 0.0
}

/**
 * A Compute benchmark of speculative decoding: the same greedy reply with and without the draft.
 * Kept for the session (the Compute screen and the diagnostics report), not stored.
 */
data class SpeculativeBenchmark(
    val modelId: String,
    val draftTitle: String,
    val backend: Backend,
    val plainTps: Double,
    val speculativeTps: Double,
    val acceptance: Double,
    val error: String? = null,
) {
    val speedup: Double get() = if (plainTps > 0) speculativeTps / plainTps else 0.0
}
