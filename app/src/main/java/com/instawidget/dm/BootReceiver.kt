package com.instawidget.dm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Re-arms the notification listener after a reboot.
 *
 * Stock Android rebinds notification listeners by itself, but several OEM
 * builds do not, which leaves the widget silently frozen until the user opens
 * the app. Asking for a rebind on boot costs nothing when the system has
 * already done it.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            return
        }
        if (!Instagram.isNotificationAccessGranted(context)) return
        ListenerControl.requestRebind(context)
    }
}
