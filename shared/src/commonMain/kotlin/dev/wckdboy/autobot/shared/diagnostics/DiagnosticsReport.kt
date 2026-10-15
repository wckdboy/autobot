package dev.wckdboy.autobot.shared.diagnostics

import kotlin.math.roundToInt
import kotlinx.serialization.Serializable

/**
 * What the Compute screen's "copy report" puts on the clipboard, so a hardware bug report says
 * which accelerators exist, which engine backends actually loaded and how fast each ran. Both
 * apps fill the same fields and share [render], so reports read the same.
 *
 * Deliberately excluded: prompts, outputs, file paths, account names, serial numbers and any
 * other identifier. The user sees the text before copying it, and nothing is sent anywhere.
 */
@Serializable
class DiagnosticsReport(
    /** e.g. `Autobot 1.2.1 (4)`. */
    val app: String,
    /** e.g. `Android 16 (API 37)` or `iOS 27.0`. */
    val os: String,
    val device: DeviceInfo,
    /** Devices the llama.cpp engine registered (CPU variants, OpenCL/Metal GPU, Hexagon HTP). */
    val engineDevices: List<EngineDevice> = emptyList(),
    /** The image accelerator (QNN HTP on Android, the Neural Engine on iOS). */
    val imageAccelerator: AcceleratorStatus? = null,
    val benchmarks: List<BenchmarkLine> = emptyList(),
    /** Recent engine errors, newest first (messages only). */
    val errors: List<String> = emptyList(),
    /** The global network mode (`Offline`, `Direct`, `Tor`, `SOCKS5`). */
    val networkMode: String? = null,
    /** The OS's own language model (Apple Intelligence, Gemini Nano), when the build can use one. */
    val systemModel: AcceleratorStatus? = null,
) {
    /** Plain text, readable in an issue or a chat message. */
    fun render(): String = buildString {
        appendLine("## $app — diagnostics")
        appendLine("os: $os")
        appendLine("device: ${device.label}")
        device.soc?.let { appendLine("soc: $it") }
        device.accelerator?.let { appendLine("accelerator: $it") }
        appendLine("ram: ${gib(device.ramBytes)} · free storage: ${gib(device.freeStorageBytes)}")
        if (device.cpuFeatures.isNotEmpty()) appendLine("cpu: ${device.cores} cores · ${device.cpuFeatures.sorted().joinToString(" ")}")
        networkMode?.let { appendLine("network: $it") }
        appendLine()
        appendLine("### engine devices")
        if (engineDevices.isEmpty()) appendLine("(none reported)")
        engineDevices.forEach { d ->
            val memory = d.memoryTotalBytes.takeIf { it > 0 }?.let { " · ${gib(it)}" }.orEmpty()
            appendLine("- ${d.kind}: ${d.name} — ${d.description}$memory")
        }
        imageAccelerator?.let { a ->
            appendLine()
            appendLine("### image accelerator")
            appendLine(a.line())
        }
        systemModel?.let { a ->
            appendLine()
            appendLine("### system model")
            appendLine(a.line())
        }
        appendLine()
        appendLine("### benchmarks")
        if (benchmarks.isEmpty()) appendLine("(none run)")
        benchmarks.forEach { b ->
            val numbers = listOfNotNull(
                b.promptTps?.let { "prompt ${fmt(it)} tok/s" },
                b.generationTps?.let { "gen ${fmt(it)} tok/s" },
                b.stepMs?.let { "${fmt(it)} ms/step" },
                b.loadMs?.let { "load ${it} ms" },
            ).joinToString(" · ")
            appendLine("- ${b.model} on ${b.backend}: ${b.error?.let { "FAILED $it" } ?: numbers}")
        }
        if (errors.isNotEmpty()) {
            appendLine()
            appendLine("### recent engine errors")
            errors.take(MAX_ERRORS).forEach { appendLine("- ${it.take(MAX_ERROR_CHARS)}") }
        }
    }.trimEnd() + "\n"

    private companion object {
        const val MAX_ERRORS = 10
        const val MAX_ERROR_CHARS = 300

        fun AcceleratorStatus.line(): String = "$name: ${if (available) "available" else "unavailable"}${detail?.let { " · $it" }.orEmpty()}"

        fun gib(bytes: Long): String = "${fmt(bytes / 1_073_741_824.0)} GB"

        fun fmt(value: Double): String = ((value * 10).roundToInt() / 10.0).toString()
    }
}

@Serializable
class DeviceInfo(
    /** Marketing model, e.g. `Galaxy S26 Ultra` or `iPhone 17 Pro`. */
    val label: String,
    /** e.g. `SM8850 · Snapdragon 8 Elite Gen 5` or `A19 Pro`. */
    val soc: String? = null,
    /** e.g. `Hexagon v81` or `Neural Engine`. */
    val accelerator: String? = null,
    val ramBytes: Long = 0,
    val freeStorageBytes: Long = 0,
    val cores: Int = 0,
    val cpuFeatures: List<String> = emptyList(),
)

@Serializable
class EngineDevice(val name: String, val description: String, val kind: String, val memoryTotalBytes: Long = 0)

@Serializable
class AcceleratorStatus(val name: String, val available: Boolean, val detail: String? = null)

@Serializable
class BenchmarkLine(
    /** Model title, never its path. */
    val model: String,
    val backend: String,
    val promptTps: Double? = null,
    val generationTps: Double? = null,
    val stepMs: Double? = null,
    val loadMs: Long? = null,
    val error: String? = null,
)
