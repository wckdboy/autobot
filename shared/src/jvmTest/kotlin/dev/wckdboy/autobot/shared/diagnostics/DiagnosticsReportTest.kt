package dev.wckdboy.autobot.shared.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Test

class DiagnosticsReportTest {
    @Test
    fun rendersAReadableReport() {
        val report = DiagnosticsReport(
            app = "Autobot 1.2.2 (5)",
            os = "Android 16 (API 37)",
            device = DeviceInfo(
                label = "Samsung SM-S948B",
                soc = "SM8850 · Snapdragon 8 Elite Gen 5",
                accelerator = "Hexagon v81",
                ramBytes = 16L * 1_073_741_824,
                freeStorageBytes = 200L * 1_073_741_824,
                cores = 8,
                cpuFeatures = listOf("sve2", "i8mm", "asimddp"),
            ),
            engineDevices = listOf(
                EngineDevice("CPU", "ARMv9.2 (i8mm, sve2)", "cpu"),
                EngineDevice("GPUOpenCL", "QUALCOMM Adreno(TM) 840", "gpu", 12L * 1_073_741_824),
                EngineDevice("HTP0", "Hexagon v81", "npu"),
            ),
            imageAccelerator = AcceleratorStatus("QNN HTP", true, "Hexagon v81"),
            benchmarks = listOf(
                BenchmarkLine("Qwen3.5 4B", "npu", promptTps = 512.34, generationTps = 21.06, loadMs = 1830),
                BenchmarkLine("Qwen3.5 4B", "gpu", error = "context could not be created"),
            ),
            networkMode = "Offline",
        )
        assertEquals(
            """
            ## Autobot 1.2.2 (5) — diagnostics
            os: Android 16 (API 37)
            device: Samsung SM-S948B
            soc: SM8850 · Snapdragon 8 Elite Gen 5
            accelerator: Hexagon v81
            ram: 16.0 GB · free storage: 200.0 GB
            cpu: 8 cores · asimddp i8mm sve2
            network: Offline

            ### engine devices
            - cpu: CPU — ARMv9.2 (i8mm, sve2)
            - gpu: GPUOpenCL — QUALCOMM Adreno(TM) 840 · 12.0 GB
            - npu: HTP0 — Hexagon v81

            ### image accelerator
            QNN HTP: available · Hexagon v81

            ### benchmarks
            - Qwen3.5 4B on npu: prompt 512.3 tok/s · gen 21.1 tok/s · load 1830 ms
            - Qwen3.5 4B on gpu: FAILED context could not be created

            """.trimIndent(),
            report.render(),
        )
    }

    @Test
    fun listsTheSystemModel() {
        val report = DiagnosticsReport(
            app = "Autobot",
            os = "iOS 27.0",
            device = DeviceInfo("iPhone 17 Pro"),
            systemModel = AcceleratorStatus("Apple Intelligence (on-device)", false, "Apple Intelligence is turned off in Settings"),
        )
        val text = report.render()
        assertEquals(
            true,
            "### system model\nApple Intelligence (on-device): unavailable · Apple Intelligence is turned off in Settings\n" in text,
        )
    }
}
