package com.mezo.trace.infra.fetch

import com.mezo.trace.application.port.FetchFailure
import java.net.InetAddress
import java.net.URI

/**
 * SSRF guard (SECURITY.md §3, ADR-011, THREAT_MODEL T2).
 *
 * Blocks private/loopback/link-local/metadata ranges by **resolved IP** —
 * never by URL string alone. DNS resolution is injectable so rebinding
 * behavior is unit-testable.
 */
object SsrfGuard {

    /** Hostname → addresses; injectable for deterministic tests. */
    fun interface DnsResolver {
        fun resolve(host: String): Array<InetAddress>
    }

    val systemDns = DnsResolver { host -> InetAddress.getAllByName(host) }

    /**
     * Resolves [host] and requires every address to be public.
     *
     * @throws FetchFailure BLOCKED_PRIVATE_ADDRESS or DNS_FAILURE
     */
    fun requirePublicHost(host: String, dns: DnsResolver = systemDns) {
        val addresses = try {
            dns.resolve(host)
        } catch (_: Exception) {
            throw FetchFailure(FetchFailure.Kind.DNS_FAILURE, "cannot resolve host: $host")
        }
        if (addresses.isEmpty()) {
            throw FetchFailure(FetchFailure.Kind.DNS_FAILURE, "no addresses for host: $host")
        }
        addresses.firstOrNull { !isAllowedAddress(it) }?.let {
            throw FetchFailure(
                FetchFailure.Kind.BLOCKED_PRIVATE_ADDRESS,
                "blocked non-public address for host $host: ${it.hostAddress}",
            )
        }
    }

    /**
     * Validates a redirect target before it is followed. DNS resolution
     * defaults to the system resolver; the fetcher passes its injected
     * resolver so every hop uses the same source of truth.
     */
    fun requireValidRedirect(current: URI, location: String, dns: DnsResolver = systemDns): URI {
        val next = runCatching { current.resolve(location) }.getOrNull()
            ?: throw FetchFailure(FetchFailure.Kind.UNSUPPORTED_SCHEME, "unresolvable redirect target")
        if (next.scheme != "https" && next.scheme != "http") {
            throw FetchFailure(FetchFailure.Kind.UNSUPPORTED_SCHEME, "blocked redirect scheme: ${next.scheme}")
        }
        requirePublicHost(next.host ?: "", dns)
        return next
    }

    /**
     * Allowlist of address space reachable by fetch workers.
     */
    fun isAllowedAddress(address: InetAddress): Boolean {
        if (address.isAnyLocalAddress || address.isLoopbackAddress ||
            address.isLinkLocalAddress || address.isSiteLocalAddress ||
            address.isMulticastAddress
        ) {
            return false
        }
        return when (address) {
            is java.net.Inet4Address -> isAllowedV4(address)
            is java.net.Inet6Address -> isAllowedV6(address)
            else -> false
        }
    }

    private fun isAllowedV4(ip: java.net.Inet4Address): Boolean {
        val a = ip.address
        val first = a[0].toInt() and 0xFF
        val second = a[1].toInt() and 0xFF

        // 0.0.0.0/8 ("this network"), 10/8, 127/8 handled above,
        // 100.64/10 CGNAT, 169.254/16 link-local handled above,
        // 172.16/12, 192.168/16 handled above, 198.18/15 benchmarking.
        val blocked = when {
            first == 0 -> true
            first == 10 -> true
            first == 100 && second in 64..127 -> true
            first == 172 && second in 16..31 -> true
            first == 192 && second == 168 -> true
            first == 198 && (second == 18 || second == 19) -> true
            first >= 224 -> true // multicast + reserved
            else -> false
        }
        return !blocked
    }

    private fun isAllowedV6(ip: java.net.Inet6Address): Boolean {
        val bytes = ip.address
        // ::1 loopback, ::/128 unspecified, fc00::/7 unique local,
        // fe80::/10 link local, ff00::/8 multicast — handled above except:
        val first = bytes[0].toInt() and 0xFF
        if (first == 0xFF) return false // multicast
        if ((first and 0xFE) == 0xFC) return false // fc00::/7
        // IPv4-mapped (::ffff:0:0/96): validate the embedded v4 address.
        if (isV4Mapped(bytes)) {
            return isAllowedV4Bytes(bytes.sliceArray(12..15))
        }
        // NAT64 64:ff9b::/96 embeds IPv4 similarly — treat conservatively.
        if (first == 0x00 && bytes[1].toInt() == 0x00 && isNat64(bytes)) {
            return isAllowedV4Bytes(bytes.sliceArray(12..15))
        }
        return true
    }

    private fun isAllowedV4Bytes(v4: ByteArray): Boolean {
        val first = v4[0].toInt() and 0xFF
        val second = v4[1].toInt() and 0xFF

        val blocked = when {
            first == 0 -> true
            first == 10 -> true
            first == 100 && second in 64..127 -> true
            first == 172 && second in 16..31 -> true
            first == 192 && second == 168 -> true
            first == 198 && (second == 18 || second == 19) -> true
            first >= 224 -> true
            else -> false
        }
        return !blocked
    }

    private fun isV4Mapped(b: ByteArray): Boolean {
        for (i in 0..9) if (b[i].toInt() != 0) return false
        return b[10] == 0xff.toByte() && b[11] == 0xff.toByte()
    }

    private fun isNat64(b: ByteArray): Boolean =
        b[0].toInt() == 0x00 && b[1].toInt() == 0x64 && b[2].toInt() == 0xff && b[3].toInt() == 0x9b &&
            (0 until 8).all { b[it].toInt() == 0 } && b[8].toInt() == 0 && b[9].toInt() == 0 &&
            b[10].toInt() == 0 && b[11].toInt() == 0
}
