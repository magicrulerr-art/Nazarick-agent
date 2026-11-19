package me.rerere.ai.provider.providers.nazarick

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers [NazarickResponsesParser] against a slice of the REAL QwenPaw chat capture (US-002).
 *
 * The fixture (`ai/src/test/resources/nazarick/stream_fixture.txt`) is a verbatim 255-line slice of
 * a live `POST /api/console/chat` SSE stream: response created/in_progress, one reasoning message
 * opening, 123 text deltas, the server's own `delta=false` aggregation, and the message-completed
 * snapshot. Its only synthetic line is the terminal `response.completed` (the capture was cut
 * mid-line by the spike harness before the server emitted it), documented inside the fixture.
 *
 * Two properties matter and are asserted separately, because a parser can satisfy one and be wrong:
 *  - the reassembled text equals [EXPECTED_TEXT], which was computed offline from the capture and
 *    is independently re-derived from the server's aggregated snapshot;
 *  - ordering authority is `sequence_number`, not arrival order.
 */
class NazarickResponsesParserTest {

    private val fixtureLines: List<String> by lazy { loadFixture().lines() }

    // ---------- the real capture ----------

    @Test
    fun `fixture opens with a response start then a message open carrying the msg id`() {
        val (_, events) = replay()
        assertEquals(
            "the first event of the wire stream is the response start",
            NazarickEvent.ResponseStarted(responseId = RESPONSE_ID, sequenceNumber = 1),
            events.first(),
        )
        assertEquals(
            "the created + in_progress response events must not start the stream twice",
            1,
            events.filterIsInstance<NazarickEvent.ResponseStarted>().size,
        )
        assertEquals(
            "the slice opens exactly one message",
            listOf(MSG_ID),
            events.filterIsInstance<NazarickEvent.MessageOpened>().map { it.msgId },
        )
        assertTrue(
            "the message has to open before its first delta arrives",
            events.indexOfFirst { it is NazarickEvent.MessageOpened } <
                    events.indexOfFirst { it is NazarickEvent.TextDelta },
        )
    }

    @Test
    fun `fixture reassembles the exact text the server itself aggregated`() {
        val (parser, _) = replay()
        assertEquals(EXPECTED_TEXT, parser.reassemble())
        assertEquals(
            "the delta stream must reproduce the delta=false snapshot the server sent for the same block",
            serverSnapshotText(),
            parser.reassemble(),
        )
        assertEquals(
            "the recorded expectation must match the snapshot inside the fixture",
            EXPECTED_TEXT,
            serverSnapshotText(),
        )
    }

    @Test
    fun `fixture yields at least 100 deltas in ascending sequence_number order`() {
        val (parser, _) = replay()
        val deltas = parser.textDeltas
        assertTrue(
            "the acceptance criteria demand >=100 deltas from the real capture, got ${deltas.size}",
            deltas.size >= 100,
        )
        val sequences = deltas.map { requireNotNull(it.sequenceNumber) { "every real delta carries a sequence_number" } }
        assertEquals("no sequence number may be accepted twice", sequences.size, sequences.toSet().size)
        val breach = sequences.zipWithNext().indexOfFirst { (a, b) -> b <= a }
        assertTrue("wire order must be ascending, first breach at index $breach", breach < 0)
        assertEquals("the capture starts on the word The", "The", deltas.first().text)
        assertEquals("every delta in this slice belongs to the one opened message",
            listOf(MSG_ID), deltas.map { it.msgId }.distinct())
    }

    @Test
    fun `fixture ends with exactly one response completed event carrying the response id`() {
        val (_, events) = replay()
        val completed = events.filterIsInstance<NazarickEvent.ResponseCompleted>()
        assertEquals("one completion per stream", 1, completed.size)
        assertTrue("the completion has to be the last event of the stream", events.last() is NazarickEvent.ResponseCompleted)
        assertEquals(
            "the completion carries the same response id the stream started with",
            RESPONSE_ID,
            completed.single().responseId,
        )
    }

    @Test
    fun `message completed and content snapshots never duplicate the text`() {
        val (parser, _) = replay()
        val before = parser.reassemble()
        val snapshot = fixtureLines.first { it.contains("\"delta\":false") || it.contains("\"delta\": false") }
        val messageCompleted = fixtureLines
            .first { it.contains("\"object\":\"message\"") && it.contains("\"completed\"") }
        repeat(3) {
            assertTrue("the aggregated snapshot must yield no events", parser.feedLine(snapshot).isEmpty())
            assertTrue("the message-completed event must yield no events", parser.feedLine(messageCompleted).isEmpty())
        }
        assertEquals("replaying the aggregations must not grow the text", before, parser.reassemble())
    }

