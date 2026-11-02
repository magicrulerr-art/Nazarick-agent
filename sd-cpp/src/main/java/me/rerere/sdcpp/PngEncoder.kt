package me.rerere.sdcpp

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater

/**
 * RGBA bytes out, PNG bytes out. No `android.graphics.Bitmap` anywhere.
 *
 * The first version of this path built a Bitmap and asked it to compress, which failed to
 * compile in this module for reasons the classpath owns — and was wrong besides: [SdRaster]
 * carries a raw `ByteArray`, and `Bitmap.createBitmap` has no ByteArray overload, so the pixel
 * order was a matter of hope. Writing the container by hand makes the byte order a stated fact
 * instead, and keeps the whole encode on a plain JVM, which is what lets it be unit-tested
 * without a device (see PngEncoderTest).
 *
 * PNG is small enough to write directly: a signature, IHDR, one IDAT (zlib over filter-0
 * scanlines) and IEND. Filter 0 ("None") is the honest choice here — the engine's output is
 * noisy detail, where the predictive filters mostly cost CPU without shrinking much.
 */
internal object PngEncoder {

    private val SIGNATURE = byteArrayOf(
        -119, 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(),
        13, 10, 26, 10,
    )

    /**
     * @param channel 4 (RGBA) or 3 (RGB) — whatever the engine reported, never assumed.
     * @throws IllegalArgumentException when the buffer does not match width x height x channel,
     *   which is the one engine contract worth failing loudly on: a truncated raster otherwise
     *   becomes a half-image PNG that looks like a model bug.
     */
    fun encode(pixels: ByteArray, width: Int, height: Int, channel: Int): ByteArray {
        require(width > 0 && height > 0) { "degenerate image ${width}x$height" }
        require(channel == 4 || channel == 3) { "unsupported channel count $channel" }
        val expected = width * height * channel
        require(pixels.size == expected) { "expected $expected pixel bytes, got ${pixels.size}" }

        val out = ByteArrayOutputStream(expected / 3 + 1024)
        val data = DataOutputStream(out)
        data.write(SIGNATURE)
        writeChunk(data, "IHDR", build { ihdr ->
            ihdr.writeInt(width)
            ihdr.writeInt(height)
            ihdr.writeByte(8)                                  // bit depth
            ihdr.writeByte(if (channel == 4) 6 else 2)         // 6 = RGBA, 2 = RGB
            ihdr.writeByte(0)                                  // deflate
            ihdr.writeByte(0)                                  // filter methods
            ihdr.writeByte(0)                                  // progressive off
        })

        // Every scanline is prefixed with its filter byte; the rest is a straight copy.
        val raw = ByteArrayOutputStream(expected + height)
        for (row in 0 until height) {
            raw.write(0)                                       // filter: None
            raw.write(pixels, row * expected / height, width * channel)
        }
        writeChunk(data, "IDAT", deflate(raw.toByteArray()))
        writeChunk(data, "IEND", ByteArray(0))
        return out.toByteArray()
    }

    private inline fun build(block: (DataOutputStream) -> Unit): ByteArray {
        val buf = ByteArrayOutputStream()
        block(DataOutputStream(buf))
        return buf.toByteArray()
    }

    private fun deflate(bytes: ByteArray): ByteArray {
        val deflater = Deflater()
        try {
            deflater.setInput(bytes)
            deflater.finish()
            val out = ByteArrayOutputStream(bytes.size / 2 + 64)
            val scratch = ByteArray(32 * 1024)
            while (!deflater.finished()) {
                val n = deflater.deflate(scratch)
                if (n > 0) out.write(scratch, 0, n)
            }
            return out.toByteArray()
        } finally {
            deflater.end()
        }
    }

    /** Length, type, data, then CRC over type+data — the field that most hand-rolled encoders get wrong. */
    private fun writeChunk(out: DataOutputStream, type: String, payload: ByteArray) {
        val typeBytes = type.toByteArray(Charsets.ISO_8859_1)
        out.writeInt(payload.size)
        out.write(typeBytes)
        out.write(payload)
        val crc = CRC32()
        crc.update(typeBytes)
        crc.update(payload)
        out.writeInt(crc.value.toInt())
    }
}
