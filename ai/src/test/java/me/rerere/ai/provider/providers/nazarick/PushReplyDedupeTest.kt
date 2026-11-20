package me.rerere.ai.provider.providers.nazarick

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * US-007 AC "Unit test covers dedupe logic" - the pure-JVM dialect of the neighbouring
 * `NazarickStopTest` / `NazarickCheckMappingTest` (JUnit4, no Android, no socket).
 *
 * The cases are the ones the acceptance criteria name: the same message twice, different content,
 * empty content, and arrival order - plus the two shapes the server can actually produce that a
 * naive id-only or content-only key would get wrong (same text re-minted under a fresh uuid, and a
 * re-delivered uuid). The cap is asserted too: the poller lives as long as the process does.
 */
class PushReplyDedupeTest {

    @Test
    fun `same message twice notifies once`() {
        val dedupe = PushReplyDedupe()
        val msg = NazarickPushMessage(id = "0f0f", text = "The Tomb answers")
        assertTrue("first delivery must notify", dedupe.shouldNotify(msg))
        assertFalse("re-delivery of the same id+text must stay silent", dedupe.shouldNotify(msg))
        assertFalse(dedupe.shouldNotify(msg.copy()))
    }

    @Test
    fun `different content notifies every time`() {
        val dedupe = PushReplyDedupe()
        assertTrue(dedupe.shouldNotify(NazarickPushMessage(id = "a", text = "first reply")))
        assertTrue(dedupe.shouldNotify(NazarickPushMessage(id = "b", text = "second reply")))
        // Two identities per message (content hash + server id), nothing evicted at capacity 512.
        assertEquals(4, dedupe.seenSize)
    }

    @Test
    fun `empty and blank text never notify and are never remembered`() {
        val dedupe = PushReplyDedupe()
        assertFalse(dedupe.shouldNotify(NazarickPushMessage(id = "a", text = "")))
        assertFalse(dedupe.shouldNotify(NazarickPushMessage(id = "b", text = "   \n\t ")))
        assertFalse(dedupe.shouldNotify(NazarickPushMessage(id = "", text = "")))
        assertEquals("nothing notify-able was seen, so nothing is remembered", 0, dedupe.seenSize)
    }

    @Test
    fun `arrival order notifies each content exactly once`() {
        val dedupe = PushReplyDedupe()
        val alpha = NazarickPushMessage(id = "1", text = "alpha")
        val beta = NazarickPushMessage(id = "2", text = "beta")
        assertTrue(dedupe.shouldNotify(alpha))
        assertTrue(dedupe.shouldNotify(beta))
        assertFalse(dedupe.shouldNotify(alpha))
        assertFalse(dedupe.shouldNotify(beta))
        assertTrue(dedupe.shouldNotify(NazarickPushMessage(id = "3", text = "gamma")))
        assertFalse(dedupe.shouldNotify(beta))
    }

    @Test
    fun `same text re-pushed under a fresh server id stays silent`() {
        // The AC wording is about *content*: a producer that pushes the identical line twice
        // (cron rerun, re-appended sticky message) must not buzz the phone twice.
        val dedupe = PushReplyDedupe()
        assertTrue(dedupe.shouldNotify(NazarickPushMessage(id = "uuid-one", text = "Cron: build finished")))
        assertFalse(dedupe.shouldNotify(NazarickPushMessage(id = "uuid-two", text = "Cron: build finished")))
    }

    @Test
    fun `re-delivery of a seen id stays silent even when the text differs`() {
        val dedupe = PushReplyDedupe()
        assertTrue(dedupe.shouldNotify(NazarickPushMessage(id = "uuid-one", text = "answer")))
        assertFalse(
            "one server message = one notification, whatever the body looks like on the retry",
            dedupe.shouldNotify(NazarickPushMessage(id = "uuid-one", text = "answer (re-rendered)")),
        )
    }

    @Test
    fun `whitespace differences are the same content`() {
        val dedupe = PushReplyDedupe()
        assertTrue(dedupe.shouldNotify(NazarickPushMessage(id = "a", text = "hello")))
        assertFalse(dedupe.shouldNotify(NazarickPushMessage(id = "b", text = "  hello\n")))
    }

    @Test
    fun `seen set stays capped and the oldest identities are dropped first`() {
        val dedupe = PushReplyDedupe(capacity = 4)
        assertTrue(dedupe.shouldNotify(NazarickPushMessage(id = "1", text = "one")))
        assertTrue(dedupe.shouldNotify(NazarickPushMessage(id = "2", text = "two")))
        assertTrue(dedupe.shouldNotify(NazarickPushMessage(id = "3", text = "three")))
        assertEquals("capacity is a hard bound", 4, dedupe.seenSize)
        // Message 1's two identities were the oldest, so it may notify again; the newest (3) does not.
        assertTrue(dedupe.shouldNotify(NazarickPushMessage(id = "1", text = "one")))
        assertFalse(dedupe.shouldNotify(NazarickPushMessage(id = "3", text = "three")))
        assertTrue(dedupe.seenSize <= 4)
    }
}
