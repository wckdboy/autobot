package dev.wckdboy.autobot.entry

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import dev.wckdboy.autobot.MainActivity

/**
 * Quick Settings tile "Autobot voice": opens a new chat in voice mode. On a locked phone the
 * system asks for the device unlock first, then the app's own lock applies. The tile never shows
 * anything from a conversation.
 */
class VoiceTileService : TileService() {

    override fun onStartListening() {
        qsTile?.apply {
            state = Tile.STATE_INACTIVE
            updateTile()
        }
    }

    override fun onClick() {
        val open = Runnable { startActivityAndCollapse(entryIntent(this, EntryActions.VOICE)) }
        if (isLocked) unlockAndRun(open) else open.run()
    }
}

/** A [PendingIntent] that opens the app on [action] (tile, widget). */
internal fun entryIntent(context: Context, action: String): PendingIntent {
    val intent = Intent(context, MainActivity::class.java)
        .setAction(action)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    return PendingIntent.getActivity(context, action.hashCode(), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
}
