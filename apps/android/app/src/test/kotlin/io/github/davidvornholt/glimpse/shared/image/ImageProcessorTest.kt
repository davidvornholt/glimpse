package io.github.davidvornholt.glimpse.shared.image

import io.github.davidvornholt.glimpse.shared.api.OriginalType
import kotlin.test.Test
import kotlin.test.assertEquals

class ImageProcessorTest {
    @Test
    fun `uploads a rotated PNG or WebP as a JPEG, so its original is found under that type`() {
        assertEquals(OriginalType.Jpeg, ImageProcessor.uploadTypeFor(OriginalType.Png, orientation = 6))
        assertEquals(OriginalType.Jpeg, ImageProcessor.uploadTypeFor(OriginalType.Webp, orientation = 3))
    }

    @Test
    fun `keeps the type of a JPEG and of an upright PNG or WebP`() {
        assertEquals(OriginalType.Jpeg, ImageProcessor.uploadTypeFor(OriginalType.Jpeg, orientation = 6))
        assertEquals(OriginalType.Png, ImageProcessor.uploadTypeFor(OriginalType.Png, orientation = 1))
        assertEquals(OriginalType.Webp, ImageProcessor.uploadTypeFor(OriginalType.Webp, orientation = 1))
    }

    @Test
    fun `turns HEIF, AVIF and unknown images into a JPEG`() {
        listOf(OriginalType.Heic, OriginalType.Heif, OriginalType.Avif, null).forEach {
            assertEquals(OriginalType.Jpeg, ImageProcessor.uploadTypeFor(it, orientation = 1), "$it")
        }
    }
}
