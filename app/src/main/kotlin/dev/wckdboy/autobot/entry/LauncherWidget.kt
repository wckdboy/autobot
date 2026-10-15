package dev.wckdboy.autobot.entry

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.widget.RemoteViews
import dev.wckdboy.autobot.R

/**
 * Home screen launcher: Ask · Voice · Imagine. Static by design: it never shows a title, a
 * message or anything else from the user's conversations.
 */
class LauncherWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val views = RemoteViews(context.packageName, R.layout.widget_launcher).apply {
            setOnClickPendingIntent(R.id.widget_ask, entryIntent(context, EntryActions.NEW_CHAT))
            setOnClickPendingIntent(R.id.widget_voice, entryIntent(context, EntryActions.VOICE))
            setOnClickPendingIntent(R.id.widget_imagine, entryIntent(context, EntryActions.IMAGINE))
        }
        manager.updateAppWidget(ids, views)
    }
}
