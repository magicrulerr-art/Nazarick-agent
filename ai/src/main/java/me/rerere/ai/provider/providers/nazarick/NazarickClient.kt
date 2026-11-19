package me.rerere.ai.provider.providers.nazarick

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.common.http.await
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
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
 */
fun nazarickRequest(url: HttpUrl, token: String? = null, agentId: String? = null): Request =
    Request.Builder().url(url).get().apply {
        if (!token.isNullOrBlank()) header("Authorization", "Bearer $token")
        if (!agentId.isNullOrBlank()) header("X-Agent-Id", agentId)
    }.build()

/** Non-2xx answer from a nazarick endpoint that was expected to return data. */
class NazarickHttpException(val status: Int, val body: String) :
    Exception("nazarick request failed: HTTP $status (${body.take(200)})")
