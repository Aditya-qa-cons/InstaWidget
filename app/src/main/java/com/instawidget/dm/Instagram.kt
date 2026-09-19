package com.instawidget.dm

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings

/**
 * Everything this app knows about Instagram: which packages count as Instagram,
 * and how to get the user to their DM inbox.
 */
object Instagram {

    /**
     * Packages whose notifications we are willing to read. Instagram Lite ships
     * under its own package in some regions.
     */
    val PACKAGES = setOf(
        "com.instagram.android",
        "com.instagram.lite"
    )

    /**
     * Instagram's deep link to the DM inbox.
     *
     * There is no public deep link to an individual conversation thread, so
     * every tap lands on the inbox. That is a limitation of Instagram, not a
     * bug here.
     */
    private const val INBOX_DEEP_LINK = "instagram://direct_inbox"

    /** Used when Instagram is not installed (or cannot handle the deep link). */
    private const val INBOX_WEB_URL = "https://www.instagram.com/direct/inbox/"

    /**
     * Intent that opens the DM inbox in the Instagram app, falling back to the
     * mobile web inbox.
     *
     * Resolved eagerly rather than relying on the app-link fallback because the
     * intent is handed to the launcher inside a PendingIntent, where we get no
     * chance to catch ActivityNotFoundException.
     */
    fun inboxIntent(context: Context): Intent {
        val deepLink = Intent(Intent.ACTION_VIEW, Uri.parse(INBOX_DEEP_LINK))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (deepLink.resolveActivity(context.packageManager) != null) return deepLink

        return Intent(Intent.ACTION_VIEW, Uri.parse(INBOX_WEB_URL))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    /** True once the user has ticked this app in Settings > Notification access. */
    fun isNotificationAccessGranted(context: Context): Boolean {
        // NotificationManager#isNotificationListenerAccessGranted needs API 27;
        // this secure setting is the same source of truth and works everywhere.
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            "enabled_notification_listeners"
        ) ?: return false
        val us = context.packageName
        return enabled.split(':').any { it.substringBefore('/') == us }
    }

    /** Settings screen where notification access is granted. */
    fun notificationAccessSettingsIntent(): Intent =
        Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /**
     * This app's entry in Settings > Apps.
     *
     * Needed because Android 13+ hides notification access behind "Restricted
     * setting" for apps installed from an APK file rather than from a store.
     * The overflow menu on this screen has the "Allow restricted settings"
     * item that clears it; there is no intent that opens that menu directly.
     */
    fun appInfoIntent(context: Context): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(Uri.fromParts("package", context.packageName, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
