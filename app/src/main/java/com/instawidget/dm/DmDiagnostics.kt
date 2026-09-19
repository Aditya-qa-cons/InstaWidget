package com.instawidget.dm

import android.content.Context
import android.service.notification.StatusBarNotification
import android.text.format.DateFormat
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * A small on-device record of what the notification listener saw and what the
 * filter decided.
 *
 * Without this there is no way to tell, from a phone you cannot attach a
 * debugger to, whether a missing DM means the listener never ran, Instagram
 * posted something unexpected, or the filter rejected it -- and those have
 * completely different fixes. Stays local like everything else.
 */
object DmDiagnostics {

    private const val MAX_ENTRIES = 12

    private const val PREFS_NAME = "ig_dm_widget_debug"
    private const val KEY_ENTRIES = "entries"
    private const val KEY_TOTAL_SEEN = "totalSeen"
    private const val KEY_INSTAGRAM_SEEN = "instagramSeen"
    private const val KEY_CONNECTED_AT = "connectedAt"

    private const val FIELD_TIME = "t"
    private const val FIELD_ACCEPTED = "a"
    private const val FIELD_DETAIL = "d"

    class Entry(val time: Long, val accepted: Boolean, val detail: String)

    private fun prefs(context: Context) = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun onListenerConnected(context: Context) {
        prefs(context).edit().putLong(KEY_CONNECTED_AT, System.currentTimeMillis()).apply()
    }

    /** Counts every notification the listener is handed, from any app. */
    fun countNotification(context: Context, isInstagram: Boolean) {
        val p = prefs(context)
        val edit = p.edit().putInt(KEY_TOTAL_SEEN, p.getInt(KEY_TOTAL_SEEN, 0) + 1)
        if (isInstagram) {
            edit.putInt(KEY_INSTAGRAM_SEEN, p.getInt(KEY_INSTAGRAM_SEEN, 0) + 1)
        }
        edit.apply()
    }

    /** Records the filter's verdict on one Instagram notification. */
    fun record(
        context: Context,
        sbn: StatusBarNotification,
        outcome: DmNotificationFilter.Outcome
    ) {
        val detail = when (outcome) {
            is DmNotificationFilter.Outcome.Accepted ->
                "SHOWN  ${outcome.message.sender}: ${outcome.message.preview.take(60)}"
            is DmNotificationFilter.Outcome.Rejected ->
                "SKIPPED  ${outcome.reason}  [${DmNotificationFilter.describeSignals(sbn)}]"
        }
        append(context, Entry(System.currentTimeMillis(), outcome is DmNotificationFilter.Outcome.Accepted, detail))
    }

    private fun append(context: Context, entry: Entry) {
        val entries = load(context).toMutableList()
        entries.add(0, entry)
        while (entries.size > MAX_ENTRIES) entries.removeAt(entries.size - 1)

        val array = JSONArray()
        for (e in entries) {
            array.put(
                JSONObject()
                    .put(FIELD_TIME, e.time)
                    .put(FIELD_ACCEPTED, e.accepted)
                    .put(FIELD_DETAIL, e.detail)
            )
        }
        prefs(context).edit().putString(KEY_ENTRIES, array.toString()).apply()
    }

    fun load(context: Context): List<Entry> {
        val raw = prefs(context).getString(KEY_ENTRIES, null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            val out = ArrayList<Entry>(array.length())
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                out.add(
                    Entry(
                        obj.optLong(FIELD_TIME),
                        obj.optBoolean(FIELD_ACCEPTED),
                        obj.optString(FIELD_DETAIL)
                    )
                )
            }
            out
        } catch (e: JSONException) {
            emptyList()
        }
    }

    fun clear(context: Context) {
        prefs(context).edit().clear().apply()
    }

    /** Human-readable dump, for showing on screen and copying to the clipboard. */
    fun report(context: Context): String {
        val p = prefs(context)
        val connectedAt = p.getLong(KEY_CONNECTED_AT, 0L)
        val builder = StringBuilder()

        builder.append("Notification access: ")
            .append(if (Instagram.isNotificationAccessGranted(context)) "granted" else "NOT granted")
            .append('\n')
        builder.append("Listener last connected: ")
            .append(if (connectedAt > 0L) time(context, connectedAt) else "never")
            .append('\n')
        builder.append("Notifications seen: ")
            .append(p.getInt(KEY_TOTAL_SEEN, 0))
            .append(" total, ")
            .append(p.getInt(KEY_INSTAGRAM_SEEN, 0))
            .append(" from Instagram\n")
        builder.append("Cached DMs: ").append(DmStore.load(context).size).append('\n')

        val entries = load(context)
        if (entries.isEmpty()) {
            builder.append("\nNo Instagram notifications seen yet.")
        } else {
            builder.append("\nRecent Instagram notifications:\n")
            for (entry in entries) {
                builder.append(time(context, entry.time))
                    .append("  ")
                    .append(entry.detail)
                    .append('\n')
            }
        }
        return builder.toString().trimEnd()
    }

    private fun time(context: Context, millis: Long): String =
        DateFormat.getTimeFormat(context).format(millis)
}
