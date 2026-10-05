package com.example.ktorserver.server

import android.content.Context
import android.net.wifi.WifiManager
import io.ktor.util.logging.KtorSimpleLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetAddress
import javax.jmdns.JmDNS
import javax.jmdns.ServiceInfo

/**
 * Announces `tucount.local` over mDNS for as long as this terminal is primary.
 *
 * Android drops multicast packets unless a [WifiManager.MulticastLock] is held, so
 * without one the responder appears to start and simply never answers. The lock costs
 * battery, which is why it is released the moment this terminal stops being primary.
 */
class JmdnsAdvertiser(context: Context) : ServiceAdvertiser {

    private val logger = KtorSimpleLogger("mDNS")
    private val appContext = context.applicationContext

    private var jmdns: JmDNS? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var announced: Announcement? = null

    private data class Announcement(val host: String, val ipv4: String, val port: Int)

    override suspend fun advertise(hostName: String, ipv4: String, port: Int) {
        val wanted = Announcement(hostName, ipv4, port)
        if (announced == wanted) return

        withContext(Dispatchers.IO) {
            withdraw()
            runCatching {
                val wifi = appContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                multicastLock = wifi.createMulticastLock("tucount-mdns").apply {
                    setReferenceCounted(false)
                    acquire()
                }

                val responder = JmDNS.create(InetAddress.getByName(ipv4), hostName)
                responder.registerService(
                    ServiceInfo.create("_http._tcp.local.", "TuCount", port, "role=primary")
                )
                jmdns = responder
                announced = wanted
                logger.info("Announcing $hostName.local -> $ipv4:$port")
            }.onFailure {
                logger.warn("Could not announce $hostName.local: ${it.message}")
                releaseLock()
            }
        }
    }

    override fun withdraw() {
        jmdns?.let { responder ->
            runCatching {
                responder.unregisterAllServices()
                responder.close()
            }
            logger.info("Stopped announcing")
        }
        jmdns = null
        announced = null
        releaseLock()
    }

    private fun releaseLock() {
        runCatching { multicastLock?.takeIf { it.isHeld }?.release() }
        multicastLock = null
    }
}
