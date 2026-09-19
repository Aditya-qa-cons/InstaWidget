package com.instawidget.dm

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings

/**
 * Everything this app knows about Instagram: which packages count as
 * Instagram, and how to get the user to their DM inbox.
 */
object Instagram {

    /**
     * Packages whose notifications we are willing to read. Instagram Lite
     * ships under its own package in some regions.
     */
    val PACKAGES = setOf(
        "com.instagram.android",
        "com.instagram.lite"
    )

    private const val MAIN_PACKAGE = "com.instagram.android"
    private const val WEB_INBOX = "https://www.instagram.com/direct/inbox/"

    private const val PREFS_NAME = "ig_dm_widget"
    private const val KEY_INBOX_LINK = "inboxLink"

    /**
     * One way of asking Instagram for the DM inbox.
     *
     * Instagram publishes no supported deep link for this, and which of these
     * actually lands on the inbox rather than the home feed varies by app
     * version. So instead of hard-coding a guess, the setup screen lets the
     * user try each one and keep whichever works on their build.
     */
    class InboxLink(
        val id: String,
        val label: String,
        private val build: () -> Intent
    ) {
        fun intent(): Intent = build().addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        fun isAvailable(context: Context): Boolean =
            intent().resolveActivity(context.packageManager) != null
    }

    /**
     * Ordered by how likely each is to land on the inbox.
     *
     * instagram://direct-inbox leads because it is the one confirmed to open
     * the inbox on a current Instagram build; the underscore spelling did not
     * even resolve there, and instagram://direct_inbox opened the home feed on
     * an older version. [inboxIntent] skips any that do not resolve, so a
     * device where the first choice is missing still gets a working link
     * without the user touching anything.
     */
    val INBOX_LINKS: List<InboxLink> = listOf(
        InboxLink("direct-inbox", "instagram://direct-inbox") {
            Intent(Intent.ACTION_VIEW, Uri.parse("instagram://direct-inbox"))
        },
        InboxLink("app-web", "Instagram app via instagram.com/direct/inbox") {
            Intent(Intent.ACTION_VIEW, Uri.parse(WEB_INBOX)).setPackage(MAIN_PACKAGE)
        },
        InboxLink("direct_inbox", "instagram://direct_inbox") {
            Intent(Intent.ACTION_VIEW, Uri.parse("instagram://direct_inbox"))
        },
        InboxLink("direct_v2", "instagram://direct_v2") {
            Intent(Intent.ACTION_VIEW, Uri.parse("instagram://direct_v2"))
        },
        InboxLink("web", "Browser (instagram.com/direct/inbox)") {
            Intent(Intent.ACTION_VIEW, Uri.parse(WEB_INBOX))
        }
    )

    private fun prefs(context: Context) = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Id of the link the user picked, or null while none has been chosen. */
    fun selectedLinkId(context: Context): String? =
        prefs(context).getString(KEY_INBOX_LINK, null)

    fun selectLink(context: Context, id: String) {
        prefs(context).edit().putString(KEY_INBOX_LINK, id).apply()
    }

    /**
     * Intent the widget uses: the link the user chose if it still resolves,
     * otherwise the first candidate this device can actually open, and the
     * plain web URL as the last resort.
     */
    fun inboxIntent(context: Context): Intent {
        val chosen = selectedLinkId(context)
        if (chosen != null) {
            val link = INBOX_LINKS.firstOrNull { it.id == chosen }
            if (link != null && link.isAvailable(context)) return link.intent()
        }
        val usable = INBOX_LINKS.firstOrNull { it.isAvailable(context) }
        return usable?.intent()
            ?: Intent(Intent.ACTION_VIEW, Uri.parse(WEB_INBOX))
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
