package dev.wckdboy.autobot.feature.studio.ui

import android.graphics.ImageDecoder
import android.graphics.SurfaceTexture
import android.graphics.drawable.AnimatedImageDrawable
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.view.Surface
import android.view.TextureView
import android.widget.ImageView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import dev.wckdboy.autobot.core.designsystem.component.GhostButton
import dev.wckdboy.autobot.core.designsystem.component.MicroLabel
import dev.wckdboy.autobot.core.designsystem.component.Tag
import dev.wckdboy.autobot.core.designsystem.theme.AutobotTheme
import dev.wckdboy.autobot.feature.studio.media.ByteDataSource
import java.nio.ByteBuffer
import kotlinx.coroutines.delay

/**
 * One MediaPlayer over decrypted bytes in memory (nothing is written to disk in the clear), with
 * play / pause, seek, loop and an optional play range (A/B compare, trim preview). Released when
 * it leaves the composition.
 */
@Stable
class BytePlayer internal constructor() {
    private var player: MediaPlayer? = null
    private var source: ByteArray? = null
    var playing by mutableStateOf(false)
        private set
    var positionMs by mutableLongStateOf(0L)
        private set
    var durationMs by mutableLongStateOf(0L)
        private set
    var videoAspect by mutableFloatStateOf(0f)
        private set
    var loop by mutableStateOf(true)
    var error by mutableStateOf<String?>(null)
        private set

    /** Play only this part (ms); null = all. */
    var range: LongRange? = null

    private var surface: Surface? = null

    fun load(bytes: ByteArray) {
        if (source === bytes) return
        release()
        source = bytes
        error = null
        val p = MediaPlayer()
        try {
            p.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            p.setDataSource(ByteDataSource(bytes))
            surface?.let(p::setSurface)
            p.setOnVideoSizeChangedListener { _, w, h -> if (w > 0 && h > 0) videoAspect = w.toFloat() / h }
            p.setOnCompletionListener {
                if (loop) {
                    it.seekTo((range?.first ?: 0L).toInt())
                    it.start()
                } else {
                    playing = false
                }
            }
            p.prepare()
            durationMs = p.duration.toLong().coerceAtLeast(0)
            player = p
        } catch (e: Exception) {
            runCatching { p.release() }
            error = "Can't play this file"
        }
    }

    fun attach(s: Surface?) {
        surface = s
        runCatching { player?.setSurface(s) }
    }

    fun toggle() = if (playing) pause() else play()

    fun play() {
        val p = player ?: return
        range?.let { r -> if (p.currentPosition < r.first || p.currentPosition >= r.last) p.seekTo(r.first.toInt()) }
        p.start()
        playing = true
    }

    fun pause() {
        runCatching { player?.pause() }
        playing = false
    }

    fun seek(ms: Long) {
        runCatching { player?.seekTo(ms, MediaPlayer.SEEK_CLOSEST) }
        positionMs = ms
    }

    internal fun tick() {
        val p = player ?: return
        positionMs = runCatching { p.currentPosition.toLong() }.getOrDefault(positionMs)
        val r = range
        if (playing && r != null && positionMs >= r.last) {
            if (loop) p.seekTo(r.first.toInt()) else pause()
        }
    }

    fun release() {
        runCatching { player?.release() }
        player = null
        source = null
        playing = false
        positionMs = 0
    }
}

@Composable
fun rememberBytePlayer(): BytePlayer {
    val player = remember { BytePlayer() }
    DisposableEffect(player) { onDispose { player.release() } }
    LaunchedEffect(player) {
        while (true) {
            if (player.playing) player.tick()
            delay(50)
        }
    }
    return player
}

/** Play / pause, the scrub bar and the loop switch. */
@Composable
fun Transport(player: BytePlayer, modifier: Modifier = Modifier, extra: @Composable () -> Unit = {}) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        val total = player.durationMs.coerceAtLeast(1)
        Slider(
            value = (player.positionMs.toFloat() / total).coerceIn(0f, 1f),
            onValueChange = { player.seek((it * total).toLong()) },
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Position ${duration(player.positionMs)} of ${duration(player.durationMs)}" },
            colors = SliderDefaults.colors(thumbColor = MaterialTheme.colorScheme.primary, activeTrackColor = MaterialTheme.colorScheme.primary),
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GhostButton(if (player.playing) "Pause" else "Play", player::toggle, accent = MaterialTheme.colorScheme.primary)
            Text("${duration(player.positionMs)} / ${duration(player.durationMs)}", style = AutobotTheme.styles.readout)
            Box(Modifier.weight(1f))
            Tag(if (player.loop) "loop on" else "loop off", accent = if (player.loop) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant, filled = player.loop, onClick = { player.loop = !player.loop })
            extra()
        }
        player.error?.let { MicroLabel(it, color = MaterialTheme.colorScheme.error) }
    }
}

/**
 * A video asset: MP4 / WEBM on a TextureView through [player]; animated WEBP / GIF (what many
 * ComfyUI video workflows save) as an animated drawable that loops by itself.
 */
@Composable
fun VideoSurface(bytes: ByteArray, mimeType: String, player: BytePlayer, modifier: Modifier = Modifier) {
    if (mimeType.startsWith("image/")) {
        AndroidView(
            factory = { ctx -> ImageView(ctx).apply { scaleType = ImageView.ScaleType.FIT_CENTER } },
            update = { view ->
                if (view.tag !== bytes) {
                    view.tag = bytes
                    val drawable = runCatching { ImageDecoder.decodeDrawable(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) }.getOrNull()
                    view.setImageDrawable(drawable)
                    (drawable as? AnimatedImageDrawable)?.start()
                }
            },
            modifier = modifier.background(MaterialTheme.colorScheme.surfaceContainerLowest),
        )
        return
    }
    LaunchedEffect(bytes) { player.load(bytes) }
    val aspect = player.videoAspect.takeIf { it > 0f } ?: (16f / 9f)
    Box(modifier.background(MaterialTheme.colorScheme.surfaceContainerLowest), contentAlignment = Alignment.Center) {
        AndroidView(
            factory = { ctx ->
                TextureView(ctx).apply {
                    surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                        override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) = player.attach(Surface(st))
                        override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) = Unit
                        override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                            player.attach(null)
                            return true
                        }

                        override fun onSurfaceTextureUpdated(st: SurfaceTexture) = Unit
                    }
                }
            },
            modifier = Modifier.fillMaxWidth().aspectRatio(aspect),
        )
    }
}

/** A thin spacer line for player sections. */
@Composable
fun PlayerGap() = Box(Modifier.height(4.dp))
