package me.rerere.rikkahub.data.ai

import java.net.HttpURLConnection
import java.net.URL

/**
 * Real reachability probe for the enabled Nazarick instance (Companion-Edge V2 US-010r).
 *
 * Pure JVM - [java.net.HttpURLConnection], no Android deps, no coroutine, no DI - so the offline
 * path can be exercised by a plain unit test instead of being asserted from an emulator. The probe
 * asks the Tomb's cheapest authenticated-status endpoint and accepts 200 only: a 401/404/5xx means
 * something answered but not *our* Tomb, which is not "reachable" for routing purposes.
 */
object TombProbe {

    private const val STATUS_PATH = "/api/auth/status"

    /**
     * Returns true only when `GET [baseUrl]/api/auth/status` answers 200 within [timeoutMs].
     *
     * Any failure - refused connection, DNS miss, TLS error, timeout, non-200 - returns false.
     * Never throws, so callers can treat reachability as a plain fact. Both the connect and the
     * read timeouts are capped at [timeoutMs], so a dead host fails fast rather than hanging on
     * the OS default.
     */
    fun probe(baseUrl: String, timeoutMs: Int = 1500): Boolean {
        val url = runCatching { URL(baseUrl.trimEnd('/') + STATUS_PATH) }.getOrNull() ?: return false
        var conn: HttpURLConnection? = null
        return try {
            conn = (url.openConnection() as? HttpURLConnection)?.apply {
                requestMethod = "GET"
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                instanceFollowRedirects = false
                setRequestProperty("Accept", "application/json")
            } ?: return false
            conn.responseCode == 200
        } catch (_: Exception) {
            false
        } finally {
            runCatching { conn?.disconnect() }
        }
    }
}
