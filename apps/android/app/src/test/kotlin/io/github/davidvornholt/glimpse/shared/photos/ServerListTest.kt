package io.github.davidvornholt.glimpse.shared.photos

import io.github.davidvornholt.glimpse.shared.api.OriginalType
import io.github.davidvornholt.glimpse.shared.api.PhotoSource
import io.github.davidvornholt.glimpse.shared.api.TEST_PHOTO_ID
import io.github.davidvornholt.glimpse.shared.api.TEST_TIME
import io.github.davidvornholt.glimpse.shared.settings.Session
import io.github.davidvornholt.glimpse.shared.upload.CaptureRecord
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class ServerListTest {
    private val uploaded =
        CaptureRecord(
            id = TEST_PHOTO_ID,
            takenAt = TEST_TIME,
            source = PhotoSource.Camera,
            width = 4032,
            height = 3024,
            originalType = OriginalType.Jpeg,
            registered = true,
            previewSent = true,
            originalSent = true,
        )
    private val session = Session("https://glimpse.example.com", "token", "device", "Pixel")
    private val empty = ServerList(session, emptySet())

    @Test
    fun `takes off only a photo the server says it no longer has`() =
        runTest {
            assertEquals(listOf(TEST_PHOTO_ID), empty.deletedAmong(listOf(uploaded)) { false })
            // The upload finished while the list loaded, or the server didn't answer.
            assertEquals(emptyList(), empty.deletedAmong(listOf(uploaded)) { true })
            assertEquals(emptyList(), empty.deletedAmong(listOf(uploaded)) { null })
        }

    @Test
    fun `asks only about finished photos the list lacks`() =
        runTest {
            val asked = mutableListOf<String>()
            val ask: suspend (String) -> Boolean? = { id -> false.also { asked += id } }
            empty.copy(ids = setOf(TEST_PHOTO_ID)).deletedAmong(listOf(uploaded), ask)
            // An agent can't see the photo yet, or its note hasn't gone out.
            empty.deletedAmong(listOf(uploaded.copy(previewSent = false, originalSent = false)), ask)
            empty.deletedAmong(listOf(uploaded.copy(note = "Left one")), ask)
            assertEquals(emptyList(), asked)

            empty.deletedAmong(listOf(uploaded), ask)
            assertEquals(listOf(TEST_PHOTO_ID), asked)
        }
}
