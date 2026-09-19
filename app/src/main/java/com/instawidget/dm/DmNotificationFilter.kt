package com.instawidget.dm

import android.app.Notification
import android.os.Bundle
import android.service.notification.StatusBarNotification

/**
 * Decides whether an Instagram notification is a direct message, as opposed to
 * a like, comment, follow, story mention or "someone went live".
 *
 * Instagram does not document any of this, so the filter is layered:
 *
 *  1. A *strong* signal (messaging category, MessagingStyle template, or a
 *     notification channel whose id mentions "direct") means DM, full stop.
 *  2. Otherwise a phrase from [NON_DM_PHRASES] in the title or body means
 *     not a DM.
 *  3. Anything left over is treated as a DM. Instagram's engagement
 *     notifications essentially always carry one of those phrases, so the
 *     fallback errs toward showing a real message rather than dropping it.
 */
object DmNotificationFilter {

    /**
     * Value of Notification.EXTRA_TEMPLATE, which is only a compile-time
     * constant from API 24. The string itself is stable across releases.
     */
    private const val EXTRA_TEMPLATE = "android.template"

    /** Lower-cased phrases that mark a notification as *not* a DM. */
    private val NON_DM_PHRASES = listOf(
        "liked your",
        "liked their",
        "liked a",
        "commented:",
        "commented on",
        "replied to your comment",
        "mentioned you in a comment",
        "started following you",
        "requested to follow you",
        "accepted your follow request",
        "follows you back",
        "suggested for you",
        "tagged you in a",
        "mentioned you in their story",
        "added to their story",
        "posted for the first time",
        "shared a post",
        "shared a reel",
        "is live",
        "went live",
        "started a live video",
        "your story",
        "your post",
        "your reel",
        "new post from",
        "reminder:",
        "you may like",
        "since you follow"
    )

    /**
     * Phrases that look like engagement but really are DM activity, so they
     * must survive the [NON_DM_PHRASES] check.
     */
    private val DM_PHRASES = listOf(
        "sent you a message",
        "sent you a photo",
        "sent you a video",
        "sent you a voice message",
        "sent you an attachment",
        "reacted to your message",
        "liked a message",
        "wants to send you a message",
        "sent a message",
        "sent you"
    )

    /** Extracts a cached DM preview, or null if this is not a DM we can show. */
    fun extract(sbn: StatusBarNotification): DmMessage? {
        if (sbn.packageName !in Instagram.PACKAGES) return null

        val notification = sbn.notification ?: return null
        if (isOngoing(notification)) return null
        if (isGroupSummary(notification)) return null

        val extras: Bundle = notification.extras ?: return null
        val title = extras.charSequence(Notification.EXTRA_TITLE)
        val text = extras.charSequence(Notification.EXTRA_TEXT)
            ?: extras.charSequence(Notification.EXTRA_BIG_TEXT)
            ?: extras.charSequence("android.summaryText")

        // Without a body there is nothing worth putting in the widget.
        if (text.isNullOrEmpty()) return null

        if (!isDirectMessage(notification, extras, title, text)) return null

        return DmMessage(
            sender = title?.takeIf { it.isNotEmpty() } ?: "Instagram",
            preview = text,
            postedAt = if (sbn.postTime > 0L) sbn.postTime else System.currentTimeMillis()
        )
    }

    private fun isDirectMessage(
        notification: Notification,
        extras: Bundle,
        title: String?,
        text: String
    ): Boolean {
        if (hasStrongDmSignal(notification, extras)) return true

        val haystack = ((title ?: "") + " " + text).lowercase()
        if (DM_PHRASES.any { haystack.contains(it) }) return true
        if (NON_DM_PHRASES.any { haystack.contains(it) }) return false

        // No signal either way: assume a plain "<name>: <message>" DM.
        return true
    }

    private fun hasStrongDmSignal(notification: Notification, extras: Bundle): Boolean {
        if (notification.category == Notification.CATEGORY_MESSAGE) return true

        val template = extras.getString(EXTRA_TEMPLATE)
        if (template != null && template.contains("MessagingStyle")) return true

        val channelId = channelIdOf(notification)?.lowercase()
        return channelId != null && (channelId.contains("direct") || channelId.contains("message"))
    }

    /**
     * Notification#getChannelId() is API 26 API but is read reflectively so the
     * app compiles against older platform jars; a missing value just means we
     * fall back to the other signals.
     */
    private fun channelIdOf(notification: Notification): String? = try {
        val method = Notification::class.java.getMethod("getChannelId")
        method.invoke(notification) as? String
    } catch (e: Exception) {
        null
    }

    private fun isOngoing(notification: Notification): Boolean =
        (notification.flags and Notification.FLAG_ONGOING_EVENT) != 0

    private fun isGroupSummary(notification: Notification): Boolean =
        (notification.flags and Notification.FLAG_GROUP_SUMMARY) != 0

    private fun Bundle.charSequence(key: String): String? =
        getCharSequence(key)?.toString()?.trim()
}
