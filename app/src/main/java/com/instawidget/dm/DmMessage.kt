package com.instawidget.dm

import org.json.JSONObject

/**
 * One cached Instagram DM preview.
 *
 * Everything here comes straight out of a notification Instagram already posted
 * to this device. Nothing is fetched, and nothing ever leaves the device.
 */
data class DmMessage(
    /** Display name Instagram put in the notification title, e.g. "alex_h". */
    val sender: String,
    /** The message preview Instagram put in the notification body. */
    val preview: String,
    /** When the notification was posted, in epoch millis. */
    val postedAt: Long
) {
    fun toJson(): JSONObject = JSONObject()
        .put(KEY_SENDER, sender)
        .put(KEY_PREVIEW, preview)
        .put(KEY_POSTED_AT, postedAt)

    /** Identity used to collapse repeats of the same notification. */
    fun dedupeKey(): String = "$sender\u0000$preview"

    companion object {
        private const val KEY_SENDER = "sender"
        private const val KEY_PREVIEW = "preview"
        private const val KEY_POSTED_AT = "postedAt"

        fun fromJson(json: JSONObject): DmMessage? {
            val sender = json.optString(KEY_SENDER)
            val preview = json.optString(KEY_PREVIEW)
            if (sender.isEmpty() && preview.isEmpty()) return null
            return DmMessage(
                sender = sender,
                preview = preview,
                postedAt = json.optLong(KEY_POSTED_AT, 0L)
            )
        }
    }
}
