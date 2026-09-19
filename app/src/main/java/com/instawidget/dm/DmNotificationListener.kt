package com.instawidget.dm

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

/**
 * The only data source in the app.
 *
 * Android hands us every notification once the user grants notification access.
 * We keep the Instagram DMs, drop everything else on the floor, and never look
 * at another app's notifications beyond checking the package name.
 */
class DmNotificationListener : NotificationListenerService() {

    override fun onListenerConnected() {
        super.onListenerConnected()
        // Catch up on DMs that were already in the shade when access was granted.
        val existing = try {
            activeNotifications
        } catch (e: SecurityException) {
            null
        } ?: return

        var changed = false
        // Oldest first, so the newest ends up at the top of the cache.
        for (sbn in existing.sortedBy { it.postTime }) {
            val message = DmNotificationFilter.extract(sbn) ?: continue
            if (DmStore.add(this, message)) changed = true
        }
        if (changed) DmWidgetProvider.refreshAll(this)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val notification = sbn ?: return
        val message = try {
            DmNotificationFilter.extract(notification)
        } catch (e: Exception) {
            // A malformed notification from another app must never take the
            // listener down; the system would stop rebinding us.
            Log.w(TAG, "Could not read notification from ${notification.packageName}", e)
            null
        } ?: return

        if (DmStore.add(this, message)) {
            DmWidgetProvider.refreshAll(this)
        }
    }

    private companion object {
        const val TAG = "DmNotificationListener"
    }
}
