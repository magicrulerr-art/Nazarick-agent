package me.rerere.sdcpp

import java.io.ByteArrayInputStream
import java.util.zip.InflaterInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * [PngEncoder] gets checked against the container spec rather than against a picture: the
 * interesting failures are a wrong CRC, a missing per-scanline filter byte, or an IHDR that
 * disagrees with the pixel format — all invisible to "does it look right" and all fatal to a
 * viewer that is stricter than Android's.
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
        assertTrue(png.size > expected.size, "output is shorter than a PNG could be")
        assertEquals(
            expected.toList(),
            png.take(expected.size).toList(),
            "PNG signature missing — no decoder will touch this",
        )
    }

    @Test
    fun `IHDR carries the geometry and the RGBA colour type`() {
        val png = PngEncoder.encode(pixels, 2, 2, 4)
        val ihdr = chunk(png, "IHDR")
        assertEquals(13, ihdr.size, "IHDR payload must be 13 bytes")
        assertEquals(2, beInt(ihdr, 0), "width")
        assertEquals(2, beInt(ihdr, 4), "height")
        assertEquals(8, ihdr[8].toInt(), "bit depth")
        assertEquals(6, ihdr[9].toInt(), "colour type 6 = RGBA (2 would drop alpha)")
    }

    @Test
    fun `IDAT inflates back to the exact scanlines written`() {
        val png = PngEncoder.encode(pixels, 2, 2, 4)
        val raw = InflaterInputStream(ByteArrayInputStream(chunk(png, "IDAT"))).readBytes()
        // Two rows, each prefixed with filter byte 0, then 2 pixels x 4 channels.
        assertEquals(2 * (1 + 2 * 4), raw.size, "raw scanline stream is the wrong length")
        assertEquals(0, raw[0].toInt(), "row 1 filter byte")
        assertEquals(0, raw[9].toInt(), "row 2 filter byte")
        assertEquals(
            pixels.take(8).toList(),
            raw.drop(1).take(8).toList(),
            "row 1 pixels came back changed",
        )
        assertEquals(
            pixels.drop(8).toList(),
            raw.drop(10).take(8).toList(),
            "row 2 pixels came back changed",
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
            assertEquals(crc, computed, "CRC mismatch on $type")
        }
    }

    @Test
    fun `a raster whose length disagrees with its geometry is refused`() {
        // Half a buffer otherwise becomes a half picture, which reads as a broken model
        // rather than as a broken transfer.
        assertFailsWith<IllegalArgumentException> { PngEncoder.encode(pixels, 2, 3, 4) }
        assertFailsWith<IllegalArgumentException> { PngEncoder.encode(pixels, 2, 2, 2) }
    }

    @Test
    fun `an RGB raster is encoded with colour type 2`() {
        val rgb = byteArrayOf(-1, 0, 0, 0, -1, 0)
        val ihdr = chunk(PngEncoder.encode(rgb, 2, 1, 3), "IHDR")
        assertEquals(2, ihdr[9].toInt(), "colour type 2 = RGB when the engine reports 3 channels")
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