    @Test
    fun `the server snapshot and the delta stream cover the same words`() {
        // Guards the fixture itself: if a future re-slice drops deltas, the >=100 test would still
        // pass while the reassembly silently stops matching anything real.
        val (parser, _) = replay()
        val snapshot = serverSnapshotText()
        assertTrue("snapshot must be real prose, got ${snapshot.length} chars", snapshot.length > 400)
        assertTrue(
            "the reassembled text must be the snapshot, not a prefix of it",
            parser.reassemble().length == snapshot.length,
        )
    }

    // ---------- robustness ----------

    @Test
    fun `a replayed delta with an already seen sequence number is ignored`() {
        val parser = NazarickResponsesParser()
        val deltaLine = fixtureLines.first { it.contains("\"delta\": true") }
        assertEquals(1, parser.feedLine(deltaLine).size)
        assertTrue("a redelivered event must not duplicate text", parser.feedLine(deltaLine).isEmpty())
        assertEquals(1, parser.textDeltas.size)
    }

    @Test
    fun `a message open is emitted once per msg id`() {
        val parser = NazarickResponsesParser()
        val line = """data: {"id":"msg_a","type":"reasoning","role":"assistant","content":[],"status":"in_progress","object":"message","sequence_number":3}"""
        assertEquals(
            listOf(NazarickEvent.MessageOpened("msg_a", "reasoning")),
            parser.feedLine(line),
        )
        assertTrue("a repeated in_progress tick for the same message is not a new message",
            parser.feedLine(line).isEmpty())
    }

    @Test
    fun `data typed content deltas carry plugin arguments and no prose`() {
        val parser = NazarickResponsesParser()
        assertTrue("plugin-call argument fragments are not chat text", parser.feedLine(DATA_DELTA).isEmpty())
        assertEquals("", parser.reassemble())
    }

    @Test
    fun `turn usage and unknown objects are ignored`() {
        val parser = NazarickResponsesParser()
        assertTrue("turn_usage carries no object key at all", parser.feedLine(TURN_USAGE).isEmpty())
        assertTrue(parser.feedLine("""data: {"object":"banana","status":"completed"}""").isEmpty())
        assertTrue(parser.feedLine("""data: {"status":"completed"}""").isEmpty())
        assertEquals("", parser.reassemble())
    }

    @Test
    fun `content deltas without a text field are ignored instead of emitting null`() {
        val parser = NazarickResponsesParser()
        assertTrue(parser.feedLine(
            """data: {"type":"text","delta":true,"index":0,"status":null,"object":"content","msg_id":"msg_a","sequence_number":7}""",
        ).isEmpty())
        assertTrue(parser.feedLine(
            """data: {"type":"text","delta":true,"object":"content","text":null,"sequence_number":8}""",
        ).isEmpty())
        assertEquals("", parser.reassemble())
    }

    @Test
    fun `sse metadata blank lines and null input are ignored`() {
        val parser = NazarickResponsesParser()
        listOf(null, "", "   ", "id: 42", "event: response.output_item.added", "retry: 3000", ":", ": keep-alive")
            .forEach { line ->
                assertTrue("SSE bookkeeping must not produce events: $line", parser.feedLine(line).isEmpty())
            }
        assertEquals("", parser.reassemble())
    }

    @Test
    fun `garbage interleaved into the real stream never throws and never corrupts the text`() {
        val garbage = listOf(
            "", "   ", "id: 42", "event: response.content_part.added", ": keep-alive", "retry: 3000",
            "data:", "data:   ", "data: [DONE]", "data: not json at all", "data: {", "data: {\"object\":",
            "data: [1,2,3]", "data: \"a bare string\"", "data: null", "data: {}", "data: {\"}\"",
            TRUNCATED_TAIL,
        )
        val clean = replay()
        val parser = NazarickResponsesParser()
        val events = mutableListOf<NazarickEvent>()
        fixtureLines.forEachIndexed { index, line ->
            events += parser.feedLine(line)
            events += parser.feedLine(garbage[index % garbage.size])
        }
        assertEquals("junk between real lines must not change a single character", EXPECTED_TEXT, parser.reassemble())
        assertEquals(
            "junk between real lines must not change the accepted delta count",
            clean.first.textDeltas.size,
            parser.textDeltas.size,
        )
        assertEquals(
            "junk must not change the emitted event stream at all",
            clean.second.map { it::class.simpleName },
            events.map { it::class.simpleName },
        )
    }

    // ---------- ordering ----------

    @Test
    fun `deltas arriving out of order are reassembled in sequence_number order`() {
        val parser = NazarickResponsesParser()
        parser.feedLine(delta(text = "three", sequence = 9))
        parser.feedLine(delta(text = "one", sequence = 7))
        parser.feedLine(delta(text = "two", sequence = 8))
        assertEquals("onetwothree", parser.reassemble())
    }

