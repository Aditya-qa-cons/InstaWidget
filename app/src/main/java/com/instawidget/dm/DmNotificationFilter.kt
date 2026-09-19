package com.instawidget.dm

import android.app.Notification
import android.os.Bundle
import android.service.notification.StatusBarNotification

/**
 * Decides whether an Instagram notification is a direct message, as opposed to
 * a like, comment, follow, story mention or "someone went live".
 *
 * Instagram documents none of this, so the filter is layered:
 *
 *  1. A *strong* signal (messaging category, MessagingStyle template, or a
 *     notification channel whose id mentions "direct") means DM, full stop.
 *  2. Otherwise a phrase from [NON_DM_PHRASES] in the title or body means
 *     not a DM.
 *  3. Anything left over is treated as a DM. Instagram's engagement
 *     notifications essentially always carry one of those phrases, so the
 *     fallback errs toward showing a real message rather than dropping it.
 *
 * Every rejection carries a reason, because the only way to debug this on a
 * real phone is to see what the filter decided and why.
 */
object DmNotificationFilter {

    /** What [inspect] concluded about one notification. */
    sealed class Outcome {
        data class Accepted(val message: DmMessage) : Outcome()
        data class Rejected(val reason: String) : Outcome()
    }

    /**
     * Keys that are only compile-time constants on newer API levels, or are
     * not public constants at all. The string values are stable.
     */
    private const val EXTRA_TEMPLATE = "android.template"
    private const val EXTRA_MESSAGES = "android.messages"
    private const val EXTRA_TEXT_LINES = "android.textLines"
    private const val EXTRA_SUMMARY_TEXT = "android.summaryText"
    private const val EXTRA_CONVERSATION_TITLE = "android.conversationTitle"
    private const val MESSAGE_KEY_TEXT = "text"
    private const val MESSAGE_KEY_SENDER = "sender"

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
    fun extract(sbn: StatusBarNotification): DmMessage? =
        (inspect(sbn) as? Outcome.Accepted)?.message

    /** Same decision as [extract], but says why when the answer is no. */
    fun inspect(sbn: StatusBarNotification): Outcome {
        if (sbn.packageName !in Instagram.PACKAGES) {
            return Outcome.Rejected("not Instagram (${sbn.packageName})")
        }

        val notification = sbn.notification
            ?: return Outcome.Rejected("no notification payload")
        if (isOngoing(notification)) return Outcome.Rejected("ongoing notification")
        if (isGroupSummary(notification)) return Outcome.Rejected("group summary")

        val extras: Bundle = notification.extras
            ?: return Outcome.Rejected("no extras")

        // MessagingStyle keeps the actual message in a parcelled array and may
        // leave EXTRA_TEXT unset entirely, so it has to be read first.
        val messaging = latestMessagingStyleMessage(extras)

        val text = messaging?.text
            ?: extras.string(Notification.EXTRA_TEXT)
            ?: extras.string(Notification.EXTRA_BIG_TEXT)
            ?: lastTextLine(extras)
            ?: extras.string(EXTRA_SUMMARY_TEXT)
        if (text.isNullOrEmpty()) return Outcome.Rejected("no message text in notification")

        val title = extras.string(Notification.EXTRA_TITLE)
        val sender = messaging?.sender
            ?: title
            ?: extras.string(EXTRA_CONVERSATION_TITLE)

        if (!isDirectMessage(notification, extras, messaging != null, title, text)) {
            return Outcome.Rejected("looks like engagement, not a DM")
        }

        return Outcome.Accepted(
            DmMessage(
                sender = sender?.takeIf { it.isNotEmpty() } ?: "Instagram",
                preview = text,
                postedAt = if (sbn.postTime > 0L) sbn.postTime else System.currentTimeMillis()
            )
        )
    }

    /** A short description of the signals on a notification, for diagnostics. */
    fun describeSignals(sbn: StatusBarNotification): String {
        val notification = sbn.notification ?: return "no payload"
        val extras = notification.extras
        val parts = ArrayList<String>(4)
        parts.add("category=${notification.category ?: "-"}")
        parts.add("template=${extras?.getString(EXTRA_TEMPLATE)?.substringAfterLast('.') ?: "-"}")
        parts.add("channel=${channelIdOf(notification) ?: "-"}")
        // The typed getParcelableArray overload needs API 33; minSdk here is 26.
        @Suppress("DEPRECATION")
        val messages = extras?.getParcelableArray(EXTRA_MESSAGES)
        if (messages != null) parts.add("hasMessages")
        return parts.joinToString(" ")
    }

    private class MessagingMessage(val sender: String?, val text: String)

    /**
     * Newest usable entry from a MessagingStyle notification's message array.
     * Entries are oldest-first, so this walks backwards.
     */
    private fun latestMessagingStyleMessage(extras: Bundle): MessagingMessage? {
        val array = try {
            // The typed overload needs API 33; minSdk here is 26.
            @Suppress("DEPRECATION")
            extras.getParcelableArray(EXTRA_MESSAGES)
        } catch (e: Exception) {
            null
        } ?: return null

        for (index in array.indices.reversed()) {
            val entry = array[index] as? Bundle ?: continue
            val text = entry.string(MESSAGE_KEY_TEXT)
            if (text.isNullOrEmpty()) continue
            return MessagingMessage(entry.string(MESSAGE_KEY_SENDER), text)
        }
        return null
    }

    /** Last line of an InboxStyle notification. */
    private fun lastTextLine(extras: Bundle): String? {
        val lines = try {
            extras.getCharSequenceArray(EXTRA_TEXT_LINES)
        } catch (e: Exception) {
            null
        } ?: return null
        for (index in lines.indices.reversed()) {
            val line = lines[index]?.toString()?.trim()
            if (!line.isNullOrEmpty()) return line
        }
        return null
    }

    private fun isDirectMessage(
        notification: Notification,
        extras: Bundle,
        isMessagingStyle: Boolean,
        title: String?,
        text: String
    ): Boolean {
        if (isMessagingStyle) return true
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

    private fun channelIdOf(notification: Notification): String? = try {
        notification.channelId
    } catch (e: Exception) {
        null
    }

    private fun isOngoing(notification: Notification): Boolean =
        (notification.flags and Notification.FLAG_ONGOING_EVENT) != 0

    private fun isGroupSummary(notification: Notification): Boolean =
        (notification.flags and Notification.FLAG_GROUP_SUMMARY) != 0

    private fun Bundle.string(key: String): String? =
        getCharSequence(key)?.toString()?.trim()
}
