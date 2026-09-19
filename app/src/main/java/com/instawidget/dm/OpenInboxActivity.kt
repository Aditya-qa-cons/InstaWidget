package com.instawidget.dm

import android.app.Activity
import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.os.Bundle
import android.util.Log
import android.widget.Toast

/**
 * Invisible step between tapping the widget and Instagram opening.
 *
 * It exists so the widget can tell when the user actually went to read their
 * DMs, which is the only honest moment to drop the "3 new messages" counts.
 * A broadcast receiver could not do this: starting an activity from the
 * background is restricted, whereas this *is* the foreground activity for the
 * instant it lives.
 *
 * Declared with Theme.NoDisplay and finished inside onCreate, so nothing is
 * ever drawn and the user only sees Instagram.
 */
class OpenInboxActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Open Instagram first. Clearing counts is the nice-to-have; landing
        // in the inbox is the thing the user asked for, so it must not be
        // able to fail because of bookkeeping.
        // A row passes its conversation; the header and empty state pass
        // nothing and just want the inbox.
        val conversationKey = intent?.getStringExtra(EXTRA_CONVERSATION)
        val sender = intent?.getStringExtra(EXTRA_SENDER)

        var opened = sendNotificationIntent(conversationKey)

        if (!opened) {
            // No live handle: fall back to a link. threadIntent returns null
            // unless that option is on and the sender is a usable handle, so
            // this lands on the inbox by itself.
            val target = Instagram.threadIntent(this, sender) ?: Instagram.inboxIntent(this)
            try {
                startActivity(target)
                opened = true
            } catch (e: ActivityNotFoundException) {
                Toast.makeText(this, R.string.toast_link_unavailable, Toast.LENGTH_LONG).show()
            }
        }

        if (opened) {
            try {
                if (DmStore.resetCounts(this)) DmWidgetProvider.refreshAll(this)
            } catch (e: Exception) {
                // Never let bookkeeping surface as a broken tap.
            }
        }

        // Theme.NoDisplay requires finishing before the activity would resume.
        finish()
    }

    /**
     * Fires the PendingIntent Instagram attached to the notification, which
     * opens the right account and the right thread.
     *
     * @return false when there is nothing to fire or Instagram has cancelled
     *         it, leaving the caller to fall back to a link.
     */
    private fun sendNotificationIntent(conversationKey: String?): Boolean {
        val key = conversationKey ?: return false
        val pending = NotificationIntents.get(key) ?: return false
        return try {
            pending.send()
            true
        } catch (e: PendingIntent.CanceledException) {
            // Instagram dropped it, usually with the notification itself.
            Log.i(TAG, "Notification intent for this conversation is gone")
            NotificationIntents.forget(key)
            false
        }
    }

    companion object {
        private const val TAG = "OpenInboxActivity"
        const val EXTRA_SENDER = "com.instawidget.dm.extra.SENDER"
        const val EXTRA_CONVERSATION = "com.instawidget.dm.extra.CONVERSATION"
    }
}
