package me.rerere.ai.provider.providers.nazarick

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.common.http.await
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.IOException

/**
 * Connection-test client for a Nazarick-agent instance (US-001).
 *
 * Two independent steps, each mapped to a [NazarickCheckOutcome] so the UI can show a
 * per-step green/red instead of one undifferentiated failure:
 *  - [checkInstance]: GET {baseUrl}/api/version with **no auth** — 200 proves the phone
 *    can reach the instance at all.
 *  - [checkToken]:    GET {baseUrl}/api/auth/verify with `Authorization: Bearer <token>`
 *    — 200 proves the token, 401 says the token (and only the token) is wrong.
 *
 * The status-to-outcome mapping is pure ([nazarickInstanceOutcome] / [nazarickTokenOutcome])
 * and unit-tested without a socket; only the two suspend wrappers touch the network.
 */
class NazarickClient(private val client: OkHttpClient = OkHttpClient()) {

    suspend fun checkInstance(baseUrl: String, agentId: String? = null): NazarickCheckOutcome =
        get(nazarickUrl(baseUrl, VERSION_PATH), auth = null, agentId = agentId, mapper = ::nazarickInstanceOutcome)

    suspend fun checkToken(baseUrl: String, token: String, agentId: String? = null): NazarickCheckOutcome =
        get(nazarickUrl(baseUrl, VERIFY_PATH), auth = token, agentId = agentId, mapper = ::nazarickTokenOutcome)

    /**
     * `POST {baseUrl}/api/auth/login` with `{"username":…,"password":…,"expires_in":0}` (US-001
     * sign-in) — the exchange that mints the Bearer token every other call in this class rides,
     * so it is the ONE call sent WITHOUT an Authorization header. `expires_in: 0` is the server's
     * permanent-token option (qwenpaw `auth.py`: 0 = never expires, still revocable), sent as the
     * JSON **integer** the endpoint expects; the user never handles the token itself.
     *
     * Returns a [NazarickLoginOutcome] instead of throwing, the same contract as [stopChat]: the
     * caller is a provider-page button that must show a named reason (rejected / locked /
     * unreachable), never a stack trace.
     */
    suspend fun login(baseUrl: String, username: String, password: String): NazarickLoginOutcome {
        val url = nazarickUrl(baseUrl, LOGIN_PATH) ?: return NazarickLoginOutcome.Unreachable
        return withContext(Dispatchers.IO) {
            try {
                client.newCall(loginRequest(url, username, password)).await().use { response ->
                    loginOutcome(response.code, response.body?.string().orEmpty())
                }
            } catch (_: IOException) {
                NazarickLoginOutcome.Unreachable
            }
        }
    }

