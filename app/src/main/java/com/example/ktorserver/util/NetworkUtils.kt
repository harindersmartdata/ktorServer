package com.example.ktorserver.util

import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Best-effort local IPv4 address, so the UI can show a URL other devices can hit.
 * Returns null when the device is offline (no non-loopback IPv4 interface).
 */
fun localIpv4Address(): String? = runCatching {
    NetworkInterface.getNetworkInterfaces()
        .asSequence()
        .filter { it.isUp && !it.isLoopback }
        .flatMap { it.inetAddresses.asSequence() }
        .filterIsInstance<Inet4Address>()
        .firstOrNull { !it.isLoopbackAddress }
        ?.hostAddress
}.getOrNull()
