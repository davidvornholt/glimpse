package io.github.davidvornholt.glimpse.shared.image

import io.github.davidvornholt.glimpse.shared.api.OriginalType
import java.io.ByteArrayOutputStream

/**
 * Removes what an image says about where, when and with what it was taken,
 * without decoding it: EXIF (with its location), XMP, IPTC, comments and
 * anything appended after the image, such as a motion photo's video. Color
 * profiles stay, so the image looks the same.
 */
object MetadataStripper {
    /**
     * Strips a JPEG, PNG or WebP. An image that is broken or isn't of [type]
     * throws [UnusableImageException], because it comes from another app.
     */
    fun strip(
        input: ByteArray,
        type: OriginalType,
        orientation: Int = 1,
    ): ByteArray =
        try {
            when (type) {
                OriginalType.Jpeg -> stripJpeg(input, orientation)
                OriginalType.Png -> stripPng(input)
                OriginalType.Webp -> stripWebp(input)
                else -> throw IllegalArgumentException("$type isn't stripped losslessly")
            }
        } catch (error: IllegalArgumentException) {
            throw UnusableImageException(error.message ?: "The image is broken")
        } catch (_: IndexOutOfBoundsException) {
            throw UnusableImageException("The image is broken")
        }

    /**
     * Strips a JPEG. When [orientation] isn't 1, a minimal EXIF block that
     * holds only the orientation goes back in, so the photo stays upright.
     */
    fun stripJpeg(
        input: ByteArray,
        orientation: Int = 1,
    ): ByteArray {
        require(input.size >= 4 && input.u8(0) == 0xFF && input.u8(1) == MARKER_SOI) { "Not a JPEG" }
        require(orientation in 1..8) { "EXIF orientation is 1 to 8, not $orientation" }
        val out = ByteArrayOutputStream(input.size)
        out.write(input, 0, 2)
        var orientationWritten = orientation == 1
        var position = 2
        while (position < input.size) {
            require(input.u8(position) == 0xFF) { "Broken JPEG at byte $position" }
            // Any number of 0xFF may pad the space before a marker.
            while (position < input.size && input.u8(position) == 0xFF) position++
            require(position < input.size) { "The JPEG ends inside a marker" }
            val marker = input.u8(position)
            val markerStart = position - 1
            position++
            if (marker == MARKER_EOI) {
                out.write(0xFF)
                out.write(MARKER_EOI)
                // Whatever follows the image, such as a motion photo's video, is dropped.
                return out.toByteArray()
            }
            if (marker in MARKER_RST0..MARKER_RST7 || marker == MARKER_TEM) {
                out.write(0xFF)
                out.write(marker)
                continue
            }
            require(position + 2 <= input.size) { "The JPEG ends inside a segment" }
            val length = (input.u8(position) shl 8) or input.u8(position + 1)
            require(length >= 2 && position + length <= input.size) { "A JPEG segment runs past the end" }
            val segmentEnd = position + length
            if (!orientationWritten && marker != MARKER_APP0) {
                out.write(orientationSegment(orientation))
                orientationWritten = true
            }
            if (keepsSegment(marker, input, position + 2, segmentEnd)) {
                out.write(0xFF)
                out.write(marker)
                out.write(input, position, length)
            }
            position = segmentEnd
            if (marker == MARKER_SOS) {
                val dataEnd = entropyCodedEnd(input, position)
                out.write(input, position, dataEnd - position)
                position = dataEnd
            }
        }
        throw IllegalArgumentException("The JPEG has no end marker")
    }

    /** Strips a PNG of its text, time and EXIF chunks and anything after its end. */
    fun stripPng(input: ByteArray): ByteArray {
        require(input.size >= PNG_SIGNATURE.size && input.copyOfRange(0, PNG_SIGNATURE.size).contentEquals(PNG_SIGNATURE)) {
            "Not a PNG"
        }
        val out = ByteArrayOutputStream(input.size)
        out.write(PNG_SIGNATURE)
        var position = PNG_SIGNATURE.size
        while (position + PNG_CHUNK_OVERHEAD <= input.size) {
            val length = input.u32BigEndian(position)
            require(length in 0..(input.size - position - PNG_CHUNK_OVERHEAD).toLong()) { "A PNG chunk runs past the end" }
            val type = String(input, position + 4, 4, Charsets.US_ASCII)
            val end = position + PNG_CHUNK_OVERHEAD + length.toInt()
            if (type !in PNG_DROPPED_CHUNKS) out.write(input, position, end - position)
            position = end
            if (type == "IEND") return out.toByteArray()
        }
        throw IllegalArgumentException("The PNG has no end chunk")
    }