    /**
     * `GET {baseUrl}/api/agents` -> parsed roster (US-003). Rides the same central
     * [nazarickRequest] helper as every other call, so `X-Agent-Id` is attached whenever
     * an agent is selected. Throws [NazarickHttpException] on a non-2xx answer — an empty
     * roster and an unreachable instance must stay distinguishable — and
     * [IllegalArgumentException] for an unparseable baseUrl.
     */
    suspend fun listAgents(baseUrl: String, token: String? = null, agentId: String? = null): List<NazarickAgent> {
        val url = nazarickUrl(baseUrl, AGENTS_PATH)
            ?: throw IllegalArgumentException("invalid nazarick baseUrl: $baseUrl")
        return withContext(Dispatchers.IO) {
            client.newCall(nazarickRequest(url, token, agentId)).await().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) throw NazarickHttpException(response.code, body)
                parseNazarickAgents(body)
            }
        }
    }

    /**
     * `POST {baseUrl}/api/console/chat/stop?chat_id=<sessionId>&cancel_reason=USER` (US-006) —
     * asks the instance to stop the run the app is cancelling.
     *
     * Server contract (source-verified in the shipped qwenpaw `console.py`): `chat_id` is a
     * **query parameter** and there is **no JSON body** — HTTP needs a body to make the request a
     * POST, so it is empty. The server reads `chat_id` as a ChatSpec id first and falls back to a
     * `session_id` lookup for `channel=console`, which is exactly the namespaced
     * `app-<device>-<chat>` id the US-004 chat stream sends, so the app passes its own session id.
     * 200 means stopped, 404 means the server has no such run (already over, or never started).
     *
     * Returns a [NazarickStopOutcome] instead of throwing: the caller is a *cancelled* chat stream
     * firing this fire-and-forget under `NonCancellable`, and a failed stop follow-up must never
     * surface as a second exception on its way out.
     */
    suspend fun stopChat(
        baseUrl: String,
        token: String,
        agentId: String? = null,
        sessionId: String,
    ): NazarickStopOutcome {
        val url = nazarickUrl(baseUrl, STOP_PATH)
            ?.newBuilder()
            ?.addQueryParameter("chat_id", sessionId)
            ?.addQueryParameter("cancel_reason", CANCEL_REASON_USER)
            ?.build()
            ?: return NazarickStopOutcome.NetworkError   // no parseable host: nothing was called
        return withContext(Dispatchers.IO) {
            try {
                client.newCall(nazarickRequest(url, token, agentId, EMPTY_BODY)).await()
                    .use { response -> stopOutcome(response.code) }
            } catch (_: IOException) {
                NazarickStopOutcome.NetworkError
            }
        }
    }

    private suspend fun get(
        url: HttpUrl?,
        auth: String?,
        agentId: String?,
        mapper: (Int) -> NazarickCheckOutcome,
    ): NazarickCheckOutcome {
        if (url == null) return NazarickCheckOutcome.INVALID_URL
        return withContext(Dispatchers.IO) {
            val request = nazarickRequest(url, auth, agentId)
            try {
                client.newCall(request).await().use { response -> mapper(response.code) }
            } catch (_: IOException) {
                NazarickCheckOutcome.NETWORK_ERROR
            }
        }
    }

    companion object {
        const val VERSION_PATH = "/api/version"
        const val VERIFY_PATH = "/api/auth/verify"
        const val AGENTS_PATH = "/api/agents"

        /** US-004 chat endpoint: `POST {baseUrl}/api/console/chat`, SSE Responses-shaped body. */
        const val CHAT_PATH = "/api/console/chat"

        /** US-006 stop endpoint: `POST {baseUrl}/api/console/chat/stop?chat_id=…&cancel_reason=USER`. */
        const val STOP_PATH = "/api/console/chat/stop"
        /** US-001 sign-in endpoint: `POST {baseUrl}/api/auth/login`, no Authorization header. */
        const val LOGIN_PATH = "/api/auth/login"

        /** Server-side default for `cancel_reason`; sent explicitly so the audit line is unambiguous. */
        const val CANCEL_REASON_USER = "USER"

        /** Stop takes no JSON body; an empty body only exists to make the request a POST. */
        val EMPTY_BODY: RequestBody = ByteArray(0).toRequestBody(null)
    }
}

enum class NazarickCheckOutcome {
    /** The step passed. */
    OK,

    /** baseUrl was blank or not a parseable http(s) URL — no request was attempted. */
    INVALID_URL,

    /** DNS refused / connection dropped / request cancelled: nothing answered. */
    NETWORK_ERROR,

    /** 404 — reachable host, wrong path (or the endpoint is not exposed). */
    NOT_FOUND,

    /** 401 — the instance answered and rejected this token. */
    UNAUTHORIZED,

    /** 5xx — the instance is up but sick. */
    SERVER_ERROR,

    /** Any other status: answered, but not a shape we promised to interpret. */
    UNKNOWN,
}

/** Pure step-1 mapping: `/api/version` semantics. */
fun nazarickInstanceOutcome(statusCode: Int): NazarickCheckOutcome = when (statusCode) {
    200 -> NazarickCheckOutcome.OK
    404 -> NazarickCheckOutcome.NOT_FOUND
    in 500..599 -> NazarickCheckOutcome.SERVER_ERROR
    else -> NazarickCheckOutcome.UNKNOWN
}

/** Pure step-2 mapping: `/api/auth/verify` semantics (401 is a *successful* reach). */
fun nazarickTokenOutcome(statusCode: Int): NazarickCheckOutcome = when (statusCode) {
    200 -> NazarickCheckOutcome.OK
    401 -> NazarickCheckOutcome.UNAUTHORIZED
    404 -> NazarickCheckOutcome.NOT_FOUND
    in 500..599 -> NazarickCheckOutcome.SERVER_ERROR
    else -> NazarickCheckOutcome.UNKNOWN
}

