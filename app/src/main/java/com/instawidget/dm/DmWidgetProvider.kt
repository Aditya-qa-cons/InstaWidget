package com.instawidget.dm

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.RemoteViews

/** The home screen widget: a header plus a scrollable list of DM previews. */
class DmWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        for (appWidgetId in appWidgetIds) {
            appWidgetManager.updateAppWidget(appWidgetId, buildViews(context, appWidgetId))
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_REFRESH) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, DmWidgetProvider::class.java))
            // Redraw the chrome first: updateAppWidget re-attaches the remote
            // adapter, and a notify issued before that can be dropped when the
            // adapter is replaced. Notifying afterwards is what actually makes
            // the factory re-read the cache.
            onUpdate(context, manager, ids)
            manager.notifyAppWidgetViewDataChanged(ids, R.id.dm_list)
            return
        }
        super.onReceive(context, intent)
    }

    private fun buildViews(context: Context, appWidgetId: Int): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_dm_list)

        // Data source for the scrollable list.
        val serviceIntent = Intent(context, DmWidgetService::class.java).apply {
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            // A unique data URI keeps RemoteViewsService from reusing one
            // factory across widget instances.
            data = Uri.parse(toUri(Intent.URI_INTENT_SCHEME))
        }
        views.setRemoteAdapter(R.id.dm_list, serviceIntent)
        views.setEmptyView(R.id.dm_list, R.id.dm_empty)

        // Every part of the widget opens the Instagram inbox: the header, each
        // row, and the empty state.
        views.setOnClickPendingIntent(R.id.widget_header, inboxPendingIntent(context))
        views.setOnClickPendingIntent(R.id.dm_empty, inboxPendingIntent(context))

        // Rows fill in this template; every row opens the same inbox because
        // Instagram exposes no per-thread deep link.
        views.setPendingIntentTemplate(R.id.dm_list, inboxTemplatePendingIntent(context))

        // The small gear is the one exception, so the setup screen stays
        // reachable from the home screen without hijacking the widget's tap.
        views.setOnClickPendingIntent(R.id.widget_setup, setupPendingIntent(context))

        return views
    }

    private fun inboxPendingIntent(context: Context): PendingIntent = PendingIntent.getActivity(
        context,
        REQUEST_INBOX,
        Instagram.inboxIntent(context),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    /**
     * Template behind the list rows.
     *
     * A collection's template has to be *mutable*, because the launcher merges
     * each row's fill-in intent into it; an immutable one silently ignores the
     * fill-in. It also needs its own request code so it does not collide with
     * the immutable header PendingIntent, which is otherwise an identical
     * intent and would be overwritten by FLAG_UPDATE_CURRENT.
     */
    private fun inboxTemplatePendingIntent(context: Context): PendingIntent {
        var flags = PendingIntent.FLAG_UPDATE_CURRENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            flags = flags or PendingIntent.FLAG_MUTABLE
        }
        return PendingIntent.getActivity(
            context,
            REQUEST_INBOX_TEMPLATE,
            Instagram.inboxIntent(context),
            flags
        )
    }

    /** Gear in the header corner; the only tap that does not open Instagram. */
    private fun setupPendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, SetupActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return PendingIntent.getActivity(
            context,
            REQUEST_SETUP,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    companion object {
        private const val ACTION_REFRESH = "com.instawidget.dm.action.REFRESH"
        private const val REQUEST_INBOX = 1
        private const val REQUEST_SETUP = 2
        private const val REQUEST_INBOX_TEMPLATE = 3

        /** Redraws every placed instance of the widget. */
        fun refreshAll(context: Context) {
            val intent = Intent(context, DmWidgetProvider::class.java).setAction(ACTION_REFRESH)
            context.sendBroadcast(intent)
        }
    }
}
