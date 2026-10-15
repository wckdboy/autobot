package dev.wckdboy.autobot.engine.systemllm

import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.common.GenAiException
import com.google.mlkit.genai.prompt.GenerateContentRequest
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.GenerativeModel
import com.google.mlkit.genai.prompt.SystemInstruction
import com.google.mlkit.genai.prompt.TextPart
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.wckdboy.autobot.providers.remote.ChatEvent
import dev.wckdboy.autobot.providers.remote.ErrorKind
import dev.wckdboy.autobot.providers.remote.SystemAvailability
import dev.wckdboy.autobot.providers.remote.SystemLlm
import dev.wckdboy.autobot.providers.remote.SystemModelStatus
import dev.wckdboy.autobot.providers.remote.SystemModels
import dev.wckdboy.autobot.providers.remote.SystemPrompt
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Gemini Nano through ML Kit's Prompt API. Inference runs in the AICore system app on this phone;
 * Autobot sends it nothing but the prompt. ML Kit itself reports usage metrics to Google, which is
 * why this lives only in the opt-in `playServices` build.
 *
 * AICore answers only while the app is in the foreground, and its model is downloaded by the
 * system (outside Autobot's network policy) — only when the user asks for it.
 */
@Singleton
class GeminiNano @Inject constructor() : SystemLlm {
    override val id: String = SystemModels.GEMINI_NANO

    private val client: GenerativeModel by lazy { Generation.getClient() }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private var download: Job? = null

    @Volatile
    private var progress: Download? = null

    private data class Download(val total: Long, val done: Long, val error: String? = null)

    override suspend fun status(): SystemModelStatus {
        val feature = try {
            client.checkStatus()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return GeminiNanoMapping.unavailable(e.message ?: "AICore is not reachable")
        }
        val d = progress
        return GeminiNanoMapping.status(feature, d?.let { if (it.total > 0) it.done.toFloat() / it.total else null }, d?.error)
    }

    override suspend fun requestDownload() {
        lock.withLock {
            if (download?.isActive == true) return
            progress = null
            download = scope.launch {
                client.download().collect { s ->
                    progress = when (s) {
                        is DownloadStatus.DownloadStarted -> Download(s.bytesToDownload, 0)
                        is DownloadStatus.DownloadProgress -> Download(progress?.total ?: 0, s.totalBytesDownloaded)
                        is DownloadStatus.DownloadFailed -> Download(0, 0, GeminiNanoMapping.describe(s.e.errorCode, s.e.message))
                        else -> null
                    }
                }
            }
        }
    }

    override fun chat(prompt: SystemPrompt): Flow<ChatEvent> = flow {
        val separate = runCatching { client.isSystemPromptAvailable() }.getOrDefault(false)
        val text = TextPart(prompt.flatten(withInstructions = !separate))
        val instructions = prompt.instructions?.takeIf { separate }
        val request = (if (instructions != null) GenerateContentRequest.Builder(SystemInstruction(instructions), text) else GenerateContentRequest.Builder(text))
            .apply {
                temperature = prompt.temperature?.toFloat()
                maxOutputTokens = prompt.maxTokens
            }
            .build()
        val promptTokens = runCatching { client.countTokens(request).totalTokens }.getOrNull()
        val output = StringBuilder()
        var finish: Int? = null
        try {
            client.generateContentStream(request).collect { chunk ->
                val candidate = chunk.candidates.firstOrNull() ?: return@collect
                if (candidate.text.isNotEmpty()) {
                    output.append(candidate.text)
                    emit(ChatEvent.ContentDelta(candidate.text))
                }
                candidate.finishReason?.let { finish = it }
            }
        } catch (e: GenAiException) {
            emit(GeminiNanoMapping.error(e.errorCode, e.message))
            return@flow
        }
        val completion = runCatching { client.countTokens(GenerateContentRequest.Builder(TextPart(output.toString())).build()).totalTokens }.getOrNull()
        emit(ChatEvent.Usage(promptTokens, completion, if (promptTokens != null && completion != null) promptTokens + completion else null))
        emit(ChatEvent.Finish(GeminiNanoMapping.finishReason(finish)))
        emit(ChatEvent.Done)
    }.flowOn(Dispatchers.IO)
}

/** ML Kit's codes in the shared shapes; pure, so it is unit tested. */
internal object GeminiNanoMapping {
    private const val TITLE = "Gemini Nano (on-device)"

    fun status(feature: Int, downloaded: Float?, downloadError: String?): SystemModelStatus = when (feature) {
        FeatureStatus.AVAILABLE -> SystemModelStatus(SystemModels.GEMINI_NANO, TITLE, SystemAvailability.AVAILABLE)
        FeatureStatus.DOWNLOADING -> SystemModelStatus(
            SystemModels.GEMINI_NANO,
            TITLE,
            SystemAvailability.DOWNLOADING,
            downloaded?.let { "${(it * 100).toInt().coerceIn(0, 100)}%" },
        )
        FeatureStatus.DOWNLOADABLE -> SystemModelStatus(SystemModels.GEMINI_NANO, TITLE, SystemAvailability.DOWNLOADABLE, downloadError)
        else -> unavailable("this phone does not support Gemini Nano (needs AICore)")
    }

    fun unavailable(detail: String) = SystemModelStatus(SystemModels.GEMINI_NANO, TITLE, SystemAvailability.UNAVAILABLE, detail)

    fun error(code: Int, message: String?): ChatEvent.Error {
        val text = describe(code, message)
        return when (code) {
            GenAiException.ErrorCode.REQUEST_TOO_LARGE ->
                ChatEvent.Error(ErrorKind.BAD_REQUEST, "context length exceeded: $text", code = "CONTEXT_WINDOW_EXCEEDED")
            GenAiException.ErrorCode.BUSY, GenAiException.ErrorCode.PER_APP_BATTERY_USE_QUOTA_EXCEEDED ->
                ChatEvent.Error(ErrorKind.RATE_LIMITED, text)
            else -> ChatEvent.Error(ErrorKind.BAD_REQUEST, text)
        }
    }

    fun describe(code: Int, message: String?): String = when (code) {
        GenAiException.ErrorCode.REQUEST_TOO_LARGE -> "the conversation is too long for Gemini Nano"
        GenAiException.ErrorCode.BUSY -> "Gemini Nano is busy, try again in a moment"
        GenAiException.ErrorCode.PER_APP_BATTERY_USE_QUOTA_EXCEEDED -> "Gemini Nano's daily limit for this app is used up"
        GenAiException.ErrorCode.BACKGROUND_USE_BLOCKED -> "Gemini Nano only answers while Autobot is on screen"
        GenAiException.ErrorCode.NOT_AVAILABLE -> "Gemini Nano is not available (not downloaded yet?)"
        GenAiException.ErrorCode.NOT_ENOUGH_DISK_SPACE -> "not enough free storage for Gemini Nano"
        GenAiException.ErrorCode.NEEDS_SYSTEM_UPDATE, GenAiException.ErrorCode.AICORE_INCOMPATIBLE -> "AICore needs a system update"
        GenAiException.ErrorCode.RESPONSE_GENERATION_ERROR -> "Gemini Nano declined to answer" + message?.let { ": $it" }.orEmpty()
        else -> message ?: "Gemini Nano failed (code $code)"
    }

    /** ML Kit's candidate finish reasons: 0 stop, 1 max tokens. */
    fun finishReason(reason: Int?): String = if (reason == 1) "length" else "stop"
}

@Module
@InstallIn(SingletonComponent::class)
interface SystemLlmModule {
    @Binds
    fun geminiNano(impl: GeminiNano): SystemLlm
}