/**
 * Join baseUrl + path defensively: trims whitespace/trailing slashes, accepts a
 * scheme-less host by defaulting to https, returns null for anything unparseable so
 * the caller reports INVALID_URL instead of throwing.
 */
fun nazarickUrl(baseUrl: String, path: String): HttpUrl? {
    val trimmed = baseUrl.trim().trimEnd('/')
    if (trimmed.isEmpty()) return null
    val withScheme = if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
        trimmed
    } else {
        "https://$trimmed"
    }
    return (withScheme + path).toHttpUrlOrNull()
}

/**
 * The ONE place a nazarick HTTP request is assembled (US-003): every request carries
 * `Authorization: Bearer <token>` when a token exists and `X-Agent-Id: <agentId>` when
 * an agent is selected. Null/blank values omit their header — an empty `X-Agent-Id`
 * would ask the server for "the agent named nothing" instead of its default.
 *
 * US-004 added [body]: pass a request body and the call becomes a POST with it, so the
 * chat generator gets its auth headers from the same assembly point as every GET
 * instead of duplicating the header rules. Null body keeps the GET behaviour every
 * existing caller (version / auth-verify / roster) depends on.
 */
fun nazarickRequest(
    url: HttpUrl,
    token: String? = null,
    agentId: String? = null,
    body: RequestBody? = null,
): Request =
    Request.Builder().url(url).apply {
        if (!token.isNullOrBlank()) header("Authorization", "Bearer $token")
        if (!agentId.isNullOrBlank()) header("X-Agent-Id", agentId)
        if (body != null) post(body) else get()
    }.build()

/** Non-2xx answer from a nazarick endpoint that was expected to return data. */
class NazarickHttpException(val status: Int, val body: String) :
    Exception("nazarick request failed: HTTP $status (${body.take(200)})")

/**
 * US-011r — the image on the outgoing turn could not be read from the device and turned into the
 * `input_image` part the tomb expects (missing file, unreadable path, unsupported scheme).
 *
 * Named `tomb_image_upload_failed` so the failure is identifiable from the message alone: it means
 * the picture never left the phone and the chat POST was **never attempted** — categorically
 * different from [NazarickHttpException], where an instance answered and rejected the request.
 * Rides the same surface as every other provider fault (the ai module has no AIException type; a
 * throw inside the [kotlinx.coroutines.flow.flow] body *is* the error channel, which
 * `GenerationHandler` already turns into the failed message + retry UI), and keeps [cause] so the
 * IOException's own reason survives.
 */
class NazarickImageUploadException(val imageUri: String, cause: Throwable?) :
    Exception("tomb_image_upload_failed: cannot read image for upload from $imageUri (${cause?.message})", cause)

/**
 * Result of [NazarickClient.stopChat] (US-006). Sealed and total: the cancelled chat stream
 * fires the stop fire-and-forget, so the four cases exist for callers that *want* to distinguish
 * "already over" (NotFound) from "instance unreachable" (NetworkError), not because the stop
 * path branches on them.
 */
sealed class NazarickStopOutcome {
    /** 200 — the instance acknowledged the stop for this chat/session. */
    object OK : NazarickStopOutcome()

    /** 404 — unknown chat_id *and* session_id: nothing runs under this id any more. */
    object NotFound : NazarickStopOutcome()

    /** Any other status (401, 5xx, …): the instance answered, just not with a stop. */
    data class HttpError(val code: Int) : NazarickStopOutcome()

    /** No answer at all — connection refused/dropped, or an unparseable baseUrl. */
    object NetworkError : NazarickStopOutcome()
}

/**
 * Pure stop-URL builder (US-006), the shape [NazarickClient.stopChat] sends:
 * `{baseUrl}/api/console/chat/stop?chat_id=<sessionId>&cancel_reason=USER`.
 *
 * Percent-encoding is HttpUrl's job and covers the query component fully — a session id holding
 * a space, a `%` or a `&` still addresses exactly one run (`%20`, `%25`, `%26`) instead of
 * splitting into extra parameters. `chat_id` stays a **query parameter** per the server contract;
 * there is no body variant to drift into.
 *
 * Throws [IllegalArgumentException] for an unparseable baseUrl — [NazarickClient.stopChat] checks
 * with [nazarickUrl] first and returns NetworkError without reaching this.
 */
