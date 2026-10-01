package com.example.ktorserver.util

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import java.net.Inet4Address
import java.net.NetworkInterface

actual val ioDispatcher: CoroutineDispatcher = Dispatchers.IO

actual fun localIpv4Address(): String? = runCatching {
    NetworkInterface.getNetworkInterfaces()
        .asSequence()
        .filter { it.isUp && !it.isLoopback }
        .flatMap { it.inetAddresses.asSequence() }
        .filterIsInstance<Inet4Address>()
        .firstOrNull { !it.isLoopbackAddress }
        ?.hostAddress
}.getOrNull()
