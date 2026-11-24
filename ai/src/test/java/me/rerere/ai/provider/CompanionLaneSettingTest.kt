package me.rerere.ai.provider

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * US-001: companion lane fields on the Nazarick provider setting.
 *
 * Verifies that the three new fields (companion_enabled, companion_agent_id,
 * companion_device_id) round-trip through JSON serialization, and that a
 * legacy JSON missing those fields still deserializes with the correct defaults
 * (false / "default" / "").
 */
class CompanionLaneSettingTest {

    private val json = Json { ignoreUnknownKeys = true }

    private val stableNazarickId = Uuid.parse("11111111-aaaa-bbbb-cccc-000000000006")

    @Test
    fun `nazarick setting with companion lane fields serializes and deserializes correctly`() {
        val setting = ProviderSetting.Nazarick(
            id = stableNazarickId,
            enabled = true,
            name = "Nazarick Test",
            models = emptyList(),
            balanceOption = BalanceOption(),
            customHeaders = emptyList(),
            builtIn = true,
            description = {},
            shortDescription = {},
            baseUrl = "https://nazarick.example.com",
            token = "secret-token",
            agentId = "agent-42",
            companion_enabled = true,
            companion_agent_id = "companion-agent-1",
            companion_device_id = "z7",
        )

        // Serialize to JSON
        val encoded = json.encodeToString(setting)
        val jsonObject = json.parseToJsonElement(encoded).jsonObject

        // Assert the new fields are present in the JSON output
        assertEquals("companion_enabled must be true in JSON", true, jsonObject["companion_enabled"]?.jsonPrimitive?.booleanOrNull)
        assertEquals("companion_agent_id must be companion-agent-1 in JSON", "companion-agent-1", jsonObject["companion_agent_id"]?.jsonPrimitive?.contentOrNull)
        assertEquals("companion_device_id must be z7 in JSON", "z7", jsonObject["companion_device_id"]?.jsonPrimitive?.contentOrNull)

        // Deserialize back and assert round-trip
        val restored = json.decodeFromString<ProviderSetting.Nazarick>(encoded)
        assertEquals("companion_enabled round-trips", true, restored.companion_enabled)
        assertEquals("companion_agent_id round-trips", "companion-agent-1", restored.companion_agent_id)
        assertEquals("companion_device_id round-trips", "z7", restored.companion_device_id)

        // Also assert the original fields still work
        assertEquals("baseUrl round-trips", "https://nazarick.example.com", restored.baseUrl)
        assertEquals("token round-trips", "secret-token", restored.token)
        assertEquals("agentId round-trips", "agent-42", restored.agentId)
    }

    @Test
    fun `legacy nazarick JSON without companion fields deserializes with defaults`() {
        // A JSON payload that could have been stored before US-001 added the fields
        val legacyJson = """
            {
                "type": "nazarick",
                "id": "11111111-aaaa-bbbb-cccc-000000000006",
                "enabled": true,
                "name": "Nazarick",
                "models": [],
                "balanceOption": { "enabled": false, "apiPath": "/credits", "resultPath": "data.total_usage" },
                "customHeaders": [],
                "builtIn": true,
                "baseUrl": "https://legacy.example.com",
                "token": "old-token",
                "agentId": "legacy-agent"
            }
        """.trimIndent()

        val restored = json.decodeFromString<ProviderSetting.Nazarick>(legacyJson)

        // The three new fields should take their default values
        assertFalse("companion_enabled defaults to false", restored.companion_enabled)
        assertEquals("companion_agent_id defaults to 'default'", "default", restored.companion_agent_id)
        assertEquals("companion_device_id defaults to empty string", "", restored.companion_device_id)

        // Original fields should still parse correctly
        assertEquals("baseUrl from legacy JSON", "https://legacy.example.com", restored.baseUrl)
        assertEquals("token from legacy JSON", "old-token", restored.token)
        assertEquals("agentId from legacy JSON", "legacy-agent", restored.agentId)
    }

    @Test
    fun `nazarick setting with defaults has companion fields off by default`() {
        val setting = ProviderSetting.Nazarick(
            id = stableNazarickId,
            enabled = true,
            name = "Nazarick Defaults",
            models = emptyList(),
            balanceOption = BalanceOption(),
            customHeaders = emptyList(),
            builtIn = true,
            description = {},
            shortDescription = {},
            baseUrl = "https://default.example.com",
            token = "default-token",
            agentId = "default-agent",
            // companion fields intentionally omitted - should use defaults
        )

        val encoded = json.encodeToString(setting)
        val jsonObject = json.parseToJsonElement(encoded).jsonObject

        // encodeDefaults=false (repo dialect): defaults are ABSENT on the wire and
        // re-applied on decode - assert that contract instead of explicit serialization.
        assertEquals("companion_enabled omitted when default", null, jsonObject["companion_enabled"])

        val restored = json.decodeFromString<ProviderSetting.Nazarick>(encoded)
        assertFalse("companion_enabled default false", restored.companion_enabled)
        assertEquals("companion_agent_id default 'default'", "default", restored.companion_agent_id)
        assertEquals("companion_device_id default empty", "", restored.companion_device_id)
    }
}