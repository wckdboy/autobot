package dev.wckdboy.autobot.core.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelLaunchTest {

    private fun android(kind: ModelKind, engine: EngineKind, ready: Boolean = true, fit: Fit? = Fit.GOOD, files: Boolean = true) =
        launchOf(kind, engine, ready, fit, files, AppPlatform.ANDROID)

    private fun ios(kind: ModelKind, engine: EngineKind, fit: Fit? = Fit.GOOD) = launchOf(kind, engine, true, fit, true, AppPlatform.IOS)

    @Test
    fun eachKindOpensItsTask() {
        assertEquals(ModelLaunch(ModelTask.CHAT), android(ModelKind.CHAT, EngineKind.LLAMA))
        assertEquals(ModelLaunch(ModelTask.CHAT, tools = true), android(ModelKind.CODE, EngineKind.LLAMA))
        assertEquals(ModelLaunch(ModelTask.IMAGINE), android(ModelKind.IMAGE, EngineKind.DIFFUSION))
        assertEquals(ModelLaunch(ModelTask.IMAGINE), android(ModelKind.IMAGE, EngineKind.NPU))
        assertEquals(ModelLaunch(ModelTask.GALLERY), android(ModelKind.UPSCALER, EngineKind.NPU))
        assertEquals(ModelLaunch(ModelTask.IMAGINE), ios(ModelKind.IMAGE, EngineKind.COREML))
        assertEquals(ModelLaunch(ModelTask.GALLERY), ios(ModelKind.UPSCALER, EngineKind.COREML))
        assertEquals(ModelLaunch(ModelTask.CHAT), ios(ModelKind.CHAT, EngineKind.LLAMA))
        assertTrue(android(ModelKind.CHAT, EngineKind.LLAMA, fit = null).opens)
    }

    @Test
    fun speechAndPartsOpenNothing() {
        listOf(ModelKind.SPEECH, ModelKind.LORA, ModelKind.VAE, ModelKind.EMBEDDING, ModelKind.OTHER).forEach { kind ->
            val launch = android(kind, if (kind == ModelKind.SPEECH) EngineKind.WHISPER else EngineKind.DIFFUSION)
            assertEquals(kind.name, ModelLaunch(ModelTask.NONE), launch)
            assertFalse(launch.opens)
        }
    }

    @Test
    fun unfinishedDownloadsOpenNothing() {
        assertEquals(ModelLaunch(ModelTask.NONE), android(ModelKind.IMAGE, EngineKind.DIFFUSION, ready = false))
    }

    @Test
    fun unusableModelsSayWhy() {
        // Engines the app doesn't ship: sd.cpp on iOS, Core ML on Android, or none at all.
        listOf(
            ios(ModelKind.IMAGE, EngineKind.DIFFUSION),
            android(ModelKind.IMAGE, EngineKind.COREML),
            android(ModelKind.CHAT, EngineKind.NONE),
            android(ModelKind.IMAGE, EngineKind.NPU, fit = Fit.UNSUPPORTED),
            android(ModelKind.CHAT, EngineKind.LLAMA, files = false),
        ).forEach { launch ->
            assertFalse(launch.opens)
            assertNotNull(launch.problem)
        }
        // The task is still known, so the message can name it.
        assertEquals(ModelTask.IMAGINE, ios(ModelKind.IMAGE, EngineKind.DIFFUSION).task)
        // A tight or too-big fit is the user's call, as on the catalog rows.
        assertTrue(android(ModelKind.IMAGE, EngineKind.DIFFUSION, fit = Fit.TOO_BIG).opens)
    }
}
