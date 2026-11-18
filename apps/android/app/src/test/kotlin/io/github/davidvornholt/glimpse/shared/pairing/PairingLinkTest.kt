package io.github.davidvornholt.glimpse.shared.pairing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PairingLinkTest {
    private val server = "https%3A%2F%2Fglimpse.example.com"

    private fun parse(
        text: String,
        allowCleartext: Boolean = false,
    ) = PairingLink.parse(text, allowCleartext)

    @Test
    fun `reads the link glimpse pair prints`() {
        val link = parse("glimpse://pair?server=$server&code=Kq3-v8_TzP")

        assertEquals(PairingLink("https://glimpse.example.com", "Kq3-v8_TzP"), link)
        assertEquals("glimpse.example.com", link?.host)
    }

    @Test
    fun `reads links written with pair as the path or in upper case`() {
        val expected = PairingLink("https://glimpse.example.com", "abc")

        assertEquals(expected, parse("glimpse:///pair?server=$server&code=abc"))
        assertEquals(expected, parse("GLIMPSE://PAIR?server=$server&code=abc"))
        assertEquals(expected, parse("  glimpse://pair?code=abc&server=$server\n"))
    }

    @Test
    fun `keeps a plus in the code and decodes percent escapes`() {
        assertEquals("a+b", parse("glimpse://pair?server=$server&code=a+b")?.code)
        assertEquals("a+b", parse("glimpse://pair?server=$server&code=a%2Bb")?.code)
        assertEquals("a b", parse("glimpse://pair?server=$server&code=a%20b")?.code)
    }

    @Test
    fun `takes the first of repeated parameters`() {
        val link = parse("glimpse://pair?server=$server&code=first&code=second&server=https%3A%2F%2Fother.example.com")

        assertEquals(PairingLink("https://glimpse.example.com", "first"), link)
    }

    @Test
    fun `accepts codes up to 128 characters`() {
        assertEquals(128, parse("glimpse://pair?server=$server&code=${"a".repeat(128)}")?.code?.length)
        assertNull(parse("glimpse://pair?server=$server&code=${"a".repeat(129)}"))
    }

    @Test
    fun `accepts plain HTTP only when cleartext is allowed`() {
        val link = "glimpse://pair?server=http%3A%2F%2F10.0.2.2%3A8787&code=abc"

        assertNull(parse(link))
        assertEquals(PairingLink("http://10.0.2.2:8787", "abc"), parse(link, allowCleartext = true))
    }

    @Test
    fun `refuses anything that isn't a pairing link`() {
        listOf(
            "",
            "glimpse pair",
            "https://glimpse.example.com/pair?server=$server&code=abc",
            "glimpse://unpair?server=$server&code=abc",
            "glimpse:pair?server=$server&code=abc",
            "glimpse://pair",
            "glimpse://pair?server=$server",
            "glimpse://pair?server=$server&code=",
            "glimpse://pair?code=abc",
            "glimpse://pair?server=$server&code=%ZZ",
            "glimpse://pair?server=ftp%3A%2F%2Fglimpse.example.com&code=abc",
            "glimpse://pair?server=glimpse.example.com&code=abc",
        ).forEach { assertNull(parse(it), it) }
    }

    @Test
    fun `normalizes the server to a base URL`() {
        assertEquals(
            "https://glimpse.example.com:8443/glimpse",
            PairingLink.normalizeServer("https://Glimpse.Example.com:8443/glimpse/", allowCleartext = false),
        )
        assertEquals("https://glimpse.example.com", PairingLink.normalizeServer(" HTTPS://glimpse.example.com/ ", false))
    }

    @Test
    fun `refuses servers with credentials, a query or a fragment`() {
        listOf(
            "https://owner:secret@glimpse.example.com",
            "https://glimpse.example.com/?token=abc",
            "https://glimpse.example.com/#top",
            "https:///no-host",
            "http://glimpse.example.com",
        ).forEach { assertNull(PairingLink.normalizeServer(it, allowCleartext = false), it) }
    }
}
