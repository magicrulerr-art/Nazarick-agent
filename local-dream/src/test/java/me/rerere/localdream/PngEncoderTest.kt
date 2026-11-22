package me.rerere.localdream

import java.io.ByteArrayInputStream
import java.util.zip.InflaterInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [PngEncoder] gets checked against the container spec rather than against a picture: the
 * interesting failures are a wrong CRC, a missing per-scanline filter byte, or an IHDR that
 * disagrees with the pixel format — all invisible to "does it look right" and all fatal to a
 * viewer that is stricter than Android's.
 *
 * Written against JUnit4 like every other module's suite: the house style costs no dependency
 * at all. This suite came over from :sd-cpp in v0.6 with the encoder itself, keeping the guard
 * on the container the QNN engine's base64 images are built with.
 */
class PngEncoderTest {

    /** A 2x2 image: red, green / blue, white. Bytes are RGBA in the order the engine writes them. */
    private val pixels = byteArrayOf(
        -1, 0, 0, -1, 0, -1, 0, -1,
        0, 0, -1, -1, -1, -1, -1, -1,
    )

    @Test
    fun `output starts with the PNG signature`() {
        val png = PngEncoder.encode(pixels, 2, 2, 4)
        val expected = byteArrayOf(-119, 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10)
        assertTrue("output is shorter than a PNG could be", png.size > expected.size)
        assertEquals(
            "PNG signature missing — no decoder will touch this",
            expected.toList(),
            png.take(expected.size).toList(),
        )
    }

    @Test
    fun `IHDR carries the geometry and the RGBA colour type`() {
        val png = PngEncoder.encode(pixels, 2, 2, 4)
        val ihdr = chunk(png, "IHDR")
        assertEquals("IHDR payload must be 13 bytes", 13, ihdr.size)
        assertEquals("width", 2, beInt(ihdr, 0))
        assertEquals("height", 2, beInt(ihdr, 4))
        assertEquals("bit depth", 8, ihdr[8].toInt())
        assertEquals("colour type 6 = RGBA (2 would drop alpha)", 6, ihdr[9].toInt())
    }

    @Test
    fun `IDAT inflates back to the exact scanlines written`() {
        val png = PngEncoder.encode(pixels, 2, 2, 4)
        val raw = InflaterInputStream(ByteArrayInputStream(chunk(png, "IDAT"))).readBytes()
        // Two rows, each prefixed with filter byte 0, then 2 pixels x 4 channels.
        assertEquals("raw scanline stream is the wrong length", 2 * (1 + 2 * 4), raw.size)
        assertEquals("row 1 filter byte", 0, raw[0].toInt())
        assertEquals("row 2 filter byte", 0, raw[9].toInt())
        assertEquals(
            "row 1 pixels came back changed",
            pixels.take(8).toList(),
            raw.drop(1).take(8).toList(),
        )
        assertEquals(
            "row 2 pixels came back changed",
            pixels.drop(8).toList(),
            raw.drop(10).take(8).toList(),
        )
    }

    @Test
    fun `every chunk CRC covers type plus data`() {
        val png = PngEncoder.encode(pixels, 2, 2, 4)
        forEachChunk(png) { type, data, crc ->
            val computed = java.util.zip.CRC32().apply {
                update(type.toByteArray(Charsets.ISO_8859_1))
                update(data)
            }.value.toInt()
            assertEquals("CRC mismatch on $type", crc, computed)
        }
    }

    @Test
    fun `a raster whose length disagrees with its geometry is refused`() {
        // Half a buffer otherwise becomes a half picture, which reads as a broken model
        // rather than as a broken transfer.
        assertThrows(IllegalArgumentException::class.java) { PngEncoder.encode(pixels, 2, 3, 4) }
        assertThrows(IllegalArgumentException::class.java) { PngEncoder.encode(pixels, 2, 2, 2) }
    }

    @Test
    fun `an RGB raster is encoded with colour type 2`() {
        val rgb = byteArrayOf(-1, 0, 0, 0, -1, 0)
        val ihdr = chunk(PngEncoder.encode(rgb, 2, 1, 3), "IHDR")
        assertEquals("colour type 2 = RGB when the engine reports 3 channels", 2, ihdr[9].toInt())
    }

    // --- minimal chunk reader (the encoder's mirror image, deliberately written differently) ---

    private fun chunk(png: ByteArray, type: String): ByteArray {
        var result: ByteArray? = null
        forEachChunk(png) { t, data, _ -> if (t == type) result = data }
        return checkNotNull(result) { "no $type chunk in output" }
    }

    private inline fun forEachChunk(png: ByteArray, onChunk: (type: String, data: ByteArray, crc: Int) -> Unit) {
        var i = 8 // past the signature
        while (i + 8 <= png.size) {
            val length = beInt(png, i)
            val type = String(png, i + 4, 4, Charsets.ISO_8859_1)
            val data = png.copyOfRange(i + 8, i + 8 + length)
            val crc = beInt(png, i + 8 + length)
            onChunk(type, data, crc)
            i += 12 + length
        }
    }

    private fun beInt(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF) shl 24) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
            (bytes[offset + 3].toInt() and 0xFF)
}
