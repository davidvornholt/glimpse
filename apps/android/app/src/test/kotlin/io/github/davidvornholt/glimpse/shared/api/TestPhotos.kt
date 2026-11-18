package io.github.davidvornholt.glimpse.shared.api

import java.time.Instant

/** A photo as the server describes it, for tests that need one. */
fun testPhoto(
    id: String = TEST_PHOTO_ID,
    note: String? = null,
    readyAt: Instant? = null,
    viewedAt: Instant? = null,
) = Photo(
    id = id,
    createdAt = TEST_TIME,
    readyAt = readyAt,
    takenAt = TEST_TIME,
    expiresAt = TEST_TIME.plusSeconds(30L * 24 * 60 * 60),
    source = PhotoSource.Camera,
    note = note,
    width = 4032,
    height = 3024,
    originalType = OriginalType.Jpeg,
    hasOriginal = false,
    deviceName = "Pixel 6",
    viewedAt = viewedAt,
)

/** The same photo as `GET /v1/photos/{id}` writes it. */
fun testPhotoJson(
    id: String = TEST_PHOTO_ID,
    note: String? = null,
    viewedAt: String? = null,
) = """
    {
      "id": "$id",
      "createdAt": "2026-11-18T09:30:00Z",
      "readyAt": null,
      "takenAt": "2026-11-18T09:30:00Z",
      "expiresAt": "2026-12-18T09:30:00Z",
      "source": "camera",
      "note": ${note?.let { "\"$it\"" } ?: "null"},
      "width": 4032,
      "height": 3024,
      "originalType": "image/jpeg",
      "hasOriginal": false,
      "originalBytes": null,
      "previewBytes": null,
      "deviceName": "Pixel 6",
      "viewedAt": ${viewedAt?.let { "\"$it\"" } ?: "null"},
      "batchId": null,
      "addedLater": true
    }
    """.trimIndent()

const val TEST_PHOTO_ID = "01KAB3XQ8Z7Y6W5V4T3S2R1Q0P"
val TEST_TIME: Instant = Instant.parse("2026-11-18T09:30:00Z")
