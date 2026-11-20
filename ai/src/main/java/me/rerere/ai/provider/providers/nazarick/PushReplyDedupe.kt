package me.rerere.ai.provider.providers.nazarick

import java.security.MessageDigest

/**
 * US-007 - notification dedupe for pushed replies (pure JVM, no Android, unit-tested in
 * `PushReplyDedupeTest`).
 *
 * Why a client-side gate exists at all: `take(session_id)` removes messages server-side, so a clean
 * poll never re-delivers the same row. But the AC still forbids duplicate notifications, and there
 * are real paths to one - the same text pushed twice by the producer (a cron rerun, a re-appended
 * sticky message) lands under a **fresh** uuid, and a poll that was cut over by a reconnect can
 * re-take what an earlier tick had already shown. The server store's own docstring expects this
 * too ("Frontend dedupes by id and caps its seen set").
 *
 * Key rule - a message notifies only when **neither** of its two identities has been seen:
 *  - `c:<sha256 of the trimmed text>` - the content itself, so identical text re-pushed under a new
 *    server id is still "the same content" and stays silent (AC wording);
 *  - `i:<server id>` - the uuid4 the store mints per push, so a re-delivery of one message cannot
 *    slip past on a text difference the app itself introduced (whitespace normalisation, a future
 *    field folded into the body).
 *
 * A blank/empty text never notifies - there is no reply content to show - and is never remembered,
 * so an always-blank producer cannot fill the seen set.
 *
 * The set is capped (oldest entry dropped first, insertion order) because the poller can live for
 * hours: an unbounded set over a chatty Tomb is a leak with good manners.
 */
class PushReplyDedupe(private val capacity: Int = DEFAULT_CAPACITY) {

    private val seen = LinkedHashSet<String>()

    /**
     * True when this message deserves a notification. Called once per received message, in the
     * order the poller received them; a true result also records the message's identities.
     */
    fun shouldNotify(message: NazarickPushMessage): Boolean {
        val keys = dedupeKeysOf(message)
        if (keys.isEmpty()) return false
        if (keys.any { it in seen }) return false
        seen += keys
        while (seen.size > capacity) {
            val oldest = seen.iterator()
            if (!oldest.hasNext()) break
            oldest.next()
            oldest.remove()
        }
        return true
    }

    /** Test/inspection seam: how many identities are currently remembered. */
    val seenSize: Int
        get() = seen.size

    companion object {
        /** Headroom over the server store's own bound (`_MAX_MESSAGES = 500`). */
        const val DEFAULT_CAPACITY = 512
    }
}

/**
 * The identity keys of [message]: content first, then the server id when it carries one. Empty list
 * when the message has no notify-able text - the "never seen, never remembered" case.
 */
fun dedupeKeysOf(message: NazarickPushMessage): List<String> {
    val text = message.text.trim()
    if (text.isEmpty()) return emptyList()
    val contentKey = "c:" + contentHash(text)
    val id = message.id.trim()
    return if (id.isEmpty()) listOf(contentKey) else listOf(contentKey, "i:$id")
}

/**
 * Content hash: SHA-256 over UTF-8 bytes, first 16 hex chars. Deterministic across processes and
 * server restarts (unlike an object identity), and a 64-bit prefix makes a collision inside a
 * [PushReplyDedupe.DEFAULT_CAPACITY]-entry window a statistical curiosity rather than a plan.
 */
private fun contentHash(text: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
    return digest.joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }.take(16)
}
