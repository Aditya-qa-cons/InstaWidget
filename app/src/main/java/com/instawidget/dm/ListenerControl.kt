package com.instawidget.dm

import android.content.ComponentName
import android.content.Context
import android.service.notification.NotificationListenerService

/**
 * Nudges the system into (re)binding the notification listener.
 *
 * Granting notification access is supposed to bind the service, but the
 * binding is routinely lost: the app being updated is enough on many builds,
 * and aggressive OEM battery managers kill the service and never bring it
 * back. The symptom is notification access showing as granted while the
 * listener has never actually run.
 *
 * `requestRebind` is the platform's own remedy for exactly that.
 */
object ListenerControl {

    fun component(context: Context): ComponentName =
        ComponentName(context, DmNotificationListener::class.java)

    /**
     * Asks the system to rebind the listener.
     *
     * @return false if the request itself was rejected, which usually means
     *         notification access is not actually granted.
     */
    fun requestRebind(context: Context): Boolean = try {
        NotificationListenerService.requestRebind(component(context))
        true
    } catch (e: Exception) {
        false
    }
}
