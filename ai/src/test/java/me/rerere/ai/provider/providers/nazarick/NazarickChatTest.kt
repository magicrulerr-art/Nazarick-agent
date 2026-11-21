package me.rerere.ai.provider.providers.nazarick

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
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
        val items = obj["input"] as JsonArray
        val firstItem = items[0] as JsonObject
        assertEquals("user", firstItem["role"]?.jsonPrimitive?.contentOrNull)
        val parts = firstItem["content"] as JsonArray
        val part = parts[0] as JsonObject
        assertEquals("text", part["type"]?.jsonPrimitive?.contentOrNull)
        assertEquals("hello tomb", part["text"]?.jsonPrimitive?.contentOrNull)
        assertEquals(true, obj["stream"]?.jsonPrimitive?.booleanOrNull)
        assertEquals("app-z7-chat1", obj["session_id"]?.jsonPrimitive?.contentOrNull)
    }

    @Test
    fun `session id falls back to a named unknown chat instead of a bare device slug`() {
        assertEquals("app-device-unknown-chat", nazarickSessionId("device", null))
        assertEquals("app-z7-chat-42", nazarickSessionId("z7", "Chat 42"))
    }

    // ---- US-011r: the image part on the wire ----

    /** The one user turn's content array, or a failure naming the body that lacked it. */
    private fun userTurnParts(body: String): List<JsonObject> {
        val obj = Json.parseToJsonElement(body) as JsonObject
        // The live-probed contract: the user turn rides the `input` items array itself.
        val items = obj["input"]?.jsonArray ?: error("body carries no input items array: $body")
        assertEquals("exactly one user turn is sent", 1, items.size)
        val turn = items[0].jsonObject
        assertEquals("user", turn["role"]?.jsonPrimitive?.contentOrNull)
        return turn["content"]?.jsonArray?.map { it.jsonObject }
            ?: error("user turn carries no content array: $body")
    }

    @Test
    fun `with image the content array is input_text then input_image with the uri verbatim`() {
        val dataUri = "data:image/png;base64,iVBORw0KGgoAAAANSUhEUg=="
        val body = nazarickChatRequestBodyWithImage(
            input = "what is on this picture",
            sessionId = "app-z7-chat1",
            imageDataUri = dataUri,
        )
        val parts = userTurnParts(body)
        assertEquals(
            listOf("text", "input_image"),
            parts.map { it["type"]?.jsonPrimitive?.contentOrNull },
        )
        assertEquals("what is on this picture", parts[0]["text"]?.jsonPrimitive?.contentOrNull)
        assertEquals(
            "the caller's data URI must reach the server untouched",
            dataUri,
            parts[1]["image_url"]?.jsonPrimitive?.contentOrNull,
        )
        // Text leg of the contract still holds next to the image part: the text rides the
        // input_text content part of the same probed user turn, never a duplicate field.
        val obj = Json.parseToJsonElement(body) as JsonObject
        assertEquals("what is on this picture", parts[0]["text"]?.jsonPrimitive?.contentOrNull)
        assertFalse("no parallel items array may ride alongside input", body.contains("\"items\""))
        assertEquals(true, obj["stream"]?.jsonPrimitive?.booleanOrNull)
        assertEquals("app-z7-chat1", obj["session_id"]?.jsonPrimitive?.contentOrNull)
    }

    @Test
    fun `without image the content array is a single input_text part and no input_image at all`() {
        val body = nazarickChatRequestBody(input = "text only", sessionId = "app-z7-chat2")
        val parts = userTurnParts(body)
        assertEquals(
            "an image-less turn must not emit an empty input_image part",
            listOf("text"),
            parts.map { it["type"]?.jsonPrimitive?.contentOrNull },
        )
        assertEquals("text only", parts[0]["text"]?.jsonPrimitive?.contentOrNull)
        assertFalse("no image part may appear on a text-only turn", body.contains("input_image"))
    }

    @Test
    fun `a blank image uri degrades to the text-only body instead of an empty image_url`() {
        val body = nazarickChatRequestBodyWithImage(
            input = "text only",
            sessionId = "app-z7-chat3",
            imageDataUri = "",
        )
        assertFalse(body.contains("input_image"))
        assertEquals(listOf("text"), userTurnParts(body).map { it["type"]?.jsonPrimitive?.contentOrNull })
    }

    @Test
    fun `mime comes from the file extension and unknown types report png`() {
        // The four types the app's picker produces (FileEncoder.supportedTypes) plus jpeg's alias.
        assertEquals("image/png", nazarickImageMimeFromPath("/data/upload/a.png"))
        assertEquals("image/jpeg", nazarickImageMimeFromPath("/data/upload/a.jpg"))
        assertEquals("image/jpeg", nazarickImageMimeFromPath("/data/upload/a.JPEG"))
        assertEquals("image/webp", nazarickImageMimeFromPath("/data/upload/a.webp"))
        // Unknown/absent extension: the probe-verified png, never a made-up type string.
        assertEquals("image/png", nazarickImageMimeFromPath("/data/upload/a.heic"))
        assertEquals("image/png", nazarickImageMimeFromPath("noextension"))
        // A cached/sized url keeps its real extension.
        assertEquals("image/webp", nazarickImageMimeFromPath("/upload/a.webp?w=200#v2"))
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
    fun `message opened events are not invented into prose, only recorded as lane tags`() {
        val batch = eventsToChunks(
            events = listOf(NazarickEvent.MessageOpened("msg_1", "reasoning")),
            textId = "t",
        )
        assertTrue(batch.chunks.isEmpty())
        assertFalse(batch.completed)
        assertEquals(mapOf("msg_1" to "reasoning"), batch.msgKinds)
    }


    // ---- eventsToChunks: reasoning lane (thinking traces) ----

    @Test
    fun `reasoning tagged deltas stream as reasoning chunks under its own part id`() {
        val batch = eventsToChunks(
            events = listOf(
                NazarickEvent.MessageOpened("msg_1", "reasoning"),
                NazarickEvent.TextDelta(1, "msg_1", "The user "),
                NazarickEvent.TextDelta(2, "msg_1", "wants..."),
            ),
            textId = "t",
            reasoningId = "r",
        )
        assertEquals(
            listOf(
                StreamChunk.ReasoningStart("r"),
                StreamChunk.ReasoningDelta("r", "The user "),
                StreamChunk.ReasoningDelta("r", "wants..."),
            ),
            batch.chunks,
        )
        assertTrue(batch.reasoningOpen)
        assertFalse(batch.textOpen)
    }

    @Test
    fun `answer message after reasoning closes into the text lane, not the reasoning lane`() {
        val kinds = mapOf("msg_1" to "reasoning", "msg_2" to "message")
        val batch = eventsToChunks(
            events = listOf(
                NazarickEvent.TextDelta(3, "msg_1", "hmm"),
                NazarickEvent.TextDelta(4, "msg_2", "Answer."),
            ),
            textId = "t",
            reasoningId = "r",
            reasoningOpen = true,
            msgKinds = kinds,
        )
        assertEquals(
            listOf(
                StreamChunk.ReasoningDelta("r", "hmm"),
                StreamChunk.TextStart("t"),
                StreamChunk.TextDelta("t", "Answer."),
            ),
            batch.chunks,
        )
        assertTrue(batch.textOpen)
        assertTrue(batch.reasoningOpen)  // closed only by the terminal event, like text
    }

    @Test
    fun `unannounced msg id degrades to the answer lane instead of silence`() {
        val batch = eventsToChunks(
            events = listOf(NazarickEvent.TextDelta(1, "msg_orphan", "prose")),
            textId = "t",
            reasoningId = "r",
        )
        assertEquals(
            listOf(StreamChunk.TextStart("t"), StreamChunk.TextDelta("t", "prose")),
            batch.chunks,
        )
    }

    @Test
    fun `completion closes reasoning and text lanes each exactly once`() {
        val batch = eventsToChunks(
            events = listOf(
                NazarickEvent.TextDelta(1, "msg_1", "think"),
                NazarickEvent.TextDelta(2, "msg_2", "say"),
                NazarickEvent.ResponseCompleted("resp_1", 9),
            ),
            textId = "t",
            reasoningId = "r",
            reasoningOpen = true,
            msgKinds = mapOf("msg_1" to "reasoning", "msg_2" to "message"),
        )
        assertEquals(
            listOf(
                StreamChunk.ReasoningDelta("r", "think"),
                StreamChunk.TextStart("t"),
                StreamChunk.TextDelta("t", "say"),
                StreamChunk.ReasoningEnd("r"),
                StreamChunk.TextEnd("t"),
                StreamChunk.Finish(
                    finishReason = NAZARICK_FINISH_COMPLETED,
                    responseId = "resp_1",
                    model = null,
                ),
            ),
            batch.chunks,
        )
        assertFalse(batch.reasoningOpen)
        assertFalse(batch.textOpen)
    }

    @Test
    fun `lane tags accumulate across batches the way the streaming loop carries them`() {
        val first = eventsToChunks(
            events = listOf(NazarickEvent.MessageOpened("msg_1", "reasoning")),
            textId = "t",
        )
        val second = eventsToChunks(
            events = listOf(NazarickEvent.TextDelta(1, "msg_1", "deep")),
            textId = "t",
            reasoningId = "r",
            msgKinds = first.msgKinds,
        )
        assertEquals(
            listOf(StreamChunk.ReasoningStart("r"), StreamChunk.ReasoningDelta("r", "deep")),
            second.chunks,
        )
    }
}
