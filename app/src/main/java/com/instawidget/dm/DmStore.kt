package com.instawidget.dm

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONException

/**
 * The entire persistence layer: a JSON array of the most recent DM previews in
 * one SharedPreferences key.
 *
 * Deliberately tiny and deliberately local. There is no database, no sync and
 * no network; if the user clears app data the cache is simply gone and refills
 * from the next DM notification.
 */
object DmStore {

    /** How many previews we keep. The widget is small; more than this is noise. */
    const val MAX_MESSAGES = 25

    private const val PREFS_NAME = "ig_dm_widget"
    private const val KEY_MESSAGES = "messages"

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Cached previews, newest first. */
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
     * Adds [message] at the front, collapsing a repeat of the same sender and
     * text so a re-posted notification does not duplicate the row.
     *
     * @return true if the stored list actually changed.
     */
    fun add(context: Context, message: DmMessage): Boolean {
        val current = load(context)
        val key = message.dedupeKey()
        if (current.isNotEmpty() && current[0].dedupeKey() == key) return false

        val updated = ArrayList<DmMessage>(current.size + 1)
        updated.add(message)
        for (existing in current) {
            if (existing.dedupeKey() == key) continue
            updated.add(existing)
            if (updated.size >= MAX_MESSAGES) break
        }
        save(context, updated)
        return true
    }

    fun clear(context: Context) = save(context, emptyList())

    private fun save(context: Context, messages: List<DmMessage>) {
        val array = JSONArray()
        for (message in messages) array.put(message.toJson())
        prefs(context).edit().putString(KEY_MESSAGES, array.toString()).apply()
    }
}
