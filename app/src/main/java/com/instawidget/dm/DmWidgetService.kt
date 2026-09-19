package com.instawidget.dm

import android.content.Context
import android.content.Intent
import android.text.format.DateUtils
import android.view.View
import android.widget.RemoteViews
import android.widget.RemoteViewsService

/** Supplies the rows for the widget's scrollable list. */
class DmWidgetService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory =
        DmRemoteViewsFactory(applicationContext)
}

private class DmRemoteViewsFactory(
    private val context: Context
) : RemoteViewsService.RemoteViewsFactory {

    private var messages: List<DmMessage> = emptyList()

    override fun onCreate() = Unit

    /**
     * Called on the launcher's behalf whenever the data set is invalidated.
     * Reading SharedPreferences here is fine: this runs off the main thread.
     */
    override fun onDataSetChanged() {
        messages = DmStore.load(context)
    }

    override fun onDestroy() {
        messages = emptyList()
    }

    override fun getCount(): Int = messages.size

    override fun getViewAt(position: Int): RemoteViews? {
        val message = messages.getOrNull(position) ?: return null
        val views = RemoteViews(context.packageName, R.layout.widget_dm_row)
        views.setImageViewBitmap(R.id.row_avatar, Avatars.forSender(context, message.sender))
        views.setTextViewText(R.id.row_sender, message.sender)
        views.setTextViewText(R.id.row_preview, message.preview)
        views.setTextViewText(R.id.row_time, relativeTime(message.postedAt))

        // The badge counts messages folded into this row since the user last
        // opened the inbox from the widget, so a single message shows nothing.
        if (message.count > 1) {
            views.setViewVisibility(R.id.row_count, View.VISIBLE)
            views.setTextViewText(R.id.row_count, formatCount(message.count))
        } else {
            views.setViewVisibility(R.id.row_count, View.GONE)
        }

        // The template PendingIntent lives on the provider; the fill-in adds
        // who this row is, which OpenInboxActivity uses to aim at the exact
        // conversation when that option is on.
        views.setOnClickFillInIntent(
            R.id.row_root,
            Intent().putExtra(OpenInboxActivity.EXTRA_SENDER, message.sender)
        )
        return views
    }

    override fun getLoadingView(): RemoteViews? = null

    override fun getViewTypeCount(): Int = 1

    override fun getItemId(position: Int): Long =
        messages.getOrNull(position)?.conversationKey()?.hashCode()?.toLong() ?: position.toLong()

    override fun hasStableIds(): Boolean = true

    private fun formatCount(count: Int): String = if (count > 9) "9+" else count.toString()

    private fun relativeTime(postedAt: Long): CharSequence {
        if (postedAt <= 0L) return ""
        return DateUtils.getRelativeTimeSpanString(
            postedAt,
            System.currentTimeMillis(),
            DateUtils.MINUTE_IN_MILLIS,
            DateUtils.FORMAT_ABBREV_RELATIVE
        )
    }
}
