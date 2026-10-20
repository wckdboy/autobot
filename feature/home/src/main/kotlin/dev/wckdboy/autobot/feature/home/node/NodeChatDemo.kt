package dev.wckdboy.autobot.feature.home.node

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import dev.wckdboy.autobot.shared.invincible.NodeBotDetails
import dev.wckdboy.autobot.shared.invincible.NodeChatSample
import java.io.ByteArrayOutputStream

/** The demo node's chat (`--ez demo true`): [NodeChatSample], with a drawn photo, as iOS shows it. */
internal object NodeChatDemo {
    val info = NodeChatSample.info
    const val ANSWER = NodeChatSample.FOLLOW_ANSWER
    const val ROUTE = NodeChatSample.ROUTE

    fun rows(photo: ByteArray): List<AgentRow> = listOf(
        AgentRow.User("d0", NodeChatSample.QUESTION, listOf(photo)),
        AgentRow.Note("d1", "read_file notes/lab.md", AgentRow.Tone.TOOL),
        AgentRow.Reply("d2", NodeChatSample.ANSWER, NodeChatSample.ROUTE),
        AgentRow.Kept("d3", NodeBotDetails.kept(NodeChatSample.KEPT)),
        AgentRow.User("d4", NodeChatSample.FOLLOW_UP),
        AgentRow.Reply("d5", NodeChatSample.FOLLOW_ANSWER, NodeChatSample.ROUTE),
    )

    /** A shelf, roughly: a warm gradient, three boards and a dark band (no real photo in the app). */
    fun photo(width: Int = 960, height: Int = 720): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val w = width.toFloat()
        val h = height.toFloat()
        canvas.drawRect(0f, 0f, w, h, Paint().apply { shader = LinearGradient(0f, 0f, w, h, Color.rgb(196, 160, 120), Color.rgb(70, 58, 50), Shader.TileMode.CLAMP) })
        val shelf = Paint().apply { color = Color.rgb(48, 40, 34) }
        canvas.drawRect(0f, h * 0.70f, w, h * 0.76f, shelf)
        val board = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(46, 110, 74) }
        for (i in 0 until 3) {
            val x = w * (0.14f + i * 0.26f)
            canvas.drawRect(x, h * 0.40f, x + w * 0.18f, h * 0.70f, board)
        }
        return ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
            bitmap.recycle()
            out.toByteArray()
        }
    }
}
