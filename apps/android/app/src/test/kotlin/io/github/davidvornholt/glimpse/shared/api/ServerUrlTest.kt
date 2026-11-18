package io.github.davidvornholt.glimpse.shared.api

import okhttp3.HttpUrl.Companion.toHttpUrl
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ServerUrlTest {
    @Test
    fun `takes URLs on the paired server`() {
        assertTrue(isOnServer("https://glimpse.example.com/v1/photos/01J/preview".toHttpUrl(), "https://glimpse.example.com"))
        assertTrue(isOnServer("https://GLIMPSE.example.com:443/v1/photos".toHttpUrl(), "https://glimpse.example.com"))
        assertTrue(isOnServer("https://glimpse.example.com/glimpse/v1/photos".toHttpUrl(), "https://glimpse.example.com/glimpse"))
    }

    @Test
    fun `refuses other hosts, ports, schemes and paths that only share a prefix`() {
        val server = "https://glimpse.example.com"
        listOf(
            "https://glimpse.example.com.attacker.example/v1/photos",
            "https://glimpse.example.community/v1/photos",
            "https://glimpse.example.com:8443/v1/photos",
            "http://glimpse.example.com/v1/photos",
        ).forEach { assertFalse(isOnServer(it.toHttpUrl(), server), it) }
        assertFalse(isOnServer("https://glimpse.example.com/glimpse-other/v1".toHttpUrl(), "https://glimpse.example.com/glimpse"))
        assertFalse(isOnServer("https://glimpse.example.com/v1".toHttpUrl(), "not a server"))
    }
}
