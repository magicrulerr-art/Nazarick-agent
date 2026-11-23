package me.rerere.ai.companion

import okhttp3.MediaType
import okhttp3.Request
import okhttp3.RequestBody

/**
 * US-008: companion-lane client.
 *
 * Pure-logic client for the companion agent chat endpoint:
 *   POST http://HOST:PORT/api/companion/agents/{agentId}/chat
 *   headers: X-Companion-Device, Authorization
 *   response: SSE stream decoded into typed events text-delta / completed / error
 *
 * The lane sits behind a debug flag [CompanionLaneConfig.enabled] (default false):
 * when disabled the builder refuses to produce requests.
 *
 * [CompanionLaneRequestBuilder] and [CompanionLaneSseParser] are separable pure
 * logic classes with no Android imports; OkHttp wiring, HOST/PORT and the debug
 * flag value are supplied by the caller (app module / settings).
 */

/** Typed events parsed from the companion SSE stream. */
sealed class CompanionEvent {
    data class TextDelta(val text: String) : CompanionEvent()
    data class Error(val message: String) : CompanionEvent()
    object Completed : CompanionEvent()
}

/** Configuration + debug gate for the companion lane. */
data class CompanionLaneConfig(
    val host: String,
    val port: Int,
    val deviceId: String,
    val authorization: String,
    /** Debug flag: false = companion lane disabled, no requests are built. */
    val enabled: Boolean = false,
)

/** Builds the okhttp POST request for /api/companion/agents/{agentId}/chat. */
class CompanionLaneRequestBuilder private constructor(
    private val config: CompanionLaneConfig,
) {
    constructor(
        host: String,
        port: Int,
        deviceId: String,
        authorization: String,
        enabled: Boolean = false,
    ) : this(CompanionLaneConfig(host, port, deviceId, authorization, enabled))

    fun chatUrl(agentId: String): String =
        "http://${config.host}:${config.port}/api/companion/agents/$agentId/chat"

    fun chatRequest(agentId: String): Request {
        check(config.enabled) {
            "Companion lane is disabled behind the debug flag; enable it before building requests"
        }
        return Request.Builder()
            .url(chatUrl(agentId))
            .header("X-Companion-Device", config.deviceId)
            .header("Authorization", config.authorization)
            .post(
                RequestBody.create(
                    "{}".toByteArray(),
                    MediaType.get("application/json"),
                )
            )
            .build()
    }
}

/** Parses SSE lines into typed [CompanionEvent]s, one line at a time. */
class CompanionLaneSseParser {

    private enum class EventType {
        TEXT_DELTA, COMPLETED, ERROR, UNKNOWN
    }

    private val data = StringBuilder()
    private var eventType: EventType = EventType.UNKNOWN
    private var id: String? = null
    private var retryMillis: Long? = null

    /**
     * Feed one SSE line. Returns the events completed by this line: a blank line
     * terminates one SSE message and emits its event; field lines return empty.
     * Malformed lines (no `:` separator) are tolerated and skipped.
     */
    fun onLine(line: String): List<CompanionEvent> {
        val trimmed = line.trimEnd('\r')
        return if (trimmed.isBlank()) {
            flush().also { reset() }
        } else {
            applyField(trimmed)
            emptyList()
        }
    }

    /** Flush any unterminated message at end of stream. */
    fun finish(): List<CompanionEvent> = flush().also { reset() }

    private fun applyField(line: String) {
        val colon = line.indexOf(':')
        if (colon < 0) return // malformed line: tolerate and skip
        val name = line.substring(0, colon)
        var value = line.substring(colon + 1)
        if (value.startsWith(' ')) value = value.substring(1) // SSE convention
        when (name) {
            "event" -> eventType = when (value) {
                "text-delta" -> EventType.TEXT_DELTA
                "completed" -> EventType.COMPLETED
                "error" -> EventType.ERROR
                else -> EventType.UNKNOWN
            }
            "data" -> {
                if (data.isNotEmpty()) data.append('\n')
                data.append(value)
            }
            "id" -> id = value
            "retry" -> retryMillis = value.toLongOrNull()
        }
    }

    private fun flush(): List<CompanionEvent> = when (eventType) {
        EventType.TEXT_DELTA -> listOf(CompanionEvent.TextDelta(data.toString()))
        EventType.ERROR -> listOf(CompanionEvent.Error(data.toString()))
        EventType.COMPLETED -> listOf(CompanionEvent.Completed)
        EventType.UNKNOWN -> emptyList()
    }

    private fun reset() {
        data.setLength(0)
        eventType = EventType.UNKNOWN
        id = null
        retryMillis = null
    }
}

/**
 * Thin client: request builder + SSE line parser. Execution (OkHttp call + SSE
 * connection) is the caller's responsibility; this class stays pure logic.
 */
class CompanionLaneClient(
    private val requestBuilder: CompanionLaneRequestBuilder,
    private val sseParser: CompanionLaneSseParser = CompanionLaneSseParser(),
) {
    fun chatRequest(agentId: String): Request = requestBuilder.chatRequest(agentId)

    fun onSseLine(line: String): List<CompanionEvent> = sseParser.onLine(line)

    fun finishSse(): List<CompanionEvent> = sseParser.finish()
}
