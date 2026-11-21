package me.rerere.ai.provider.providers.nazarick

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Covers the pure half of [NazarickClient]: the HTTP-status -> [NazarickCheckOutcome]
 * mapping for both Test-Connection steps and the baseUrl joiner. The two steps must map
 * differently on purpose — a 401 from /api/auth/verify is a *valid reach* with an invalid
 * token, while the same code anywhere else is noise — so each mapping is asserted alone.
 */
class NazarickCheckMappingTest {

    @Test
    fun `version 200 is instance OK`() {
        assertEquals(NazarickCheckOutcome.OK, nazarickInstanceOutcome(200))
    }

    @Test
    fun `version 404 is NOT_FOUND not OK`() {
        assertEquals(NazarickCheckOutcome.NOT_FOUND, nazarickInstanceOutcome(404))
    }

    @Test
    fun `version 5xx is SERVER_ERROR`() {
        assertEquals(NazarickCheckOutcome.SERVER_ERROR, nazarickInstanceOutcome(503))
    }

    @Test
    fun `version unmapped status is UNKNOWN`() {
        assertEquals(NazarickCheckOutcome.UNKNOWN, nazarickInstanceOutcome(418))
    }

    @Test
    fun `verify 200 is token OK`() {
        assertEquals(NazarickCheckOutcome.OK, nazarickTokenOutcome(200))
    }

    @Test
    fun `verify 401 is UNAUTHORIZED and distinct from instance failure`() {
        assertEquals(NazarickCheckOutcome.UNAUTHORIZED, nazarickTokenOutcome(401))
        assertEquals(NazarickCheckOutcome.UNKNOWN, nazarickInstanceOutcome(401))
    }

    @Test
    fun `verify 5xx is SERVER_ERROR`() {
        assertEquals(NazarickCheckOutcome.SERVER_ERROR, nazarickTokenOutcome(500))
    }

    @Test
    fun `blank base url yields no request url`() {
        assertNull(nazarickUrl("", NazarickClient.VERSION_PATH))
        assertNull(nazarickUrl("   ", NazarickClient.VERSION_PATH))
    }

    @Test
    fun `base url joins paths and tolerates trailing slash`() {
        assertEquals(
            "https://instance.local/api/version",
            nazarickUrl("https://instance.local/", NazarickClient.VERSION_PATH).toString(),
        )
        assertEquals(
            "http://192.168.1.7:3131/api/auth/verify",
            nazarickUrl("http://192.168.1.7:3131", NazarickClient.VERIFY_PATH).toString(),
        )
    }

    @Test
    fun `scheme-less base url defaults to https`() {
        assertEquals(
            "https://aura.example/api/version",
            nazarickUrl("aura.example", NazarickClient.VERSION_PATH).toString(),
        )
    }
}
