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
        DmDiagnostics.onListenerConnected(this)

        // Catch up on DMs that were already in the shade when access was granted.
        val existing = try {
            activeNotifications
        } catch (e: SecurityException) {
            null
        } ?: return

        var changed = false
        // Oldest first, so the newest ends up at the top of the cache.
        for (sbn in existing.sortedBy { it.postTime }) {
            if (handle(sbn)) changed = true
        }
        if (changed) DmWidgetProvider.refreshAll(this)
    }

    /**
     * The system drops the binding on app updates, low memory and whenever an
     * OEM battery manager feels like it, and does not always bring it back.
     * Asking for a rebind here is the documented way to recover, and is what
     * keeps the widget working without the user visiting the setup screen.
     */
    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        DmDiagnostics.onListenerDisconnected(this)
        ListenerControl.requestRebind(this)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val notification = sbn ?: return
        if (handle(notification)) {
            DmWidgetProvider.refreshAll(this)
        }
    }

    /**
     * Runs one notification through the filter.
     *
     * @return true if the cache changed and the widget needs redrawing.
     */
    private fun handle(sbn: StatusBarNotification): Boolean = try {
        val isInstagram = sbn.packageName in Instagram.PACKAGES
        DmDiagnostics.countNotification(this, isInstagram)

        if (!isInstagram) {
            false
        } else {
            val outcome = DmNotificationFilter.inspect(sbn)
            DmDiagnostics.record(this, sbn, outcome)
            if (outcome !is DmNotificationFilter.Outcome.Accepted) {
                false
            } else {
                // Instagram's own intent knows which logged-in account this
                // arrived on and which thread it belongs to, which nothing we
                // could build does.
                NotificationIntents.remember(
                    outcome.message.conversationKey(),
                    sbn.notification?.contentIntent
                )
                DmStore.add(this, outcome.message)
            }
        }
    } catch (e: Exception) {
        // A malformed notification from another app must never take the
        // listener down; the system would stop rebinding us.
        Log.w(TAG, "Could not read notification from ${sbn.packageName}", e)
        false
    }

    private companion object {
        const val TAG = "DmNotificationListener"
    }
}
