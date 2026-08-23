package com.mezo.trace.infra.fetch

import com.mezo.trace.application.port.FetchFailure
import java.net.InetAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SsrfGuardTest {

    private fun dnsOf(vararg addresses: String) = SsrfGuard.DnsResolver { host ->
        addresses.map { InetAddress.getByName(it) }.toTypedArray()
    }

    @Test
    fun `public addresses are allowed`() {
        SsrfGuard.requirePublicHost("example.com", dnsOf("93.184.216.34"))
        SsrfGuard.requirePublicHost("v6.example.com", dnsOf("2606:2800:220:1:248:1893:25c8:1946"))
    }

    @Test
    fun `ipv4 private and reserved ranges are blocked`() {
        val blocked = listOf(
            "127.0.0.1",       // loopback
            "10.1.2.3",        // private
            "172.16.0.9",      // private
            "172.31.255.1",    // private
            "192.168.1.1",     // private
            "169.254.169.254", // cloud metadata / link-local
            "0.0.0.0",         // this-network
            "100.64.0.1",      // CGNAT
            "198.18.0.1",      // benchmarking
            "224.0.0.1",       // multicast
        )
        blocked.forEach { ip ->
            val failure = assertFailsWith<FetchFailure> {
                SsrfGuard.requirePublicHost("host.internal", dnsOf(ip))
            }
            assertEquals(FetchFailure.Kind.BLOCKED_PRIVATE_ADDRESS, failure.kind, "expected block for $ip")
        }
    }

    @Test
    fun `ipv6 loopback link-local and unique-local are blocked`() {
        listOf("::1", "fe80::1", "fc00::1", "fd12:3456::1").forEach { ip ->
            assertFailsWith<FetchFailure> {
                SsrfGuard.requirePublicHost("host6.internal", dnsOf(ip))
            }
        }
    }

    @Test
    fun `ipv4-mapped ipv6 addresses are validated as their embedded v4`() {
        // ::ffff:10.0.0.5 → embedded private v4 must be blocked.
        assertFailsWith<FetchFailure> {
            SsrfGuard.requirePublicHost("mapped.internal", dnsOf("::ffff:10.0.0.5"))
        }
        // ::ffff:93.184.216.34 → public is allowed.
        SsrfGuard.requirePublicHost("mapped-public.internal", dnsOf("::ffff:93.184.216.34"))
    }

    @Test
    fun `dns failures are distinct from blocks`() {
        val failing = SsrfGuard.DnsResolver { throw java.net.UnknownHostException("nope") }
        val failure = assertFailsWith<FetchFailure> { SsrfGuard.requirePublicHost("missing.host", failing) }
        assertEquals(FetchFailure.Kind.DNS_FAILURE, failure.kind)
    }

    @Test
    fun `redirect targets are revalidated`() {
        val httpsPublic = java.net.URI("https://example.com/start")

        // Relative redirect resolving to the same public host: allowed shape.
        val next = SsrfGuard.requireValidRedirect(httpsPublic, "/next")
        assertTrue(next.toString().endsWith("/next"))

        // Redirect to a literal private host is rejected by DNS resolution.
        assertFailsWith<FetchFailure> {
            SsrfGuard.requireValidRedirect(httpsPublic, "http://127.0.0.1:8080/admin")
        }

        // Redirect to a non-http(s) scheme is rejected outright.
        assertFailsWith<FetchFailure> {
            SsrfGuard.requireValidRedirect(httpsPublic, "file:///etc/passwd")
        }
    }
}
