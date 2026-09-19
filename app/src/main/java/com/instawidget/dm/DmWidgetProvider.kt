package com.instawidget.dm

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
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
            // Tell the collection its data changed, then redraw the chrome.
            manager.notifyAppWidgetViewDataChanged(ids, R.id.dm_list)
            onUpdate(context, manager, ids)
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

        // Tapping the header opens the Instagram inbox.
        views.setOnClickPendingIntent(R.id.widget_header, inboxPendingIntent(context))

        // Rows fill in this template; every row opens the same inbox because
        // Instagram exposes no per-thread deep link.
        views.setPendingIntentTemplate(R.id.dm_list, inboxPendingIntent(context))

        // The empty state should be tappable too.
        views.setOnClickPendingIntent(R.id.dm_empty, emptyStatePendingIntent(context))

        return views
    }

    private fun inboxPendingIntent(context: Context): PendingIntent = PendingIntent.getActivity(
        context,
        REQUEST_INBOX,
        Instagram.inboxIntent(context),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    /**
     * With no messages cached the likely reason is that notification access was
     * never granted, so the empty state goes to the setup screen instead.
     */
    private fun emptyStatePendingIntent(context: Context): PendingIntent {
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

        /** Redraws every placed instance of the widget. */
        fun refreshAll(context: Context) {
            val intent = Intent(context, DmWidgetProvider::class.java).setAction(ACTION_REFRESH)
            context.sendBroadcast(intent)
        }
    }
}
