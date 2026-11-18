package io.github.davidvornholt.glimpse.shared.image

import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class MetadataStripperTest {
    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    private fun ascii(text: String) = text.toByteArray(Charsets.US_ASCII)

    private fun join(vararg parts: ByteArray): ByteArray = ByteArrayOutputStream().apply { parts.forEach(::write) }.toByteArray()

    private fun segment(
        marker: Int,
        payload: ByteArray,
    ) = join(bytes(0xFF, marker, (payload.size + 2) shr 8, (payload.size + 2) and 0xFF), payload)

    private val soi = bytes(0xFF, 0xD8)
    private val eoi = bytes(0xFF, 0xD9)
    private val jfif = segment(0xE0, join(ascii("JFIF"), bytes(0, 1, 2, 0, 0, 1, 0, 1, 0, 0)))
    private val exif = segment(0xE1, join(ascii("Exif"), bytes(0, 0), ascii("GPS 48.7758 N 9.1829 E")))
    private val xmp = segment(0xE1, ascii("http://ns.adobe.com/xap/1.0/\u0000<x:xmpmeta/>"))
    private val icc = segment(0xE2, join(ascii("ICC_PROFILE"), bytes(0, 1, 1), ascii("profile")))
    private val mpf = segment(0xE2, join(ascii("MPF"), bytes(0), ascii("second image at 0x1234")))
    private val comment = segment(0xFE, ascii("Shot on a phone"))
    private val adobe = segment(0xEE, join(ascii("Adobe"), bytes(0, 100, 0, 0, 0, 0, 1)))
    private val quantization = segment(0xDB, ByteArray(65) { it.toByte() })
    private val scanHeader = segment(0xDA, bytes(1, 1, 0, 0, 63, 0))

    // Image data with an escaped 0xFF and a restart marker inside, as real scans have.
    private val scanData = bytes(0x12, 0xFF, 0x00, 0x34, 0xFF, 0xD0, 0x56, 0x78)
    private val motionVideo = ascii("ftypmp42 the video of a motion photo")

    @Test
    fun `removes EXIF, XMP, comments and what follows the image`() {
        val jpeg = join(soi, jfif, exif, xmp, icc, mpf, comment, adobe, quantization, scanHeader, scanData, eoi, motionVideo)

        val stripped = MetadataStripper.stripJpeg(jpeg)

        assertContentEquals(join(soi, jfif, icc, adobe, quantization, scanHeader, scanData, eoi), stripped)
    }

    @Test
    fun `keeps the orientation so the photo stays upright`() {
        val jpeg = join(soi, jfif, exif, quantization, scanHeader, scanData, eoi)

        val stripped = MetadataStripper.stripJpeg(jpeg, orientation = 6)

        val orientationOnly =
            segment(
                0xE1,
                join(
                    ascii("Exif"),
                    bytes(0, 0, 0x4D, 0x4D, 0, 0x2A, 0, 0, 0, 8, 0, 1, 0x01, 0x12, 0, 3, 0, 0, 0, 1, 0, 6, 0, 0, 0, 0, 0, 0),
                ),
            )
        assertContentEquals(join(soi, jfif, orientationOnly, quantization, scanHeader, scanData, eoi), stripped)
    }

    @Test
    fun `refuses what isn't a whole JPEG`() {
        assertFailsWith<IllegalArgumentException> { MetadataStripper.stripJpeg(ascii("GIF89a")) }
        assertFailsWith<IllegalArgumentException> { MetadataStripper.stripJpeg(join(soi, jfif, quantization)) }
        assertFailsWith<IllegalArgumentException> { MetadataStripper.stripJpeg(join(soi, jfif).copyOf(10)) }
        assertFailsWith<IllegalArgumentException> { MetadataStripper.stripJpeg(join(soi, jfif, eoi), orientation = 9) }
    }

    private val pngSignature = bytes(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

    private fun chunk(
        type: String,
        data: ByteArray,
    ): ByteArray {
        val crc = CRC32().apply { update(ascii(type) + data) }.value
        val length = data.size
        return join(
            bytes(length ushr 24, (length shr 16) and 0xFF, (length shr 8) and 0xFF, length and 0xFF),
            ascii(type),
            data,
            bytes((crc ushr 24).toInt() and 0xFF, (crc ushr 16).toInt() and 0xFF, (crc ushr 8).toInt() and 0xFF, crc.toInt() and 0xFF),
        )
    }

    @Test
    fun `removes text, time and EXIF from a PNG`() {
        val header = chunk("IHDR", bytes(0, 0, 0, 1, 0, 0, 0, 1, 8, 2, 0, 0, 0))
        val profile = chunk("iCCP", ascii("sRGB\u0000\u0000profile"))
        val data = chunk("IDAT", bytes(0x78, 0x9C, 0x63, 0x60, 0, 0, 0, 2, 0, 1))
        val end = chunk("IEND", ByteArray(0))
        val png =
            join(
                pngSignature,
                header,
                chunk("tEXt", ascii("Author\u0000Someone")),
                profile,
                chunk("eXIf", ascii("MM\u0000*GPS")),
                chunk("tIME", bytes(7, 0xEA, 11, 18, 9, 30, 0)),
                chunk("iTXt", ascii("XML:com.adobe.xmp\u0000\u0000\u0000\u0000\u0000<x:xmpmeta/>")),
                data,
                chunk("zTXt", ascii("Comment\u0000\u0000x")),
                end,
                ascii("trailing"),
            )

        assertContentEquals(join(pngSignature, header, profile, data, end), MetadataStripper.stripPng(png))
    }

    @Test
    fun `refuses what isn't a whole PNG`() {
        assertFailsWith<IllegalArgumentException> { MetadataStripper.stripPng(ascii("not a png at all")) }
        assertFailsWith<IllegalArgumentException> { MetadataStripper.stripPng(join(pngSignature, chunk("IHDR", ByteArray(13)))) }
    }

    private fun riffChunk(
        type: String,
        data: ByteArray,
    ) = join(ascii(type), littleEndian(data.size), data, if (data.size % 2 == 1) bytes(0) else ByteArray(0))

    private fun littleEndian(value: Int) = bytes(value and 0xFF, (value shr 8) and 0xFF, (value shr 16) and 0xFF, value ushr 24)

    private fun webp(vararg chunks: ByteArray): ByteArray {
        val body = join(*chunks)
        return join(ascii("RIFF"), littleEndian(4 + body.size), ascii("WEBP"), body)
    }

    private fun extendedHeader(flags: Int) = riffChunk("VP8X", bytes(flags, 0, 0, 0, 0, 0, 0, 0, 0, 0))

    @Test
    fun `removes EXIF and XMP from a WebP and says so in its header`() {
        val profile = riffChunk("ICCP", ascii("profile"))
        // An odd size, so the chunk carries a padding byte.
        val image = riffChunk("VP8L", bytes(0x2F, 0, 0, 0, 0))
        val webp =
            webp(
                extendedHeader(0x20 or 0x08 or 0x04),
                profile,
                image,
                riffChunk("EXIF", ascii("MM\u0000*GPS")),
                riffChunk("XMP ", ascii("<x:xmpmeta/>")),
            )

        val stripped = MetadataStripper.stripWebp(webp)

        assertContentEquals(webp(extendedHeader(0x20), profile, image), stripped)
        assertEquals(
            stripped.size - 8,
            stripped.copyOfRange(4, 8).foldIndexed(0) { i, sum, b -> sum or ((b.toInt() and 0xFF) shl (8 * i)) },
        )
    }

    @Test
    fun `refuses what isn't a WebP`() {
        assertFailsWith<IllegalArgumentException> { MetadataStripper.stripWebp(join(ascii("RIFF"), littleEndian(4), ascii("WAVE"))) }
        assertFailsWith<IllegalArgumentException> {
            MetadataStripper.stripWebp(join(ascii("RIFF"), littleEndian(100), ascii("WEBP"), ascii("VP8L"), littleEndian(90), bytes(1, 2)))
        }
    }
}
