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

    /**
     * Whether the cache holds DMs for more than one Instagram account. With
     * one account the label would be the same on every row, so it is hidden.
     */
    private var showAccounts: Boolean = false

    override fun onCreate() = Unit

    /**
     * Called on the launcher's behalf whenever the data set is invalidated.
     * Reading SharedPreferences here is fine: this runs off the main thread.
     */
    override fun onDataSetChanged() {
        messages = DmStore.load(context)
        showAccounts = messages.mapNotNull { it.account }.distinct().size > 1
    }

    override fun onDestroy() {
        messages = emptyList()
        showAccounts = false
    }

    override fun getCount(): Int = messages.size

    override fun getViewAt(position: Int): RemoteViews? {
        val message = messages.getOrNull(position) ?: return null
        val views = RemoteViews(context.packageName, R.layout.widget_dm_row)
        views.setImageViewBitmap(R.id.row_avatar, Avatars.forSender(context, message.sender))
        views.setTextViewText(R.id.row_sender, message.sender)
        views.setTextViewText(R.id.row_preview, message.preview)
        views.setTextViewText(R.id.row_time, relativeTime(message.postedAt))

        val account = message.account
        if (showAccounts && !account.isNullOrEmpty()) {
            views.setViewVisibility(R.id.row_account, View.VISIBLE)
            views.setTextViewText(R.id.row_account, context.getString(R.string.row_account, account))
        } else {
            views.setViewVisibility(R.id.row_account, View.GONE)
        }

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
            Intent()
                .putExtra(OpenInboxActivity.EXTRA_SENDER, message.sender)
                .putExtra(OpenInboxActivity.EXTRA_CONVERSATION, message.conversationKey())
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
