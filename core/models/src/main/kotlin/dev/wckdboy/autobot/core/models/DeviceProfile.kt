package dev.wckdboy.autobot.core.models

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.StatFs
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** How well a model fits this phone. */
enum class Fit(val label: String) { GOOD("fits"), TIGHT("tight"), TOO_BIG("too big"), UNSUPPORTED("unsupported") }

/** What this phone can run, read from the system (no network, no identifiers leave the device). */
data class DeviceProfile(
    val socModel: String,
    val socName: String?,
    val totalRamBytes: Long,
    val freeStorageBytes: Long,
    val cpuFeatures: Set<String>,
    val cores: Int,
) {
    /** Dot-product instructions: required by the image engine build (ARMv8.2+dotprod). */
    val supportsImageEngine: Boolean get() = "asimddp" in cpuFeatures
    val supportsI8mm: Boolean get() = "i8mm" in cpuFeatures

    /** e.g. `SM8850 · Snapdragon 8 Elite Gen 5`. */
    val label: String get() = listOfNotNull(socModel.takeIf { it.isNotBlank() }, socName).joinToString(" · ").ifBlank { Build.MODEL }

    /** Weights + KV cache / buffers should stay under about half of physical RAM. */
    fun fit(kind: ModelKind, bytes: Long, engine: EngineKind): Fit {
        if (engine == EngineKind.NONE) return Fit.UNSUPPORTED
        if (engine == EngineKind.DIFFUSION && !supportsImageEngine) return Fit.UNSUPPORTED
        val working = when (kind) {
            ModelKind.CHAT, ModelKind.CODE -> bytes * 13 / 10
            ModelKind.IMAGE -> bytes * 14 / 10
            else -> bytes
        }
        val budget = totalRamBytes / 2
        return when {
            working <= budget * 7 / 10 -> Fit.GOOD
            working <= budget -> Fit.TIGHT
            else -> Fit.TOO_BIG
        }
    }

    companion object {
        /** Public SoC part numbers → marketing names (Snapdragon flagships). */
        private val SNAPDRAGON = mapOf(
            "SM8850" to "Snapdragon 8 Elite Gen 5",
            "SM8845" to "Snapdragon 8 Gen 5",
            "SM8750" to "Snapdragon 8 Elite",
            "SM8735" to "Snapdragon 8s Gen 4",
            "SM8650" to "Snapdragon 8 Gen 3",
            "SM8635" to "Snapdragon 8s Gen 3",
            "SM8550" to "Snapdragon 8 Gen 2",
            "SM8475" to "Snapdragon 8+ Gen 1",
            "SM8450" to "Snapdragon 8 Gen 1",
            "SM7675" to "Snapdragon 7+ Gen 3",
            "SM7550" to "Snapdragon 7 Gen 3",
        )

        fun socName(model: String): String? = SNAPDRAGON[model.uppercase().substringBefore('-')]

        internal fun parseCpuFeatures(cpuinfo: String): Set<String> =
            cpuinfo.lineSequence()
                .firstOrNull { it.startsWith("Features", ignoreCase = true) }
                ?.substringAfter(':')
                ?.trim()
                ?.split(Regex("\\s+"))
                ?.toSet()
                .orEmpty()
    }
}

@Singleton
class DeviceProfiler @Inject constructor(@ApplicationContext private val context: Context) {

    fun profile(): DeviceProfile {
        val am = context.getSystemService(ActivityManager::class.java)
        val mem = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val soc = Build.SOC_MODEL.takeUnless { it == Build.UNKNOWN }.orEmpty()
        val features = runCatching { File("/proc/cpuinfo").readText() }.getOrDefault("").let(DeviceProfile::parseCpuFeatures)
        return DeviceProfile(
            socModel = soc,
            socName = DeviceProfile.socName(soc),
            totalRamBytes = mem.totalMem,
            freeStorageBytes = StatFs(context.filesDir.path).availableBytes,
            cpuFeatures = features,
            cores = Runtime.getRuntime().availableProcessors(),
        )
    }
}
