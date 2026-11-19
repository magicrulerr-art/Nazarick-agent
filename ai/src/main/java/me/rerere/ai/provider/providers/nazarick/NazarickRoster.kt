package me.rerere.ai.provider.providers.nazarick

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * One entry of a Nazarick instance's agent roster (`GET /api/agents`, US-003).
 *
 * Field names match the server envelope verbatim (`id`, `name`, `description`,
 * `enabled`); everything else the server sends (workspace_dir, backend, active_model,
 * ...) is intentionally dropped via `ignoreUnknownKeys` — the app selects agents by
 * id/name only, and pinning extra fields would turn server additions into crashes.
 */
@Serializable
data class NazarickAgent(
    val id: String,
    val name: String,
    val description: String = "",
    val enabled: Boolean = false,
)

@Serializable
private data class NazarickAgentsResponse(
    val agents: List<NazarickAgent> = emptyList(),
)

private val rosterJson = Json { ignoreUnknownKeys = true }

/**
 * Pure roster parser: envelope object with an `agents` array -> [NazarickAgent] list.
 * Unit-tested against `ai/src/test/resources/nazarick/agents_fixture.json`, a capture
 * taken live from `http://127.0.0.1:8088/api/agents` on 2026-11-19.
 */
fun parseNazarickAgents(body: String): List<NazarickAgent> =
    rosterJson.decodeFromString(NazarickAgentsResponse.serializer(), body).agents
