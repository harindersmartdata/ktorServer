package com.example.ktorserver.util

import kotlinx.coroutines.CoroutineDispatcher

/**
 * Dispatcher for blocking SQLite work.
 *
 * `Dispatchers.IO` is not declared in common code (it has no meaning on every Kotlin
 * target), so each platform supplies it. Both of ours are JVM, so both resolve to the
 * same pool — the indirection exists to satisfy the compiler, not to hide a difference.
 */
expect val ioDispatcher: CoroutineDispatcher

/**
 * Best-effort local IPv4 address, so the UI can show a URL other machines can hit.
 * Returns null when there is no non-loopback IPv4 interface (offline).
 */
expect fun localIpv4Address(): String?
