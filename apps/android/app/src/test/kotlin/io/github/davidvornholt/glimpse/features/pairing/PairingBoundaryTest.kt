package io.github.davidvornholt.glimpse.features.pairing

import io.github.davidvornholt.glimpse.shared.api.ClaimResult
import io.github.davidvornholt.glimpse.shared.api.Device
import io.github.davidvornholt.glimpse.shared.api.GlimpseApi
import io.github.davidvornholt.glimpse.shared.api.OriginalType
import io.github.davidvornholt.glimpse.shared.api.PhotoSource
import io.github.davidvornholt.glimpse.shared.api.ServerEvent
import io.github.davidvornholt.glimpse.shared.api.TEST_PHOTO_ID
import io.github.davidvornholt.glimpse.shared.api.TEST_TIME
import io.github.davidvornholt.glimpse.shared.api.testPhoto
import io.github.davidvornholt.glimpse.shared.pairing.PairingLink
import io.github.davidvornholt.glimpse.shared.photos.PhotoFeed
import io.github.davidvornholt.glimpse.shared.photos.PhotoLibrary
import io.github.davidvornholt.glimpse.shared.settings.Session
import io.github.davidvornholt.glimpse.shared.settings.SessionState
import io.github.davidvornholt.glimpse.shared.settings.SessionStore
import io.github.davidvornholt.glimpse.shared.settings.SettingsStore
import io.github.davidvornholt.glimpse.shared.ulid.UlidGenerator
import io.github.davidvornholt.glimpse.shared.upload.CaptureRecord
import io.github.davidvornholt.glimpse.shared.upload.CaptureStore
import io.github.davidvornholt.glimpse.shared.upload.DeleteScheduler
import io.github.davidvornholt.glimpse.shared.upload.UploadScheduler
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class PairingBoundaryTest {
    private val root = Files.createTempDirectory("pairing").toFile()
    private val server = "https://glimpse.example.com"
    private val link = PairingLink("$server/second", "code")
    private val old = Session(server, "test", "first-device", "Phone")
    private val sessions = mock(SessionStore::class.java)
    private val settings = mock(SettingsStore::class.java)
    private val uploads = mock(UploadScheduler::class.java)
    private val state = MutableStateFlow<SessionState>(SessionState.Paired(old))
    private val captures = CaptureStore(File(root, "captures"))
    private val record = CaptureRecord(TEST_PHOTO_ID, TEST_TIME, PhotoSource.Camera, 1, 1, OriginalType.Jpeg)
    private val api = mock(GlimpseApi::class.java)
    private val revoked = mutableListOf<File>()
    private lateinit var feed: PhotoFeed
    private lateinit var library: PhotoLibrary

    private suspend fun TestScope.claimer(): PairingClaimer {
        val deletes = mock(DeleteScheduler::class.java)
        `when`(uploads.progress).thenReturn(MutableStateFlow(emptyMap()))
        `when`(deletes.pending).thenReturn(flowOf(emptySet()))
        `when`(sessions.state).thenReturn(state)
        `when`(sessions.lastServer()).thenReturn(server)
        `when`(api.claim("code", "Phone")).thenReturn(ClaimResult("new-test", Device("new-device", "Phone", TEST_TIME)))
        `when`(api.photo(record.id)).thenReturn(testPhoto())
        `when`(sessions.lastDeviceId()).thenReturn(old.deviceId)
        `when`(api.events()).thenReturn(
            flow {
                emit(ServerEvent.PhotoChanged(testPhoto()))
                emit(ServerEvent.Waiting(2))
                awaitCancellation()
            },
        )
        feed = PhotoFeed(sessions, { api }, backgroundScope)
        library =
            PhotoLibrary(captures, uploads, deletes, feed, sessions, settings, mock(), mock(), UlidGenerator(), {
                api
            }, root, { revoked.add(it) }, backgroundScope)
        return PairingClaimer({ api }, sessions, library)
    }

    @AfterTest fun cleanUp() {
        root.deleteRecursively()
    }

    @Test fun `warns about unsent originals after the device is removed`() =
        runTest {
            val claimer = claimer()
            state.value = SessionState.Unpaired(server)
            captures.prepare(record.id)
            captures.add(record)
            assertEquals(PairingClaimer.SwitchWarning(server, 1), claimer.switchWarning(link))
        }

    @Test fun `keeps captures on a previously stored default port`() =
        runTest {
            val claimer = claimer()
            `when`(sessions.lastServer()).thenReturn("$server:443")
            captures.prepare(record.id)
            captures.add(record)
            claimer.claim(link.copy(server = server), "Phone")
            assertEquals(record, captures.get(record.id))
        }

    @Test fun `reclaims an unfinished registration and keeps its original and note`() =
        runTest {
            val claimer = claimer()
            captures.prepare(record.id)
            captures.originalFile(record).writeText("original")
            captures.previewFile(record.id).writeText("preview")
            captures.add(record.copy(registered = true, note = "caption", registrations = 1))
            val session = claimer.claim(link.copy(server = server), "Phone")
            assertEquals(old.deviceId, captures.get(record.id)?.registrationDevice)
            library.recoverRegistration(record.id, session)
            val recovered =
                captures.records.value.values
                    .single()
            assertNotEquals(record.id, recovered.id)
            assertFalse(recovered.registered)
            assertEquals("caption", recovered.note)
            assertEquals("original", captures.originalFile(recovered).readText())
            assertEquals("preview", captures.previewFile(recovered.id).readText())
            assertEquals(recovered, CaptureStore(File(root, "captures")).get(recovered.id))
            verify(uploads).resume(recovered.id)
        }

    @Test fun `a lost preview reply keeps the visible photo under its id`() =
        runTest {
            val claimer = claimer()
            captures.prepare(record.id)
            captures.add(record.copy(registered = true, registrationDevice = old.deviceId))
            `when`(api.photo(record.id)).thenReturn(testPhoto(readyAt = TEST_TIME))
            val session = claimer.claim(link.copy(server = server), "Phone")
            assertFalse(library.recoverRegistration(record.id, session))
            assertTrue(captures.get(record.id)!!.previewSent)
        }

    @Test fun `switching clears the next photo note`() =
        runTest {
            claimer().claim(link, "Phone")
            verify(settings).setPendingNote(null)
        }

    @Test fun `switching clears photos and waiters before the new server lists anything`() =
        runTest {
            val claimer = claimer()
            feed.start()
            runCurrent()
            assertEquals(setOf(TEST_PHOTO_ID), feed.photos.value.keys)
            assertEquals(2, feed.waiting.value)
            claimer.claim(link, "Phone")
            feed.upsert(old, testPhoto())
            assertTrue(feed.photos.value.isEmpty())
            assertEquals(0, feed.waiting.value)
        }

    @Test fun `unpair and switch cleanup removes shared and downloaded copies`() =
        runTest {
            claimer()
            val shared = File(root, "shared").apply { mkdirs() }
            File(shared, "shared.jpg").writeText("copy")
            File(shared, "download.jpg").writeText("original")
            library.clear()
            assertEquals(setOf("shared.jpg", "download.jpg"), revoked.map { it.name }.toSet())
            assertFalse(shared.exists())
        }
}
