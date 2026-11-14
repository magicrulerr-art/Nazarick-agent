package me.rerere.rikkahub.runtime.tools

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the shape from the z7 Jensen battery (AURA-021 US-012, 2026-11-14): search_web
 * calls that carried no usable arguments must be rejected with the missing key named,
 * while a well-formed call passes the gate untouched.
 */
class RequiredArgsGateTest {

    private val searchSchema = Json.parseToJsonElement(
        """{"type":"object","properties":{"query":{"type":"string"},"num_results":{"type":"integer"}},"required":["query"]}"""
    ).jsonObject

    @Test
    fun `requiredNames reads the schema required list`() {
        assertEquals(listOf("query"), RequiredArgsGate.requiredNames(searchSchema))
        assertEquals(emptyList<String>(), RequiredArgsGate.requiredNames(null))
        assertEquals(emptyList<String>(), RequiredArgsGate.requiredNames("{}".let { Json.parseToJsonElement(it).jsonObject }))
    }

    @Test
    fun `empty object is missing every required name`() {
        val args = Json.parseToJsonElement("{}")
        assertEquals(listOf("query"), RequiredArgsGate.missing(listOf("query"), args))
    }

    @Test
    fun `blank and null values count as missing`() {
        val blank = Json.parseToJsonElement("""{"query":""}""")
        assertEquals(listOf("query"), RequiredArgsGate.missing(listOf("query"), blank))
        val nul = Json.parseToJsonElement("""{"query":null}""")
        assertEquals(listOf("query"), RequiredArgsGate.missing(listOf("query"), nul))
    }

    @Test
    fun `non-object args are missing everything and well-formed args nothing`() {
        val notObject = Json.parseToJsonElement("""["query"]""")
        assertEquals(listOf("a", "b"), RequiredArgsGate.missing(listOf("a", "b"), notObject))
        val good = Json.parseToJsonElement("""{"query":"jensen huang nvidia"}""")
        assertTrue(RequiredArgsGate.missing(listOf("query"), good).isEmpty())
    }
}
