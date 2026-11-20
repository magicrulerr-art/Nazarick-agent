package me.rerere.ai.provider.providers.nazarick

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.ui.StreamChunk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * US-004 acceptance leg: the pure event->chunk seam ([eventsToChunks]) plus the request-shaping
 * helpers ([nazarickChatRequestBody], [nazarickSessionId]) tested on the plain JVM — no OkHttp,
 * no socket, no Robolectric. The streaming loop in `NazarickProvider.streamText` is just the
 * transport for exactly these decisions.
 */
class NazarickChatTest {

    // ---- request shape ----

    @Test
    fun `request body carries input stream true and the session id`() {
        val body = nazarickChatRequestBody(input = "hello tomb", sessionId = "app-z7-chat1")
        val obj = Json.parseToJsonElement(body) as JsonObject
        assertEquals("hello tomb", obj["input"]?.jsonPrimitive?.contentOrNull)
        assertEquals(true, obj["stream"]?.jsonPrimitive?.booleanOrNull)
        assertEquals("app-z7-chat1", obj["session_id"]?.jsonPrimitive?.contentOrNull)
    }

    @Test
    fun `session id falls back to a named unknown chat instead of a bare device slug`() {
        assertEquals("app-device-unknown-chat", nazarickSessionId("device", null))
        assertEquals("app-z7-chat-42", nazarickSessionId("z7", "Chat 42"))
    }

    // ---- eventsToChunks: the seam ----

    @Test
    fun `first delta opens the text part exactly once`() {
        val batch = eventsToChunks(
            events = listOf(
                NazarickEvent.TextDelta(1, "msg_1", "Hel"),
                NazarickEvent.TextDelta(2, "msg_1", "lo"),
            ),
            textId = "t",
        )
        assertEquals(
            listOf(
                StreamChunk.TextStart("t"),
                StreamChunk.TextDelta("t", "Hel"),
                StreamChunk.TextDelta("t", "lo"),
            ),
            batch.chunks,
        )
        assertTrue(batch.textOpen)
        assertFalse(batch.completed)
    }

    @Test
    fun `continuation batch does not re-open an already open part`() {
        val batch = eventsToChunks(
            events = listOf(NazarickEvent.TextDelta(3, "msg_1", "more")),
            textId = "t",
            textOpen = true,
        )
        assertEquals(listOf(StreamChunk.TextDelta("t", "more")), batch.chunks)
        assertTrue(batch.textOpen)
    }

    @Test
    fun `empty delta text is dropped`() {
        val batch = eventsToChunks(
            events = listOf(NazarickEvent.TextDelta(1, "msg_1", "")),
            textId = "t",
        )
        assertTrue(batch.chunks.isEmpty())
        assertFalse(batch.textOpen)
    }

    @Test
    fun `completion closes an open part and emits Finish once`() {
        val batch = eventsToChunks(
            events = listOf(
                NazarickEvent.TextDelta(1, "msg_1", "text"),
                NazarickEvent.ResponseCompleted("resp_1", 9),
            ),
            textId = "t",
            model = "demiurge",
        )
        assertEquals(
            listOf(
                StreamChunk.TextStart("t"),
                StreamChunk.TextDelta("t", "text"),
                StreamChunk.TextEnd("t"),
                StreamChunk.Finish(
                    finishReason = NAZARICK_FINISH_COMPLETED,
                    responseId = "resp_1",
                    model = "demiurge",
                ),
            ),
            batch.chunks,
        )
        assertFalse(batch.textOpen)
        assertTrue(batch.completed)
    }

    @Test
    fun `finish keeps the id from an earlier started event when completed carries none`() {
        val batch = eventsToChunks(
            events = listOf(
                NazarickEvent.ResponseStarted("resp_7", 1),
                NazarickEvent.ResponseCompleted(null, 2),
            ),
            textId = "t",
        )
        val finish = batch.chunks.last() as StreamChunk.Finish
        assertEquals("resp_7", finish.responseId)
        assertTrue(batch.completed)
    }

    @Test
    fun `message opened events are dropped, not invented into prose`() {
        val batch = eventsToChunks(
            events = listOf(NazarickEvent.MessageOpened("msg_1", "reasoning")),
            textId = "t",
        )
        assertTrue(batch.chunks.isEmpty())
        assertFalse(batch.completed)
    }
}
