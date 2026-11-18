package io.github.davidvornholt.glimpse.shared.network

import java.net.InetAddress
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LocalNetworkTest {
    private fun isLocal(address: String) = LocalNetwork.isLocalAddress(InetAddress.getByName(address))

    @Test
    fun `counts private, link-local and shared addresses as local`() {
        listOf(
            "10.0.2.2",
            "172.16.0.1",
            "172.31.255.254",
            "192.168.178.20",
            "169.254.1.1",
            "100.64.0.1",
            "100.127.255.254",
            "fd7a:115c:a1e0::1",
            "fe80::1",
        ).forEach { assertTrue(isLocal(it), it) }
    }

    @Test
    fun `counts public and loopback addresses as not local`() {
        listOf("203.0.113.7", "172.32.0.1", "100.128.0.1", "8.8.8.8", "127.0.0.1", "2001:db8::1", "::1")
            .forEach { assertFalse(isLocal(it), it) }
    }
}
