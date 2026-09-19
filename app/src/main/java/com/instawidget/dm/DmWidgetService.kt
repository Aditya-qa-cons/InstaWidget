package com.instawidget.dm

import android.content.Context
import android.content.Intent
import android.text.format.DateUtils
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

        // The template PendingIntent lives on the provider; rows only need to
        // opt in. There is no per-thread deep link to pass along, so the
        // fill-in intent carries nothing but the click itself.
        views.setOnClickFillInIntent(R.id.row_root, Intent())
        return views
    }

    override fun getLoadingView(): RemoteViews? = null

    override fun getViewTypeCount(): Int = 1

    override fun getItemId(position: Int): Long =
        messages.getOrNull(position)?.dedupeKey()?.hashCode()?.toLong() ?: position.toLong()

    override fun hasStableIds(): Boolean = true

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
