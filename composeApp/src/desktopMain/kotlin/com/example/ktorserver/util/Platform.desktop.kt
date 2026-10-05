package com.example.ktorserver.util

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface

actual val ioDispatcher: CoroutineDispatcher = Dispatchers.IO

/**
 * The address other machines on the network should use to reach this terminal.
 *
 * Enumerating interfaces does not work on a host with more than one: a spare adapter, a
 * VPN or a sharing bridge all look equally valid, and picking the first produced a
 * self-assigned 169.254 address on one machine and an unreachable virtual one on another.
 *
 * So the routing table is asked instead. Connecting a UDP socket transmits nothing — it
 * only fixes the local endpoint the OS would use for that destination — which reveals
 * the interface that actually carries traffic. The scan stays as a fallback for a host
 * with no default route.
 */
actual fun localIpv4Address(): String? = routedAddress() ?: scannedAddress()

private fun routedAddress(): String? = runCatching {
    DatagramSocket().use { socket ->
        // Any routable address serves; no packet is sent.
        socket.connect(InetAddress.getByName("203.0.113.1"), 9)
        (socket.localAddress as? Inet4Address)
            ?.takeUnless { it.isAnyLocalAddress || it.isLoopbackAddress || it.isLinkLocalAddress }
            ?.hostAddress
    }
}.getOrNull()

private fun scannedAddress(): String? = runCatching {
    NetworkInterface.getNetworkInterfaces()
        .asSequence()
        .filter { it.isUp && !it.isLoopback }
        .flatMap { it.inetAddresses.asSequence() }
        .filterIsInstance<Inet4Address>()
        .filterNot { it.isLoopbackAddress || it.isLinkLocalAddress }
        .sortedByDescending { it.isSiteLocalAddress }
        .firstOrNull()
        ?.hostAddress
}.getOrNull()
