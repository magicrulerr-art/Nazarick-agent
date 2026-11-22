package me.rerere.ai.provider.providers.nazarick

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * US-001 sign-in acceptance leg: the pure pieces of [NazarickClient.login] tested on the plain
 * JVM — no OkHttp calls, no socket, no Robolectric, same dialect as [NazarickChatTest].
 *
 * The wire contract is pinned against the shipped qwenpaw source (mission-20261119-132526
 * OQ1/OQ2): `POST /api/auth/login` with `{"username","password","expires_in"}`, 200 returns
 * `{"token","username"}`; `expires_in: 0` (integer) mints a permanent token; 401 rejects;
 * 423 locks. The suspend wrapper adds only transport.
 */
class NazarickLoginTest {

    private fun bodyOf(request: okhttp3.Request): String {
        val buffer = Buffer()
        request.body!!.writeTo(buffer)
        return buffer.readUtf8()
    }

    // ---- request shape ----

    @Test
    fun `login body carries username password and integer zero expires_in`() {
        val request = loginRequest("http://100.76.205.66:8088".toHttpUrl().newBuilder().encodedPath(NazarickClient.LOGIN_PATH).build(), "demo-user", "hunter2")
        assertEquals("POST", request.method)
        val raw = bodyOf(request)
        val obj = Json.parseToJsonElement(raw).jsonObject
        assertEquals("demo-user", obj["username"]?.jsonPrimitive?.content)
        assertEquals("hunter2", obj["password"]?.jsonPrimitive?.content)
        // Permanent token: the literal JSON integer 0 — NOT "0", NOT a big number, NOT omitted.
        assertTrue("expires_in must serialize as integer 0, body was: $raw", raw.contains("\"expires_in\":0"))
        assertTrue(obj["expires_in"]?.jsonPrimitive?.isString == false)
    }

    @Test
    fun `login request carries no Authorization header`() {
        val url = "https://instance.test".toHttpUrl().newBuilder().encodedPath(NazarickClient.LOGIN_PATH).build()
        val request = loginRequest(url, "u", "p")
        // This call MINTS the token; it must never require one.
        assertNull(request.header("Authorization"))
        assertNull(request.header("X-Agent-Id"))
        assertEquals("/api/auth/login", url.encodedPath)
    }

    // ---- token parsing ----

    @Test
    fun `parseLoginToken reads the token field`() {
        assertEquals("secret-token", parseLoginToken("{\"token\":\"secret-token\",\"username\":\"demo-user\"}"))
    }

    @Test
    fun `parseLoginToken collapses garbage to null`() {
        assertNull(parseLoginToken("{}"))
        assertNull(parseLoginToken("{\"token\":\"\"}"))
        assertNull(parseLoginToken("{\"token\":\"   \"}"))
        assertNull(parseLoginToken("not json at all"))
        assertNull(parseLoginToken(""))
    }

    // ---- status mapping ----

    @Test
    fun `200 with token is success`() {
        val outcome = loginOutcome(200, "{\"token\":\"t0k\",\"username\":\"u\"}")
        assertEquals(NazarickLoginOutcome.Success("t0k"), outcome)
    }

    @Test
    fun `200 without usable token is a named malformed response, not a crash`() {
        assertEquals(NazarickLoginOutcome.MalformedResponse, loginOutcome(200, "{\"detail\":\"weird\"}"))
        assertEquals(NazarickLoginOutcome.MalformedResponse, loginOutcome(200, ""))
    }

    @Test
    fun `401 and 403 both name credentials rejected`() {
        assertEquals(NazarickLoginOutcome.CredentialsRejected, loginOutcome(401, "{\"detail\":\"Invalid username or password\"}"))
        assertEquals(NazarickLoginOutcome.CredentialsRejected, loginOutcome(403, ""))
    }

    @Test
    fun `423 names the lockout`() {
        assertEquals(NazarickLoginOutcome.AccountLocked, loginOutcome(423, "{\"detail\":\"Too many failed login attempts\"}"))
    }

    @Test
    fun `any other status is a server error carrying its code`() {
        assertEquals(NazarickLoginOutcome.ServerError(500), loginOutcome(500, ""))
        assertEquals(NazarickLoginOutcome.ServerError(404), loginOutcome(404, ""))
    }
}
