package me.rerere.ai.provider.providers.nazarick

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * US-007 - the pure half of `GET /api/console/push-messages`.
 *
 * Server contract of record (shipped `qwenpaw/app/routers/console.py::get_push_messages` plus
 * `qwenpaw/app/console_push_store.py`): the envelope is
 * `{messages: [...], pending_approvals: [...], notification_events: [...]}` and every message is
 * serialised by `_strip_ts` as exactly `{id, text, sticky}` - `id` is a server-minted uuid4, so it
 * is a stable identity, and `text` is the reply content the user is waiting for.
 *
 * TAKE SEMANTICS - the reason a poller must never poll speculatively: with `session_id` set the
 * endpoint **consumes** that session's pending messages (`take(session_id)` removes them from the
 * store, and anything older than 60 s is pruned on read). A GET whose payload the app drops is a
 * reply nobody will ever see again, so the caller notifies immediately and only asks while a turn
 * is genuinely in flight.
 */
@Serializable
data class NazarickPushMessage(
    val id: String = "",
    val text: String = "",
    val sticky: Boolean = false,
)

/**
 * Deserialiser for the envelope. `pending_approvals` and `notification_events` are deliberately
 * not modelled: `ignoreUnknownKeys` discards them, so a server that grows those lists can never
 * break the message parse. Approvals arrive **unfiltered** (every session, with their own
 * `session_id` field), which makes them a separate product surface - noted as future work, not
 * part of US-007.
 */
@Serializable
private data class NazarickPushEnvelope(
    val messages: List<NazarickPushMessage> = emptyList(),
)

private val pushJson = Json { ignoreUnknownKeys = true }

/** Pure envelope parser: `{messages: [{id, text, sticky}, ...]}` -> list (missing key = empty). */
fun parseNazarickPushMessages(body: String): List<NazarickPushMessage> =
    pushJson.decodeFromString(NazarickPushEnvelope.serializer(), body).messages

/**
 * Outcome of [NazarickClient.getPushMessages], in the [NazarickStopOutcome] dialect with one extra
 * case: a 200 whose body does not parse is a contract failure, not an empty answer, and must never
 * be mistaken for "nothing pending".
 */
sealed class NazarickPushOutcome {
    /** 200 with a parseable envelope. `messages` may be empty - nothing was pending this tick. */
    data class Reply(val messages: List<NazarickPushMessage>) : NazarickPushOutcome()

    /** 401 - the token is dead: the poller stops instead of burning a poll every interval. */
    object Unauthorized : NazarickPushOutcome()

    /** 404 - reachable instance, endpoint absent (older server build). */
    object NotFound : NazarickPushOutcome()

    /** Any other status (5xx, ...): answered, but not with an envelope. */
    data class HttpError(val code: Int) : NazarickPushOutcome()

    /** No answer at all - connection refused/dropped, or an unparseable baseUrl. */
    object NetworkError : NazarickPushOutcome()

    /** 200 but the body is not the promised envelope - reported, never notified. */
    data class Malformed(val snippet: String) : NazarickPushOutcome()
}

/**
 * Pure status/body -> outcome mapping: the US-001 check-mapping dialect extended with a body.
 * 401 is a **stop** signal, 5xx is "keep waiting" (a sick Tomb agent usually recovers inside the
 * poll interval), and 200 is the only code that carries an envelope.
 */
fun pushOutcome(httpCode: Int, body: String): NazarickPushOutcome = when (httpCode) {
    200 -> runCatching { parseNazarickPushMessages(body) }
        .fold(
            onSuccess = { NazarickPushOutcome.Reply(it) },
            onFailure = { NazarickPushOutcome.Malformed(body.take(200)) },
        )

    401 -> NazarickPushOutcome.Unauthorized
    404 -> NazarickPushOutcome.NotFound
    else -> NazarickPushOutcome.HttpError(httpCode)
}

/**
 * Pure push-URL builder (US-007): `{baseUrl}/api/console/push-messages?session_id=<namespaced id>`.
 *
 * The id is the same namespaced `app-<device>-<chat>` value the US-004 chat POST sends, because the
 * server store keys pending messages by the session that produced them. Percent-encoding is
 * HttpUrl's job, the same rule [stopUrl] relies on.
 *
 * Throws [IllegalArgumentException] for an unparseable baseUrl - [NazarickClient.getPushMessages]
 * checks with [nazarickUrl] first and returns [NazarickPushOutcome.NetworkError] without reaching
 * this function.
 */
fun pushMessagesUrl(baseUrl: String, sessionId: String): String {
    val base = nazarickUrl(baseUrl, NazarickClient.PUSH_PATH)
        ?: throw IllegalArgumentException("invalid nazarick baseUrl: $baseUrl")
    return base.newBuilder()
        .addQueryParameter("session_id", sessionId)
        .build()
        .toString()
}
