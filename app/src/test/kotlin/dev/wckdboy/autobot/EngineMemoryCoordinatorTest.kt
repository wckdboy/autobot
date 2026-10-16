package dev.wckdboy.autobot

import android.app.Application
import android.content.ComponentCallbacks2
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ApplicationProvider
import dev.wckdboy.autobot.engine.audiogen.LocalAudioGen
import dev.wckdboy.autobot.engine.diffusion.ISdCallback
import dev.wckdboy.autobot.engine.diffusion.ISdEngine
import dev.wckdboy.autobot.engine.diffusion.LocalSd
import dev.wckdboy.autobot.engine.diffusion.SdBundle
import dev.wckdboy.autobot.engine.diffusion.SdRequest
import dev.wckdboy.autobot.engine.diffusion.SdService
import dev.wckdboy.autobot.engine.llama.ILlamaCallback
import dev.wckdboy.autobot.engine.llama.ILlamaEngine
import dev.wckdboy.autobot.engine.llama.LlamaService
import dev.wckdboy.autobot.engine.llama.LocalLlm
import dev.wckdboy.autobot.engine.npu.LocalNpu
import dev.wckdboy.autobot.engine.speech.LocalSpeech
import dev.wckdboy.autobot.engine.tts.LocalVoice
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * [EngineMemoryCoordinator] with the real engine clients over fake engine binders: starting an
 * image job makes room by unloading the chat model, memory pressure unloads idle engines, and a
 * busy engine keeps its model through an app switch.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class EngineMemoryCoordinatorTest {
    private val app: Application = ApplicationProvider.getApplicationContext()

    private class FakeLlama : ILlamaEngine.Stub() {
        val callbacks = CopyOnWriteArrayList<ILlamaCallback>()
        var unloads = 0
        override fun linkToDeath(recipient: IBinder.DeathRecipient, flags: Int) = Unit
        override fun unlinkToDeath(recipient: IBinder.DeathRecipient, flags: Int) = true
        override fun systemInfo() = "fake"
        override fun devices() = "[]"
        override fun chat(modelPath: String, nCtx: Int, backend: String, request: ParcelFileDescriptor, callback: ILlamaCallback) {
            request.close()
            callbacks += callback
        }
        override fun cancel() = Unit
        override fun unload() {
            unloads++
        }
        override fun embed(modelPath: String, texts: Array<String>): FloatArray? = null
        override fun cancelChat(callback: ILlamaCallback) = Unit
        override fun prepare(modelPath: String, nCtx: Int, backend: String) = Unit
    }

    private class FakeSd : ISdEngine.Stub() {
        val callbacks = CopyOnWriteArrayList<ISdCallback>()
        var unloads = 0
        override fun linkToDeath(recipient: IBinder.DeathRecipient, flags: Int) = Unit
        override fun unlinkToDeath(recipient: IBinder.DeathRecipient, flags: Int) = true
        override fun systemInfo() = "fake"
        override fun generate(request: Bundle, callback: ISdCallback) {
            callbacks += callback
        }
        override fun cancel() = Unit
        override fun unload() {
            unloads++
        }
    }

    private val llamaEngine = FakeLlama()
    private val sdEngine = FakeSd()

    private class Engines(val llm: LocalLlm, val sd: LocalSd, val coordinator: EngineMemoryCoordinator)

    private fun TestScope.engines(): Engines {
        // One binder per engine service (a plain component registration would answer every bind).
        shadowOf(app).setComponentNameAndServiceForBindServiceForIntent(Intent(app, LlamaService::class.java), ComponentName(app, LlamaService::class.java), llamaEngine)
        shadowOf(app).setComponentNameAndServiceForBindServiceForIntent(Intent(app, SdService::class.java), ComponentName(app, SdService::class.java), sdEngine)
        val llm = LocalLlm(app, idleMillis = 60_000, io = StandardTestDispatcher(testScheduler))
        val sd = LocalSd(app)
        val coordinator = EngineMemoryCoordinator(app, backgroundScope, llm, sd, LocalNpu(app), LocalSpeech(app), LocalVoice(), LocalAudioGen())
        coordinator.install()
        return Engines(llm, sd, coordinator)
    }

    private fun TestScope.pump() {
        repeat(3) {
            runCurrent()
            shadowOf(Looper.getMainLooper()).idle()
        }
        runCurrent()
    }

    private fun CoroutineScope.chat(llm: LocalLlm): Job = launch { runCatching { llm.chat("/m.gguf", 2048, "cpu", "{}").collect {} } }

    private fun CoroutineScope.image(sd: LocalSd): Job = launch { runCatching { sd.generate(SdRequest(SdBundle(model = "/m.safetensors"), "a fox")).collect {} } }

    private val llamaBinding get() = shadowOf(app).boundServiceConnections.size

    @Test
    fun startingAnImageJobUnloadsTheIdleChatModelAndEndsItsProcess() = runTest(timeout = 10.seconds) {
        val e = engines()
        val chat = chat(e.llm)
        pump()
        llamaEngine.callbacks.single().onResult("{}")
        pump()
        assertTrue(chat.isCompleted)
        assertEquals(0, llamaEngine.unloads)

        val image = image(e.sd)
        pump()

        assertEquals("the chat model makes room", 1, llamaEngine.unloads)
        assertEquals(1, sdEngine.callbacks.size)
        // The idle chat engine was let go; only the image engine is still bound.
        assertEquals(1, llamaBinding)
        sdEngine.callbacks.single().onDone(null)
        pump()
        assertTrue(image.isCompleted)
    }

    @Test
    fun theChatModelMakesRoomForTheImageModelToo() = runTest(timeout = 10.seconds) {
        val e = engines()
        val image = image(e.sd)
        pump()
        sdEngine.callbacks.single().onDone(null)
        pump()
        assertTrue(image.isCompleted)

        val chat = chat(e.llm)
        pump()
        assertEquals(1, sdEngine.unloads)
        llamaEngine.callbacks.single().onResult("{}")
        pump()
        assertTrue(chat.isCompleted)
    }

    @Test
    fun memoryPressureUnloadsIdleEnginesButAnAppSwitchKeepsTheChatModel() = runTest(timeout = 10.seconds) {
        val e = engines()
        val chat = chat(e.llm)
        pump()
        llamaEngine.callbacks.single().onResult("{}")
        pump()
        assertTrue(chat.isCompleted)

        e.coordinator.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_BACKGROUND)
        pump()
        assertEquals("an app switch keeps the chat model", 0, llamaEngine.unloads)

        e.coordinator.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_MODERATE)
        pump()
        assertEquals(1, llamaEngine.unloads)
        assertEquals("and its process is let go", 0, llamaBinding)
    }

    @Test
    fun aBusyEngineKeepsItsModelUnderPressure() = runTest(timeout = 10.seconds) {
        val e = engines()
        val chat = chat(e.llm)
        pump()
        @Suppress("DEPRECATION")
        e.coordinator.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL)
        pump()
        assertEquals(0, llamaEngine.unloads)
        assertEquals(1, llamaBinding)
        llamaEngine.callbacks.single().onResult("{}")
        pump()
        assertTrue(chat.isCompleted)
    }
}
