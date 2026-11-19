package io.github.davidvornholt.glimpse.features.pairing

import kotlin.test.Test
import kotlin.test.assertEquals

class DeviceNameTest {
    @Test
    fun `suggests the name from the phone's settings`() {
        assertEquals("Work phone", suggestedDeviceName("  Work phone ", "Pixel 6"))
    }

    @Test
    fun `falls back to the model without a name`() {
        assertEquals("Pixel 6", suggestedDeviceName(null, "Pixel 6"))
        assertEquals("Pixel 6", suggestedDeviceName("   ", "Pixel 6"))
    }

    @Test
    fun `cuts a long name to what the server accepts`() {
        assertEquals("a".repeat(MAX_NAME), suggestedDeviceName("a".repeat(MAX_NAME + 10), "Pixel 6"))
    }
}
