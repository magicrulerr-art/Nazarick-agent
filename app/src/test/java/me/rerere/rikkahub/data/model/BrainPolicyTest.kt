package me.rerere.rikkahub.data.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/** US-008: brainPolicy defaults are the migration; values are wire-exact. */
class BrainPolicyTest {
    private val json = Json

    @Test
    fun legacyPayloadWithoutBrainPolicyDecodesToEdge() {
        val assistant = json.decodeFromString(Assistant.serializer(), """{"name":"x"}""")
        assertEquals(BrainPolicy.EDGE, assistant.brainPolicy)
    }

    @Test
    fun everyPolicyRoundTrips() {
        BrainPolicy.entries.forEach { policy ->
            val encoded = json.encodeToString(Assistant.serializer(), Assistant(name = "x", brainPolicy = policy))
            val decoded = json.decodeFromString(Assistant.serializer(), encoded)
            assertEquals(policy, decoded.brainPolicy)
        }
    }

    @Test
    fun wireValuesAreLowerCaseVerbatim() {
        assertTrue(json.encodeToString(BrainPolicy.serializer(), BrainPolicy.TOMB).contains("tomb"))
        assertTrue(json.encodeToString(BrainPolicy.serializer(), BrainPolicy.EDGE).contains("edge"))
        assertTrue(json.encodeToString(BrainPolicy.serializer(), BrainPolicy.AUTO).contains("auto"))
    }

    @Test
    fun defaultEdgeIsOmittedFromEncoding() {
        val encoded = json.encodeToString(Assistant.serializer(), Assistant(name = "x"))
        assertFalse(encoded.contains("brainPolicy"))
    }

    @Test
    fun unknownPolicyValueFailsLoud() {
        kotlin.test.assertFailsWith<SerializationException> {
            json.decodeFromString(Assistant.serializer(), """{"name":"x","brainPolicy":"cloud"}""")
        }
    }
]
