package io.github.davidvornholt.glimpse.shared.image

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import io.github.davidvornholt.glimpse.shared.api.OriginalType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import kotlin.math.max
import kotlin.math.roundToInt

/** An original ready to upload: stripped of metadata, with its upright size. */
data class PreparedImage(
    val type: OriginalType,
    val width: Int,
    val height: Int,
)

/** The image is larger than the server takes, or isn't an image. */
class UnusableImageException(
    message: String,
) : IOException(message)

/**
 * Turns camera captures and shared images into what the server takes: an
 * original without location or other identifying metadata, and a JPEG
 * preview of at most 2048 px on its longest edge and 4 MiB.
 */
class ImageProcessor(
    private val resolver: ContentResolver,
) {
    /** Strips a camera JPEG in place of [original]. Only the orientation stays. */
    suspend fun prepareCapture(
        jpeg: File,
        original: File,
    ): PreparedImage =
        withContext(Dispatchers.IO) {
            val bytes = jpeg.readBytes()
            prepareBytes(bytes, original, OriginalType.Jpeg, orientationOf(bytes)).also { jpeg.delete() }
        }

    /** Reads a shared image and writes its stripped original to the file [original] names for its type. */
    suspend fun prepareShared(
        uri: Uri,
        original: (OriginalType) -> File,
    ): Pair<File, PreparedImage> =
        withContext(Dispatchers.IO) {
            val bytes =
                try {
                    resolver.openInputStream(uri)?.use { input -> readLimited(input.buffered()) }
                } catch (_: SecurityException) {
                    null
                } ?: throw UnusableImageException("The image can't be read")
            val sniffed = sniff(bytes)
            val orientation = orientationOf(bytes)
            val type = uploadTypeFor(sniffed, orientation)
            val file = original(type)
            file to if (type == sniffed) prepareBytes(bytes, file, type, orientation) else reencode(bytes, file)
        }

    private fun prepareBytes(
        bytes: ByteArray,
        target: File,
        type: OriginalType,
        orientation: Int,
    ): PreparedImage {
        val stripped = MetadataStripper.strip(bytes, type, orientation)
        writeAtomically(target, stripped)
        val bounds = bounds(stripped)
        val turned = orientation in TURNED
        return PreparedImage(type, if (turned) bounds.second else bounds.first, if (turned) bounds.first else bounds.second)
    }

    private fun reencode(
        bytes: ByteArray,
        target: File,
    ): PreparedImage {
        val bitmap =
            try {
                ImageDecoder.decodeBitmap(ImageDecoder.createSource(bytes)) { decoder, _, _ ->
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
                }
            } catch (error: IOException) {
                throw UnusableImageException("The image can't be decoded: ${error.message}")
            }
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, REENCODE_QUALITY, out)
        val prepared = PreparedImage(OriginalType.Jpeg, bitmap.width, bitmap.height)
        bitmap.recycle()
        writeAtomically(target, out.toByteArray())
        return prepared
    }

    /** Writes the preview of [original] to [preview], upright and sRGB. */
    suspend fun writePreview(
        original: File,
        preview: File,
    ) = withContext(Dispatchers.IO) {
        val bytes = original.readBytes()
        val orientation = orientationOf(bytes)
        val (width, height) = bounds(bytes)
        val options =
            BitmapFactory.Options().apply {
                inSampleSize = sampleSize(max(width, height))
                inPreferredColorSpace = ColorSpace.get(ColorSpace.Named.SRGB)
            }
        val decoded =
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
                ?: throw UnusableImageException("The image can't be decoded")
        val scale = minOf(1f, MAX_PREVIEW_EDGE.toFloat() / max(decoded.width, decoded.height))
        val matrix = orientationMatrix(orientation).apply { postScale(scale, scale) }
        val upright = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
        if (upright !== decoded) decoded.recycle()
        var quality = PREVIEW_QUALITY
        var encoded: ByteArray
        do {
            val out = ByteArrayOutputStream()
            upright.compress(Bitmap.CompressFormat.JPEG, quality, out)
            encoded = out.toByteArray()
            quality -= QUALITY_STEP
        } while (encoded.size > MAX_PREVIEW_BYTES && quality > 0)
        upright.recycle()
        writeAtomically(preview, encoded)
    }

    private fun sampleSize(longestEdge: Int): Int {
        var sample = 1
        // Decode at the smallest power of two that still leaves a sharp preview.
        while (longestEdge / (sample * 2) >= MAX_PREVIEW_EDGE) sample *= 2
        return sample
    }

    private fun orientationOf(bytes: ByteArray): Int =
        try {
            ExifInterface(ByteArrayInputStream(bytes))
                .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
                .takeIf { it in 1..8 } ?: 1
        } catch (_: IOException) {
            1
        }

    private fun orientationMatrix(orientation: Int) =
        Matrix().apply {
            when (orientation) {
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> {
                    postScale(-1f, 1f)
                }

                ExifInterface.ORIENTATION_ROTATE_180 -> {
                    postRotate(180f)
                }

                ExifInterface.ORIENTATION_FLIP_VERTICAL -> {
                    postScale(1f, -1f)
                }

                ExifInterface.ORIENTATION_TRANSPOSE -> {
                    postRotate(90f)
                    postScale(-1f, 1f)
                }

                ExifInterface.ORIENTATION_ROTATE_90 -> {
                    postRotate(90f)
                }

                ExifInterface.ORIENTATION_TRANSVERSE -> {
                    postRotate(-90f)
                    postScale(-1f, 1f)
                }

                ExifInterface.ORIENTATION_ROTATE_270 -> {
                    postRotate(270f)
                }
            }
        }

    private fun bounds(bytes: ByteArray): Pair<Int, Int> {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        if (options.outWidth <= 0 || options.outHeight <= 0) throw UnusableImageException("The image has no size")
        return options.outWidth to options.outHeight
    }

    private fun readLimited(input: java.io.InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(BUFFER)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read == -1) break
            total += read
            if (total > MAX_INPUT_BYTES) throw UnusableImageException("The image is larger than 50 MiB")
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }

    private fun writeAtomically(
        target: File,
        bytes: ByteArray,
    ) {
        val partial = File(target.parentFile, "${target.name}.part")
        partial.writeBytes(bytes)
        if (!partial.renameTo(target)) throw IOException("Couldn't save ${target.name}")
    }

    companion object {
        private const val MAX_PREVIEW_EDGE = 2048
        private const val MAX_PREVIEW_BYTES = 4 * 1024 * 1024
        private const val MAX_INPUT_BYTES = 50L * 1024 * 1024
        private const val PREVIEW_QUALITY = 85
        private const val REENCODE_QUALITY = 95
        private const val QUALITY_STEP = 10
        private const val BUFFER = 64 * 1024
        private val TURNED = setOf(5, 6, 7, 8)

        /**
         * The type a shared image is uploaded as. JPEG, and PNG or WebP that
         * need no rotation, are stripped as they are. PNG and WebP keep no
         * orientation once their EXIF is gone, so a rotated one becomes an
         * upright JPEG, as do HEIF, AVIF and anything else.
         */
        fun uploadTypeFor(
            sniffed: OriginalType?,
            orientation: Int,
        ): OriginalType =
            when (sniffed) {
                OriginalType.Jpeg -> OriginalType.Jpeg
                OriginalType.Png, OriginalType.Webp -> if (orientation == 1) sniffed else OriginalType.Jpeg
                else -> OriginalType.Jpeg
            }

        /** The image type, read from the bytes rather than trusting what the sender claimed. */
        fun sniff(bytes: ByteArray): OriginalType? {
            fun ascii(
                from: Int,
                length: Int,
            ) = if (bytes.size >= from + length) String(bytes, from, length, Charsets.US_ASCII) else ""
            return when {
                bytes.size > 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte() -> {
                    OriginalType.Jpeg
                }

                bytes.size > 8 && bytes[0] == 0x89.toByte() && ascii(1, 3) == "PNG" -> {
                    OriginalType.Png
                }

                ascii(0, 4) == "RIFF" && ascii(8, 4) == "WEBP" -> {
                    OriginalType.Webp
                }

                ascii(4, 4) == "ftyp" -> {
                    when (ascii(8, 4)) {
                        "avif", "avis" -> OriginalType.Avif
                        "heic", "heix", "heim", "heis" -> OriginalType.Heic
                        else -> OriginalType.Heif
                    }
                }

                else -> {
                    null
                }
            }
        }
    }
}