    @Test
    fun `a delta with no sequence number sorts last but still reaches the text`() {
        val parser = NazarickResponsesParser()
        parser.feedLine(delta(text = "one", sequence = 1))
        parser.feedLine("""data: {"type":"text","delta":true,"object":"content","msg_id":"msg_a","text":"unnumbered"}""")
        assertEquals("oneunnumbered", parser.reassemble())
    }

    @Test
    fun `reassemble can be scoped to a single msg id`() {
        val parser = NazarickResponsesParser()
        parser.feedLine(delta(text = "reasoning ", sequence = 1, msgId = "msg_reason"))
        parser.feedLine(delta(text = "answer", sequence = 2, msgId = "msg_final"))
        assertEquals("reasoning answer", parser.reassemble())
        assertEquals("reasoning ", parser.reassemble(msgId = "msg_reason"))
        assertEquals("answer", parser.reassemble(msgId = "msg_final"))
        assertEquals("", parser.reassemble(msgId = "msg_missing"))
    }

    // ---------- helpers ----------

    private fun replay(lines: List<String> = fixtureLines): Pair<NazarickResponsesParser, List<NazarickEvent>> {
        val parser = NazarickResponsesParser()
        val events = lines.flatMapTo(mutableListOf()) { parser.feedLine(it) }
        return parser to events
    }

    private fun delta(text: String, sequence: Int?, msgId: String = MSG_ID): String =
        "data: {\"type\": \"text\", \"delta\": true, \"index\": 0, \"object\": \"content\", " +
                "\"msg_id\": \"$msgId\", \"text\": \"$text\", \"sequence_number\": $sequence}"

    /** The server's own aggregation of the same block: the `delta=false` content event. */
    private fun serverSnapshotText(): String {
        val line = fixtureLines.first { it.contains("\"delta\":false") || it.contains("\"delta\": false") }
        val obj = Json.parseToJsonElement(line.removePrefix("data:").trim()) as JsonObject
        assertEquals("text", requireNotNull(obj["type"]).jsonPrimitive.contentOrNull)
        assertTrue("the snapshot line must be the delta=false aggregation", obj["delta"]?.jsonPrimitive?.booleanOrNull == false)
        return requireNotNull(obj["text"]).jsonPrimitive.contentOrNull
    }

    private fun loadFixture(): String {
        val url = requireNotNull(javaClass.classLoader?.getResource(FIXTURE)) { "Missing test resource: $FIXTURE" }
        // Explicit UTF-8: the capture contains em dashes, and a runner whose default charset is
        // ASCII (LANG=C) would otherwise turn them into '?' and fail the text assertion.
        return url.openStream().use { it.reader(Charsets.UTF_8).readText() }
    }

    private companion object {
        const val FIXTURE = "nazarick/stream_fixture.txt"
        const val RESPONSE_ID = "response_5bbf4e867f45402fbb276add9274db3e"
        const val MSG_ID = "msg_d8596b57adf64c8fbf35157c6c57912a"

        /** Verbatim first plugin-argument delta of the same capture (type=data, no prose). */
        const val DATA_DELTA = """data: {"type": "data", "delta": true, "index": 0, "status": "in_progress", "object": "content", "msg_id": "msg_7425315b3438488b9eb70a79ce97f8b2", "data": {"call_id": "call_sd5xt5pk75mlgu8qolg4", "name": "run_code", "arguments": ""}, "sequence_number": 130}"""

        /** Verbatim usage record from the same capture — it carries no `object` key. */
        const val TURN_USAGE = """data: {"type": "turn_usage", "session_id": "v2spike", "usage": {"provider_id": "lemonade", "model_name": "GufoQ4MixMTP", "prompt_tokens": 10029, "completion_tokens": 270, "total_tokens": 10299}, "context_usage": {"estimated_tokens": 10029, "max_input_length": 131072}}"""

        /** The real capture's final line: JSON torn in half by the spike harness. */
        const val TRUNCATED_TAIL = "data: {\"type\": \"text\", \"delta\": true, \"index\": 0, \"status\": null, \"object\": \"content\", \"msg_id\""

        /**
         * The assistant text of the fixture slice, computed offline from the capture (sha256 prefix
         * 4d66e54cffd598dd, 523 chars) and equal to the `delta=false` snapshot the server emitted
         * for that block. Hard-coded on purpose: deriving it from the fixture again would let a
         * parser that reads the wrong field still pass.
         */
        const val EXPECTED_TEXT = "The user sent an image (a screenshot) with no text. Let me look at it. Actually the message appears empty — just the turn. I should load the image? There's no path given. Hmm, the user message is empty. Maybe there's an attachment I can't see.\n\nLet me check the working directory for recent files, or just ask. Actually, with an empty user message, the best response is to ask what Ainz-sama needs. But maybe there's a file dropped in the workspace.\n\nLet me do a quick read of the workspace to see if something new landed.\n"
    }
}
