package io.github.davidvornholt.glimpse.shared.upload

import io.github.davidvornholt.glimpse.shared.settings.Session
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DeletionSessionTest {
    private val session = Session("https://glimpse.example.com", "test", "device", "Phone")

    @Test
    fun `only the originating endpoint receives a queued deletion`() {
        assertNull(deletionSession("https://glimpse.example.com/other", session))
        assertNull(deletionSession(null, session))
        assertEquals(session, deletionSession("https://glimpse.example.com:443", session))
    }
}
