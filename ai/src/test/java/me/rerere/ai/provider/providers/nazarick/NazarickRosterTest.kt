package me.rerere.ai.provider.providers.nazarick

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * US-003 roster leg: parses the live-captured fixture
 * (`src/test/resources/nazarick/agents_fixture.json`, taken from
 * `http://127.0.0.1:8088/api/agents` on 2026-11-19) and pins the central
 * [nazarickRequest] header helper that puts Authorization + X-Agent-Id on every call.
 */
class NazarickRosterTest {

    private fun fixtureBody(): String {
        val stream = javaClass.getResourceAsStream("/nazarick/agents_fixture.json")
        assertNotNull("agents_fixture.json missing from test resources", stream)
        return stream!!.use { it.readBytes().toString(Charsets.UTF_8) }
    }

    @Test
    fun `live fixture parses to non-empty roster`() {
        val agents = parseNazarickAgents(fixtureBody())
        assertTrue("fixture must contain at least one agent", agents.isNotEmpty())
    }

    @Test
    fun `live fixture agents carry id name description enabled`() {
        val agents = parseNazarickAgents(fixtureBody())
        for (agent in agents) {
            assertTrue("agent id must not be blank: $agent", agent.id.isNotBlank())
            assertTrue("agent name must not be blank: $agent", agent.name.isNotBlank())
        }
        assertTrue("server always ships at least one enabled agent", agents.any { it.enabled })
    }

    @Test
    fun `live fixture contains the default agent`() {
        val ids = parseNazarickAgents(fixtureBody()).map { it.id }
        assertTrue("default", ids.contains("default"))
    }

    @Test
    fun `unknown fields are ignored and optional fields default`() {
        val body = """
            {"agents":[{"id":"a","name":"A","workspace_dir":"C:\\tmp","enabled":true,
                        "backend_status":"available","nested":{"x":[1,2]}},
                       {"id":"b","name":"B"}],
             "total":2}
        """.trimIndent()
        val agents = parseNazarickAgents(body)
        assertEquals(
            listOf(
                NazarickAgent("a", "A", "", true),
                NazarickAgent("b", "B", "", false),
            ),
            agents,
        )
    }

    @Test
    fun `empty agents array parses to empty list`() {
        assertEquals(emptyList<NazarickAgent>(), parseNazarickAgents("""{"agents":[]}"""))
    }

    @Test
    fun `request carries bearer token and agent id headers`() {
        val request = nazarickRequest(
            "http://127.0.0.1:8088/api/agents".toHttpUrl(),
            token = "tok-123",
            agentId = "aura",
        )
        assertEquals("Bearer tok-123", request.header("Authorization"))
        assertEquals("aura", request.header("X-Agent-Id"))
        assertEquals("GET", request.method)
    }

    @Test
    fun `absent token or agent id omits the header instead of sending blank`() {
        val request = nazarickRequest("http://127.0.0.1:8088/api/version".toHttpUrl())
        assertNull(request.header("Authorization"))
        assertNull(request.header("X-Agent-Id"))

        val blank = nazarickRequest(
            "http://127.0.0.1:8088/api/agents".toHttpUrl(),
            token = "  ",
            agentId = "",
        )
        assertNull(blank.header("Authorization"))
        assertNull(blank.header("X-Agent-Id"))
    }

    @Test
    fun `agents path constant matches fixture endpoint`() {
        assertFalse(NazarickClient.AGENTS_PATH.isBlank())
        assertEquals("/api/agents", NazarickClient.AGENTS_PATH)
    }
}
