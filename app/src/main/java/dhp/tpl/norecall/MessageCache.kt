package dhp.tpl.norecall

import android.util.LruCache

/**
 * In-process LRU cache of original message content, keyed by message ID.
 *
 * When Messenger marks a message as unsent/tombstoned it clears the body,
 * attachment URLs and attachment type from the message model. We cache every
 * message we see *before* that happens so we can restore the content later
 * when the tombstone view is being rendered.
 *
 * The cache is intentionally in-memory only (no SharedPreferences / SQLite)
 * so it is:
 *   – fast (no I/O on the UI thread),
 *   – zero-permission (no storage permission required),
 *   – automatically evicted when the app process dies.
 *
 * 500 entries ≈ comfortably covers any visible conversation.  Each entry
 * holds a few strings so total heap cost is negligible.
 */
object MessageCache {

    private const val MAX = 500
    private val store = LruCache<String, Entry>(MAX)

    /** Attachment type labels shown in the recalled-message bubble. */
    const val TYPE_TEXT     = "text"
    const val TYPE_PHOTO    = "photo"
    const val TYPE_VIDEO    = "video"
    const val TYPE_AUDIO    = "audio"
    const val TYPE_FILE     = "file"
    const val TYPE_STICKER  = "sticker"
    const val TYPE_GIF      = "gif"
    const val TYPE_LOCATION = "location"
    const val TYPE_UNKNOWN  = "attachment"

    data class Entry(
        val messageId:      String,
        /** Plain-text body of the message (null if it was a pure attachment). */
        val body:           String?,
        /** One of the TYPE_* constants above. */
        val attachmentType: String,
        /** Sender display name, if we managed to extract it. */
        val senderName:     String?,
    )

    // ------------------------------------------------------------------
    // Write
    // ------------------------------------------------------------------

    fun put(entry: Entry) {
        if (entry.messageId.isBlank()) return
        store.put(entry.messageId, entry)
    }

    // ------------------------------------------------------------------
    // Read
    // ------------------------------------------------------------------

    fun get(messageId: String): Entry? = store.get(messageId)

    fun has(messageId: String): Boolean = store.get(messageId) != null

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /**
     * Build a human-readable label for the recalled-message bubble,
     * matching the style of the Zalo mod screenshot:
     *   "[Recalled] Some text here"
     *   "[Recalled] 📷 Photo"
     *   "[Recalled] 📹 Video"  etc.
     */
    fun formatLabel(entry: Entry): String {
        val prefix = "🔴 [Recalled]"
        return when {
            !entry.body.isNullOrBlank() -> "$prefix ${entry.body}"
            entry.attachmentType == TYPE_PHOTO    -> "$prefix 📷 Photo"
            entry.attachmentType == TYPE_VIDEO    -> "$prefix 📹 Video"
            entry.attachmentType == TYPE_AUDIO    -> "$prefix 🎵 Audio"
            entry.attachmentType == TYPE_STICKER  -> "$prefix 🎭 Sticker"
            entry.attachmentType == TYPE_GIF      -> "$prefix 🎞️ GIF"
            entry.attachmentType == TYPE_FILE     -> "$prefix 📎 File"
            entry.attachmentType == TYPE_LOCATION -> "$prefix 📍 Location"
            else                                  -> "$prefix (unsupported attachment)"
        }
    }
}
