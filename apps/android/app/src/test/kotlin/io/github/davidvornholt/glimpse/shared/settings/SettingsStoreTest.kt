package io.github.davidvornholt.glimpse.shared.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SettingsStoreTest {
    @get:Rule val folder = TemporaryFolder()

    @Test
    fun `the first shutter press owns the note even when the second photo finishes first`() =
        runTest {
            val store = PreferenceDataStoreFactory.create(scope = backgroundScope) { folder.root.resolve("settings.preferences_pb") }
            val settings = SettingsStore(store, backgroundScope)
            settings.setPendingNote("First")
            settings.reserveNote("one")
            settings.reserveNote("two")
            assertNull(settings.reservedNote("two"))
            assertEquals("First", settings.reservedNote("one"))
        }

    @Test
    fun `restart releases an abandoned reservation and preserves one awaiting its scanner result`() =
        runTest {
            val store = PreferenceDataStoreFactory.create(scope = backgroundScope) { folder.root.resolve("settings.preferences_pb") }
            val before = SettingsStore(store, backgroundScope)
            for (id in listOf("abandoned", "scan", "finished")) {
                before.setPendingNote(id)
                before.reserveNote(id)
            }

            val restored = SettingsStore(store, backgroundScope)
            restored.recoverNotes(waiting = setOf("scan"), used = setOf("finished"))
            assertEquals("abandoned", restored.current().pendingNote)
            assertEquals("scan", restored.reservedNote("scan"))
            assertNull(restored.reservedNote("finished"))
        }

    @Test
    fun `an empty scan releases its note without overwriting a newer note`() =
        runTest {
            val store = PreferenceDataStoreFactory.create(scope = backgroundScope) { folder.root.resolve("settings.preferences_pb") }
            val settings = SettingsStore(store, backgroundScope)
            settings.setPendingNote("Scan")
            settings.reserveNote("scan")
            assertEquals("Scan", settings.reservedNote("scan"))
            settings.finishNote("scan", used = false)
            assertEquals("Scan", settings.current().pendingNote)
            settings.reserveNote("again")
            settings.setPendingNote("New")
            settings.finishNote("again", used = false)
            assertEquals("New", settings.current().pendingNote)
        }
}