    /** Strips a WebP of its EXIF and XMP chunks and clears the flags that announce them. */
    fun stripWebp(input: ByteArray): ByteArray {
        require(
            input.size >= WEBP_HEADER &&
                String(input, 0, 4, Charsets.US_ASCII) == "RIFF" &&
                String(input, 8, 4, Charsets.US_ASCII) == "WEBP",
        ) { "Not a WebP" }
        val riffEnd = minOf(input.size.toLong(), RIFF_PREAMBLE + input.u32LittleEndian(4)).toInt()
        val chunks = ByteArrayOutputStream(input.size)
        var position = WEBP_HEADER
        while (position + RIFF_PREAMBLE <= riffEnd) {
            val type = String(input, position, 4, Charsets.US_ASCII)
            val size = input.u32LittleEndian(position + 4)
            val padded = size + (size and 1)
            require(position + RIFF_PREAMBLE + padded <= riffEnd) { "A WebP chunk runs past the end" }
            val end = position + RIFF_PREAMBLE + padded.toInt()
            when (type) {
                "EXIF", "XMP " -> {}

                "VP8X" -> {
                    val chunk = input.copyOfRange(position, end)
                    chunk[RIFF_PREAMBLE] = (chunk[RIFF_PREAMBLE].toInt() and (VP8X_EXIF or VP8X_XMP).inv()).toByte()
                    chunks.write(chunk)
                }

                else -> {
                    chunks.write(input, position, end - position)
                }
            }
            position = end
        }
        val body = chunks.toByteArray()
        val out = ByteArrayOutputStream(WEBP_HEADER + body.size)
        out.write("RIFF".toByteArray(Charsets.US_ASCII))
        out.write(littleEndian(4 + body.size))
        out.write("WEBP".toByteArray(Charsets.US_ASCII))
        out.write(body)
        return out.toByteArray()
    }

    private fun keepsSegment(
        marker: Int,
        input: ByteArray,
        dataStart: Int,
        dataEnd: Int,
    ): Boolean =
        when (marker) {
            MARKER_APP0 -> true

            // APP2 also carries MPF, which points at images after the end that are dropped.
            MARKER_APP2 -> input.startsWith(ICC_PROFILE, dataStart, dataEnd)

            // Adobe's APP14 says how to read the colors of CMYK and YCCK images.
            MARKER_APP14 -> true

            in MARKER_APP1..MARKER_APP15, MARKER_COM -> false

            else -> true
        }

    /** Where the entropy-coded data after a start-of-scan header ends. */
    private fun entropyCodedEnd(
        input: ByteArray,
        start: Int,
    ): Int {
        var position = start
        while (position + 1 < input.size) {
            if (input.u8(position) == 0xFF) {
                val next = input.u8(position + 1)
                // 0xFF00 is an escaped 0xFF and restart markers belong to the scan.
                if (next != 0x00 && next !in MARKER_RST0..MARKER_RST7 && next != 0xFF) return position
            }
            position++
        }
        throw IllegalArgumentException("The JPEG ends inside its image data")
    }

    /** An APP1 segment holding EXIF with nothing but the orientation tag. */
    private fun orientationSegment(orientation: Int): ByteArray =
        byteArrayOf(
            0xFF.toByte(),
            MARKER_APP1.toByte(),
            0x00,
            0x22,
            // "Exif\0\0"
            0x45,
            0x78,
            0x69,
            0x66,
            0x00,
            0x00,
            // Big-endian TIFF header with the first directory at offset 8.
            0x4D,
            0x4D,
            0x00,
            0x2A,
            0x00,
            0x00,
            0x00,
            0x08,
            // One entry: tag 0x0112 Orientation, type SHORT, count 1, the value.
            0x00,
            0x01,
            0x01,
            0x12,
            0x00,
            0x03,
            0x00,
            0x00,
            0x00,
            0x01,
            0x00,
            orientation.toByte(),
            0x00,
            0x00,
            // No further directory.
            0x00,
            0x00,
            0x00,
            0x00,
        )

    private fun ByteArray.u8(index: Int) = this[index].toInt() and 0xFF

    private fun ByteArray.u32BigEndian(index: Int): Long =
        (u8(index).toLong() shl 24) or (u8(index + 1).toLong() shl 16) or (u8(index + 2).toLong() shl 8) or u8(index + 3).toLong()

    private fun ByteArray.u32LittleEndian(index: Int): Long =
        (u8(index + 3).toLong() shl 24) or (u8(index + 2).toLong() shl 16) or (u8(index + 1).toLong() shl 8) or u8(index).toLong()

    private fun ByteArray.startsWith(
        prefix: ByteArray,
        start: Int,
        end: Int,
    ): Boolean = end - start >= prefix.size && prefix.indices.all { this[start + it] == prefix[it] }

    private fun littleEndian(value: Int) =
        byteArrayOf(value.toByte(), (value shr 8).toByte(), (value shr 16).toByte(), (value shr 24).toByte())

    private const val MARKER_TEM = 0x01
    private const val MARKER_RST0 = 0xD0
    private const val MARKER_RST7 = 0xD7
    private const val MARKER_SOI = 0xD8
    private const val MARKER_EOI = 0xD9
    private const val MARKER_SOS = 0xDA
    private const val MARKER_APP0 = 0xE0
    private const val MARKER_APP1 = 0xE1
    private const val MARKER_APP2 = 0xE2
    private const val MARKER_APP14 = 0xEE
    private const val MARKER_APP15 = 0xEF
    private const val MARKER_COM = 0xFE
    private val ICC_PROFILE = "ICC_PROFILE\u0000".toByteArray(Charsets.US_ASCII)

    private val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
    private const val PNG_CHUNK_OVERHEAD = 12
    private val PNG_DROPPED_CHUNKS = setOf("eXIf", "tEXt", "zTXt", "iTXt", "tIME")

    private const val WEBP_HEADER = 12
    private const val RIFF_PREAMBLE = 8
    private const val VP8X_EXIF = 0x08
    private const val VP8X_XMP = 0x04
}
