package com.example.ktorserver.server

import io.ktor.util.logging.KtorSimpleLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetAddress
import javax.jmdns.JmDNS
import javax.jmdns.ServiceInfo

/**
 * Announces `tucount.local` over mDNS for as long as this terminal is primary.
 *
 * JmDNS is handed the address and the host name, which makes it publish the A record
 * clients resolve. Re-advertising is cheap and idempotent, but changing the address
 * means tearing the responder down and rebuilding it — the A record is fixed at
 * construction.
 */
class JmdnsAdvertiser : ServiceAdvertiser {

    private val logger = KtorSimpleLogger("mDNS")

    private var jmdns: JmDNS? = null
    private var announced: Announcement? = null

    private data class Announcement(val host: String, val ipv4: String, val port: Int)

    override suspend fun advertise(hostName: String, ipv4: String, port: Int) {
        val wanted = Announcement(hostName, ipv4, port)
        if (announced == wanted) return

        withContext(Dispatchers.IO) {
            withdraw()
            runCatching {
                val responder = JmDNS.create(InetAddress.getByName(ipv4), hostName)
                responder.registerService(
                    ServiceInfo.create("_http._tcp.local.", "TuCount", port, "role=primary")
                )
                jmdns = responder
                announced = wanted
                logger.info("Announcing $hostName.local -> $ipv4:$port")
            }.onFailure { logger.warn("Could not announce $hostName.local: ${it.message}") }
        }
    }

    override fun withdraw() {
        val responder = jmdns ?: return
        runCatching {
            responder.unregisterAllServices()
            responder.close()
        }
        jmdns = null
        announced = null
        logger.info("Stopped announcing")
    }
}
