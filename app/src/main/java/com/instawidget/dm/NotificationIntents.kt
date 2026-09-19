package com.instawidget.dm

import android.app.PendingIntent

/**
 * Holds the PendingIntent from each DM notification, keyed by conversation.
 *
 * This is how the widget opens the *right* Instagram account. Instagram builds
 * that intent itself, so it already knows which of several logged-in accounts
 * received the message and which thread it belongs to; firing it beats any
 * URL we could construct, because Instagram publishes no deep link that names
 * an account at all.
 *
 * Deliberately memory-only: a PendingIntent is a live handle into another
 * process, not something that can be written to disk and read back. It
 * survives as long as this process does, and [DmNotificationListener] refills
 * the map from the notifications still in the shade whenever the listener
 * reconnects. When there is no live handle the caller falls back to a plain
 * inbox link.
 */
object NotificationIntents {

    /** Bounded so a long-lived process cannot accumulate handles without end. */
    private const val MAX_ENTRIES = 40

    private val intents = LinkedHashMap<String, PendingIntent>()

    @Synchronized
    fun remember(conversationKey: String, intent: PendingIntent?) {
        if (intent == null) return
        intents.remove(conversationKey)
        intents[conversationKey] = intent
        while (intents.size > MAX_ENTRIES) {
            val oldest = intents.keys.firstOrNull() ?: break
            intents.remove(oldest)
        }
    }

    @Synchronized
    fun get(conversationKey: String): PendingIntent? = intents[conversationKey]

    /** Dropped once used and found dead, so the fallback takes over cleanly. */
    @Synchronized
    fun forget(conversationKey: String) {
        intents.remove(conversationKey)
    }

    @Synchronized
    fun size(): Int = intents.size

    @Synchronized
    fun clear() = intents.clear()
}
