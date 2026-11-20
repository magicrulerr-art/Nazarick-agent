package me.rerere.rikkahub.data.ai

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.runtime.util.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * US-009r: `UIMessage.brainKind` has to survive the trip through the Room column, which stores
 * the whole message list as one JSON blob (`message_node.messages`) written by [JsonInstant].
 * These are the two halves of the "no migration" claim, proven on the store's real codec rather
 * than on a default `Json`:
 *
 *  - a payload written before this card, which has no `brainKind` key at all, decodes to `""`
 *    rather than failing — the default IS the migration;
 *  - `""` is never written back out (`@EncodeDefault(NEVER)`, despite `encodeDefaults = true`),
 *    so re-saving a conversation leaves untouched messages byte-identical on disk.
 */
class BrainKindPersistenceTest {

    private val legacyPayload = """{"role":"assistant","parts":[]}"""

    @Test
    fun `legacy payload without the field decodes to empty`() {
        val decoded = JsonInstant.decodeFromString<UIMessage>(legacyPayload)

        assertEquals(MessageRole.ASSISTANT, decoded.role)
        assertEquals("", decoded.brainKind)
    }

    @Test
    fun `empty brain kind is not written so existing payloads stay byte-identical`() {
        val encoded = JsonInstant.encodeToString(UIMessage.assistant("hi"))

        assertFalse(encoded, encoded.contains("brainKind"))
        assertEquals("", JsonInstant.decodeFromString<UIMessage>(encoded).brainKind)
    }

    @Test
    fun `stamped brain kind round-trips`() {
        val stamped = UIMessage.assistant("hi").copy(brainKind = BrainBadge.STORED_TOMB)

        val encoded = JsonInstant.encodeToString(stamped)
        assertTrue(encoded, encoded.contains("\"brainKind\":\"tomb\""))
        assertEquals(BrainBadge.STORED_TOMB, JsonInstant.decodeFromString<UIMessage>(encoded).brainKind)
    }
}
