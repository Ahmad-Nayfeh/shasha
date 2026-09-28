package com.ahmadnayfeh.shasha.remote

import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketException

/** Finds the TV's IPv4 address on the home network (the only address the server may bind to). */
object LanAddress {
    /** Mobile data, VPN, Wi-Fi Direct, containers, bridges: never where the phone is. */
    private val excludedPrefixes = listOf(
        "rmnet", "ccmni", "pdp", "tun", "ppp", "ipsec", "wg", "p2p", "dummy", "docker", "veth", "br-", "virbr",
    )

    fun find(): Inet4Address? = try {
        val interfaces = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
        choose(interfaces.mapNotNull(::describe))
    } catch (e: SocketException) {
        null
    }

    internal data class Candidate(
        val name: String,
        val isUp: Boolean,
        val isLoopback: Boolean,
        val isVirtual: Boolean,
        val isPointToPoint: Boolean,
        val addresses: List<InetAddress>,
    )

    /** Site-local IPv4 of an up, physical interface; wlan and eth interfaces win over anything else. */
    internal fun choose(candidates: List<Candidate>): Inet4Address? = candidates
        .filter { it.isUp && !it.isLoopback && !it.isVirtual && !it.isPointToPoint }
        .filterNot { c -> excludedPrefixes.any { c.name.startsWith(it) } }
        .sortedBy { if (it.name.startsWith("wlan") || it.name.startsWith("eth")) 0 else 1 }
        .firstNotNullOfOrNull { c -> c.addresses.filterIsInstance<Inet4Address>().firstOrNull { it.isSiteLocalAddress } }

    private fun describe(ni: NetworkInterface): Candidate? = try {
        Candidate(
            name = ni.name.orEmpty(),
            isUp = ni.isUp,
            isLoopback = ni.isLoopback,
            isVirtual = ni.isVirtual,
            isPointToPoint = ni.isPointToPoint,
            addresses = ni.inetAddresses.toList(),
        )
    } catch (e: SocketException) {
        null
    }
}
