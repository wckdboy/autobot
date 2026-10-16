package dev.wckdboy.autobot.providers.remote

import android.app.Application
import android.content.ComponentName
import android.os.DeadObjectException
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.wckdboy.autobot.core.data.ModelRowRepository
import dev.wckdboy.autobot.core.data.db.AutobotDatabase
import dev.wckdboy.autobot.core.data.db.DatabaseHolder
import dev.wckdboy.autobot.core.models.ComputeProfile
import dev.wckdboy.autobot.core.models.EngineKind
import dev.wckdboy.autobot.core.models.FileRole
import dev.wckdboy.autobot.core.models.ModelFile
import dev.wckdboy.autobot.core.models.ModelFormat
import dev.wckdboy.autobot.core.models.ModelKind
import dev.wckdboy.autobot.core.models.ModelLibrary
import dev.wckdboy.autobot.core.models.ModelManifest
import dev.wckdboy.autobot.core.models.ModelPlan
import dev.wckdboy.autobot.core.models.ModelSource
import dev.wckdboy.autobot.core.models.ModelStatus
import dev.wckdboy.autobot.core.security.KeyManager
import dev.wckdboy.autobot.engine.llama.ILlamaCallback
import dev.wckdboy.autobot.engine.llama.ILlamaEngine
import dev.wckdboy.autobot.engine.llama.LlamaService
import dev.wckdboy.autobot.engine.llama.LocalLlm
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * A chat on a downloaded model when things go wrong, as the user sees it: the file deleted outside
 * the app, the engine dying mid-answer, a file the engine cannot load. The registry is real (Room),
 * the engine a fake binder; every wait is bounded.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LocalChatFailuresTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var db: AutobotDatabase
    private lateinit var rows: ModelRowRepository
    private lateinit var library: ModelLibrary

    private class FakeEngine : ILlamaEngine.Stub() {
        @Volatile var alive = true
        val recipients = CopyOnWriteArrayList<IBinder.DeathRecipient>()
        val callbacks = CopyOnWriteArrayList<ILlamaCallback>()

        override fun isBinderAlive() = alive
        override fun pingBinder() = alive

        override fun linkToDeath(recipient: IBinder.DeathRecipient, flags: Int) {
            if (!alive) throw DeadObjectException()
            recipients += recipient
        }

        override fun unlinkToDeath(recipient: IBinder.DeathRecipient, flags: Int): Boolean = recipients.remove(recipient)
        override fun systemInfo() = "fake"
        override fun devices() = "[]"

        override fun chat(modelPath: String, nCtx: Int, backend: String, request: ParcelFileDescriptor, callback: ILlamaCallback) {
            request.close()
            callbacks += callback
        }

        override fun cancel() = Unit
        override fun unload() = Unit
        override fun embed(modelPath: String, texts: Array<String>): FloatArray? = null

        fun die() {
            alive = false
            recipients.forEach { it.binderDied() }
        }
    }

    private val engine = FakeEngine()

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(app, AutobotDatabase::class.java).build()
        rows = ModelRowRepository(DatabaseHolder(app, KeyManager(app)).apply { useForTesting(db) }, Dispatchers.IO)
        library = ModelLibrary(app, rows, scope)
        shadowOf(app).setComponentNameAndServiceForBindService(ComponentName(app, LlamaService::class.java), engine)
    }

    @After
    fun tearDown() {
        scope.cancel()
        db.close()
    }

    /** A ready chat model whose file is on disk. */
    private suspend fun installed(id: String = "qwen"): File {
        val file = ModelFile(FileRole.MODEL, "q.gguf", "https://huggingface.co/x/y/resolve/main/q.gguf", 4)
        library.add(
            ModelPlan(id, ModelKind.CHAT, ModelSource.HUGGING_FACE, "Qwen", "", ModelFormat.GGUF, EngineKind.LLAMA, manifest = ModelManifest(listOf(file))),
        )
        rows.updateProgress(id, 4, ModelStatus.READY.name, null)
        return library.fileOf(id, file).apply {
            parentFile!!.mkdirs()
            writeBytes(byteArrayOf(1, 2, 3, 4))
        }
    }

    private fun provider() = LocalChatProvider(library, LocalLlm(app), ComputeProfile(rows), system = null)

    private fun request(model: String = "qwen") = ChatRequest(model = model, messages = listOf(ChatMessage(ChatRole.USER, "hi")))

    /** Runs [block] off the main thread while this (main) thread turns Android's main looper, where binder connections arrive. */
    private fun <T> withLooper(block: suspend CoroutineScope.() -> T): T {
        val work = scope.async { block() }
        waitFor { work.isCompleted }
        return runBlocking { work.await() }
    }

    /** Turns the main looper until [condition] holds; fails after 20 s. */
    private fun waitFor(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
        while (!condition()) {
            check(System.nanoTime() < deadline) { "timed out" }
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
    }

    @Test
    fun aModelFileDeletedOutsideTheAppIsReportedWithoutStartingTheEngine() {
        val events = withLooper {
            installed().delete()
            provider().streamChat(request()).toList()
        }
        val error = events.single() as ChatEvent.Error
        assertTrue(error.message, error.message.contains("missing") && error.message.contains("Download it again"))
        assertTrue("the engine was never started", shadowOf(app).boundServiceConnections.isEmpty())
    }

    @Test
    fun theEngineDyingMidAnswerEndsTheChatWithAClearError() {
        runBlocking { installed() }
        val running = scope.async { provider().streamChat(request()).toList() }
        waitFor { engine.callbacks.isNotEmpty() }
        engine.callbacks.single().onDelta(0, "Hel")
        engine.die()
        val events = withLooper { running.await() }

        assertEquals(ChatEvent.ContentDelta("Hel"), events.first())
        val error = events.last() as ChatEvent.Error
        assertEquals("ENGINE_DIED", error.code)
        assertTrue(error.message, error.message.contains("stopped"))
    }

    @Test
    fun aFileTheEngineCannotLoadSaysWhy() {
        runBlocking { installed() }
        val running = scope.async { provider().streamChat(request()).toList() }
        waitFor { engine.callbacks.isNotEmpty() }
        engine.callbacks.single().onResult("""{"error":{"code":"LOAD_FAILED","message":"the model file is incomplete or corrupt: download it again in the Models tab"}}""")
        val events = withLooper { running.await() }

        val error = events.last() as ChatEvent.Error
        assertTrue(error.message, error.message.contains("incomplete or corrupt"))
    }
}
