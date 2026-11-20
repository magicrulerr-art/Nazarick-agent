package me.rerere.rikkahub.data.model

import kotlinx.serialization.SerializationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
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
        assertThrows(SerializationException::class.java) {
            json.decodeFromString(Assistant.serializer(), """{"name":"x","brainPolicy":"cloud"}""")
        }
    }
]
