package dev.wckdboy.autobot.core.models

import dev.wckdboy.autobot.core.data.ModelRowRepository
import dev.wckdboy.autobot.core.data.model.BenchmarkRow
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/** One measured run of a model on a backend. */
data class Benchmark(
    val modelId: String,
    /** Where it actually ran (an accelerator that fails falls back to the CPU). */
    val backend: Backend,
    val promptTps: Double? = null,
    val generationTps: Double? = null,
    val stepMs: Double? = null,
    val loadMs: Long? = null,
    val error: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
) {
    val ok: Boolean get() = error == null
}

/**
 * Which backend each model should run on: the user's explicit choice, otherwise the fastest one
 * the benchmark measured, otherwise [Backend.AUTO] (the engine tries NPU → GPU → CPU).
 */
@Singleton
class ComputeProfile @Inject constructor(private val rows: ModelRowRepository) {

    val benchmarks: Flow<List<Benchmark>> = rows.observeBenchmarks().map { list -> list.map { it.toBenchmark() } }

    /** Each benchmarked model's fastest backend (what [resolve] picks for "auto"). */
    val fastestByModel: Flow<Map<String, Backend>> = benchmarks.map { list ->
        list.groupBy { it.modelId }.mapNotNull { (id, runs) -> fastest(runs)?.let { id to it } }.toMap()
    }

    suspend fun setBackend(modelId: String, backend: Backend) = rows.setBackend(modelId, backend.id)

    suspend fun record(result: Benchmark) {
        rows.addBenchmark(
            BenchmarkRow(
                modelId = result.modelId,
                backend = result.backend.id,
                promptTps = result.promptTps,
                generationTps = result.generationTps,
                stepMs = result.stepMs,
                loadMs = result.loadMs,
                error = result.error,
                createdAt = result.createdAt,
            ),
        )
    }

    suspend fun resolve(model: InstalledModel): Backend {
        if (model.backend != Backend.AUTO) return model.backend
        return fastest(rows.benchmarks(model.id).map { it.toBenchmark() }) ?: Backend.AUTO
    }

    private val ran = MutableStateFlow<Map<String, Backend>>(emptyMap())

    /** Where each model last actually ran in this process, as the engine reported after a reply. */
    val lastRan: StateFlow<Map<String, Backend>> = ran.asStateFlow()

    private val spec = MutableStateFlow<Map<String, SpeculativeBenchmark>>(emptyMap())

    /** The newest speculative decoding benchmark per model, for this process (not stored). */
    val speculative: StateFlow<Map<String, SpeculativeBenchmark>> = spec.asStateFlow()

    fun recordSpeculative(result: SpeculativeBenchmark) {
        spec.update { it + (result.modelId to result) }
    }

    /** Records where [modelId] ran ([Backend.AUTO] = not reported, ignored). */
    fun noteRan(modelId: String, backend: Backend) {
        if (backend == Backend.AUTO || ran.value[modelId] == backend) return
        ran.update { it + (modelId to backend) }
    }

    companion object {
        /**
         * The backend a model's route tag names: where it last ran, else the user's pick, else the
         * fastest measured one ("auto" resolves to it); null while none of these is known (the
         * engine then tries NPU → GPU → CPU).
         */
        fun shownBackend(lastRan: Backend?, chosen: Backend, measured: Backend? = null): Backend? =
            lastRan ?: chosen.takeIf { it != Backend.AUTO } ?: measured

        /**
         * Fastest backend among the newest successful run per backend; null if nothing was measured.
         * Text models: the shortest typical chat turn ([TURN_PROMPT_TOKENS] prompt tokens read,
         * [TURN_REPLY_TOKENS] generated), so a backend that generates a little faster but reads
         * prompts several times slower does not win; by generation speed alone when a run has no
         * prompt speed. Image models: the shortest step.
         */
        fun fastest(results: List<Benchmark>): Backend? {
            val latest = results.filter { it.ok && it.backend != Backend.AUTO }
                .groupBy { it.backend }
                .mapValues { (_, runs) -> runs.maxBy { it.createdAt } }
                .values
            val text = latest.filter { it.generationTps != null && it.generationTps > 0 }
            if (text.isNotEmpty()) {
                if (text.any { it.promptTps == null || it.promptTps <= 0 }) return text.maxBy { it.generationTps!! }.backend
                return text.minBy { TURN_PROMPT_TOKENS / it.promptTps!! + TURN_REPLY_TOKENS / it.generationTps!! }.backend
            }
            return latest.filter { it.stepMs != null }.minByOrNull { it.stepMs!! }?.backend
        }

        /** A typical turn for [fastest]: an agent step reads about this many new prompt tokens… */
        const val TURN_PROMPT_TOKENS = 1000.0

        /** …and generates about this many. */
        const val TURN_REPLY_TOKENS = 200.0

        private fun BenchmarkRow.toBenchmark() = Benchmark(
            modelId = modelId,
            backend = Backend.of(backend),
            promptTps = promptTps,
            generationTps = generationTps,
            stepMs = stepMs,
            loadMs = loadMs,
            error = error,
            createdAt = createdAt,
        )
    }
}
