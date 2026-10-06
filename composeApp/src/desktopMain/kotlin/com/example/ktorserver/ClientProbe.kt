package com.example.ktorserver

import com.example.ktorserver.client.TruCountClient
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * Drives [TruCountClient] against a running pair so failover can be observed.
 *
 * Writes once a second and prints which terminal served each call. Kill the primary
 * part-way through and promote the other: the point is that the gap shows up as a few
 * retried seconds, not as lost sales.
 *
 * `./gradlew clientProbe -PprobeArgs="127.0.0.1:9100 127.0.0.1:9101 30"`
 */
@OptIn(ExperimentalTime::class)
fun main(args: Array<String>) = runBlocking {
    val addresses = args.filter { it.contains(':') }
        .ifEmpty { listOf("127.0.0.1:9100", "127.0.0.1:9101") }
    val rounds = args.lastOrNull()?.toIntOrNull() ?: 30

    val client = TruCountClient(addresses)
    println("probe: candidates=$addresses rounds=$rounds")

    var ok = 0
    var failed = 0
    var lastPrimary: String? = null

    repeat(rounds) { i ->
        val started = Clock.System.now()
        val outcome = runCatching {
            client.createUser("Probe $i", "probe$i@example.com")
        }
        val tookMs = (Clock.System.now() - started).inWholeMilliseconds
        val servedBy = client.primary.value

        if (servedBy != lastPrimary) {
            println("  >>> primary is now ${servedBy ?: "unknown"}")
            lastPrimary = servedBy
        }

        if (outcome.isSuccess) {
            ok++
            println("  [$i] ok   ${tookMs}ms  via ${servedBy ?: "?"}")
        } else {
            failed++
            val why = outcome.exceptionOrNull()
            println("  [$i] FAIL ${tookMs}ms  ${why?.let { it::class.simpleName }}: ${why?.message}")
        }
        delay(1_000)
    }

    println("probe: $ok succeeded, $failed failed")
    client.close()
}
