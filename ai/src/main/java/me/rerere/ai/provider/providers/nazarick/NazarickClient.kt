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

    suspend fun checkInstance(baseUrl: String): NazarickCheckOutcome =
        get(nazarickUrl(baseUrl, VERSION_PATH), auth = null, mapper = ::nazarickInstanceOutcome)

    suspend fun checkToken(baseUrl: String, token: String): NazarickCheckOutcome =
        get(nazarickUrl(baseUrl, VERIFY_PATH), auth = token, mapper = ::nazarickTokenOutcome)

    private suspend fun get(
        url: HttpUrl?,
        auth: String?,
        mapper: (Int) -> NazarickCheckOutcome,
    ): NazarickCheckOutcome {
        if (url == null) return NazarickCheckOutcome.INVALID_URL
        return withContext(Dispatchers.IO) {
            val request = Request.Builder().url(url).get().apply {
                if (auth != null) header("Authorization", "Bearer $auth")
            }.build()
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