fun stopUrl(baseUrl: String, sessionId: String): String {
    val base = nazarickUrl(baseUrl, NazarickClient.STOP_PATH)
        ?: throw IllegalArgumentException("invalid nazarick baseUrl: $baseUrl")
    return base.newBuilder()
        .addQueryParameter("chat_id", sessionId)
        .addQueryParameter("cancel_reason", NazarickClient.CANCEL_REASON_USER)
        .build()
        .toString()
}

/**
 * Pure HTTP-status -> [NazarickStopOutcome] mapping (US-006). 200 is the only success; 404 is a
 * *sane* answer (the run already ended between the user's tap and the POST), every other code is
 * carried with its number so a log or a snackbar can name it.
 */
fun stopOutcome(httpCode: Int): NazarickStopOutcome = when (httpCode) {
    200 -> NazarickStopOutcome.OK
    404 -> NazarickStopOutcome.NotFound
    else -> NazarickStopOutcome.HttpError(httpCode)
}

/**
 * Outcome of [NazarickClient.login] (US-001 sign-in). Sealed and total like [NazarickStopOutcome]:
 * every answer the login endpoint can give has a named case, so the provider page reports
 * *what happened* instead of "HTTP 423".
 */
sealed class NazarickLoginOutcome {
    /** 200 with a usable `token` — the permanent Bearer token to store in provider settings. */
    data class Success(val token: String) : NazarickLoginOutcome()

    /** 401/403 — the instance answered and rejected these credentials. Nothing was stored. */
    object CredentialsRejected : NazarickLoginOutcome()

    /** 423 — too many failed attempts; the account is temporarily locked. Wait, then retry. */
    object AccountLocked : NazarickLoginOutcome()

    /** 200 without a usable non-blank `token` field: the instance answered, but not in the shape we promised to read. */
    object MalformedResponse : NazarickLoginOutcome()

    /** Any other status: answered, but not a shape we promised to interpret. Code carried for the log. */
    data class ServerError(val code: Int) : NazarickLoginOutcome()

    /** No answer at all — connection refused/dropped (Tailscale down?), or an unparseable baseUrl. */
    object Unreachable : NazarickLoginOutcome()
}

/** The login body's media type; same value the chat stream uses, kept local to stay private. */
private const val LOGIN_JSON_MEDIA = "application/json"

/**
 * Pure request builder (US-001): `POST {baseUrl}/api/auth/login` carrying
 * `{"username":…,"password":…,"expires_in":0}`.
 *
 * `expires_in` is the JSON **integer** 0 — the server's permanent-token option — and tests pin
 * the literal so the body can never drift into `"0"` or an omitted field (an omitted field would
 * silently mint a *session* token the user then has to re-login for). NO Authorization header:
 * this request mints the token, it does not consume one.
 */
fun loginRequest(url: HttpUrl, username: String, password: String): Request {
    val body = buildJsonObject {
        put("username", username)
        put("password", password)
        put("expires_in", 0)
    }.toString().toRequestBody(LOGIN_JSON_MEDIA.toMediaType())
    return nazarickRequest(url, token = null, agentId = null, body = body)
}

/**
 * Pure `{"token":…}` reader (US-001). Any malformed or missing shape collapses to null — a
 * garbage 200 is a named [NazarickLoginOutcome.MalformedResponse], never a crash and never a
 * stored empty token.
 */
fun parseLoginToken(body: String): String? = try {
    body.let { Json.parseToJsonElement(it).jsonObject["token"]?.jsonPrimitive?.contentOrNull }
        ?.takeIf { it.isNotBlank() }
} catch (_: Exception) {
    null
}

/**
 * Pure HTTP-status + body -> [NazarickLoginOutcome] mapping (US-001), unit-tested without a
 * socket. 200 is the only success and only with a parseable non-blank token; 401 and 403 share
 * one name because from the app's side they mean the same thing (bad credentials); 423 is the
 * qwenpaw lockout and deserves its own user-facing sentence.
 */
fun loginOutcome(httpCode: Int, body: String): NazarickLoginOutcome = when (httpCode) {
    200 -> parseLoginToken(body)?.let { NazarickLoginOutcome.Success(it) }
        ?: NazarickLoginOutcome.MalformedResponse
    401, 403 -> NazarickLoginOutcome.CredentialsRejected
    423 -> NazarickLoginOutcome.AccountLocked
    else -> NazarickLoginOutcome.ServerError(httpCode)
}
