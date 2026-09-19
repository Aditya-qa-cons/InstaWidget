package com.instawidget.dm

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONException

/**
 * The entire persistence layer: a JSON array of the most recent conversations
 * in one SharedPreferences key.
 *
 * Deliberately tiny and deliberately local. There is no database, no sync and
 * no network; if the user clears app data the cache is simply gone and refills
 * from the next DM notification.
 */
object DmStore {

    /** How many conversations we keep. The widget is small; more is noise. */
    const val MAX_CONVERSATIONS = 25

    private const val PREFS_NAME = "ig_dm_widget"
    private const val KEY_MESSAGES = "messages"

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Cached conversations, most recently active first. */
    fun load(context: Context): List<DmMessage> {
        val raw = prefs(context).getString(KEY_MESSAGES, null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            val out = ArrayList<DmMessage>(array.length())
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                DmMessage.fromJson(obj)?.let(out::add)
            }
            out
        } catch (e: JSONException) {
            // Corrupt cache is not worth crashing over; start clean.
            emptyList()
        }
    }

    /**
     * Folds [message] into its conversation and moves that conversation to the
     * top, the way a real DM inbox behaves: one row per person, showing their
     * latest message.
     *
     * @return true if the stored list actually changed.
     */
    fun add(context: Context, message: DmMessage): Boolean {
        val current = load(context)
        val key = message.conversationKey()
        val existing = current.firstOrNull { it.conversationKey() == key }

        val merged = when {
            existing == null -> message

            // Instagram re-posts a MessagingStyle notification every time the
            // conversation changes, so the same newest message arrives over
            // and over. Identical text means the same message, not a new one:
            // refresh the timestamp but do not inflate the count.
            existing.preview == message.preview -> {
                val alreadyCurrent = existing.postedAt == message.postedAt &&
                    current.firstOrNull()?.conversationKey() == key
                if (alreadyCurrent) return false
                existing.copy(postedAt = maxOf(existing.postedAt, message.postedAt))
            }

            else -> message.copy(count = existing.count + 1)
        }

        val updated = ArrayList<DmMessage>(current.size + 1)
        updated.add(merged)
        for (entry in current) {
            if (entry.conversationKey() == key) continue
            updated.add(entry)
            if (updated.size >= MAX_CONVERSATIONS) break
        }
        save(context, updated)
        return true
    }

    /**
     * Drops the collapsed-message counts back to 1, leaving the previews in
     * place. Called when the user opens the Instagram inbox from the widget,
     * since at that point they have seen what was waiting.
     *
     * @return true if anything changed.
     */
    fun resetCounts(context: Context): Boolean {
        val current = load(context)
        if (current.none { it.count > 1 }) return false
        save(context, current.map { if (it.count > 1) it.copy(count = 1) else it })
        return true
    }

    fun clear(context: Context) = save(context, emptyList())

    private fun save(context: Context, messages: List<DmMessage>) {
        val array = JSONArray()
        for (message in messages) array.put(message.toJson())
        prefs(context).edit().putString(KEY_MESSAGES, array.toString()).apply()
    }
}
