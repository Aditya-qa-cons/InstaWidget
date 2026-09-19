package com.instawidget.dm

import org.json.JSONObject

/**
 * One row in the widget: the most recent DM from a given conversation.
 *
 * Everything here comes straight out of a notification Instagram already
 * posted to this device. Nothing is fetched, and nothing ever leaves the
 * device.
 */
data class DmMessage(
    /** Display name Instagram put in the notification title, e.g. "alex_h". */
    val sender: String,
    /** The newest message preview Instagram put in the notification body. */
    val preview: String,
    /** When the newest message was posted, in epoch millis. */
    val postedAt: Long,
    /**
     * How many messages from this conversation have collapsed into this row
     * since it was last cleared. 1 means a single message.
     */
    val count: Int = 1,
    /**
     * Which of the logged-in Instagram accounts received this, when the
     * notification says. Null when it does not, which is the single-account
     * case.
     */
    val account: String? = null
) {
    fun toJson(): JSONObject = JSONObject()
        .put(KEY_SENDER, sender)
        .put(KEY_PREVIEW, preview)
        .put(KEY_POSTED_AT, postedAt)
        .put(KEY_COUNT, count)
        .put(KEY_ACCOUNT, account ?: "")

    /**
     * Identity of the conversation this belongs to.
     *
     * Instagram gives us no thread id, so the sender name is the only handle
     * available. Normalised so that casing or stray whitespace in the
     * notification title does not split one conversation into two rows, and
     * scoped by account so the same person messaging two logged-in accounts
     * stays two conversations.
     */
    fun conversationKey(): String =
        (account?.trim()?.lowercase() ?: "") + "\u0000" + sender.trim().lowercase()

    companion object {
        private const val KEY_SENDER = "sender"
        private const val KEY_PREVIEW = "preview"
        private const val KEY_POSTED_AT = "postedAt"
        private const val KEY_COUNT = "count"
        private const val KEY_ACCOUNT = "account"

        fun fromJson(json: JSONObject): DmMessage? {
            val sender = json.optString(KEY_SENDER)
            val preview = json.optString(KEY_PREVIEW)
            if (sender.isEmpty() && preview.isEmpty()) return null
            return DmMessage(
                sender = sender,
                preview = preview,
                postedAt = json.optLong(KEY_POSTED_AT, 0L),
                count = json.optInt(KEY_COUNT, 1).coerceAtLeast(1),
                account = json.optString(KEY_ACCOUNT).takeIf { it.isNotEmpty() }
            )
        }
    }
}
