package com.example.ktorserver.server

import io.ktor.util.logging.KtorSimpleLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.net.InetAddress
import javax.jmdns.JmDNS
import javax.jmdns.ServiceEvent
import javax.jmdns.ServiceInfo
import javax.jmdns.ServiceListener

/**
 * mDNS presence, shared by Android and desktop.
 *
 * Two responders, because JmDNS fixes its host name when it is created and we need two
 * different ones:
 *
 *  - **self** — always running, host `tucount-<id>.local`, publishing `_tucount._tcp`
 *    so the other terminal can find this one whatever its role.
 *  - **primary** — only while this terminal is primary, host `tucount.local`, which is
 *    the single address the POS app is configured with.
 *
 * [onNetworkStart] and [onNetworkStop] exist for Android, which silently drops multicast
 * unless a lock is held.
 */
open class JmdnsServiceAdvertiser : ServiceAdvertiser {

    private val logger = KtorSimpleLogger("mDNS")

    private var selfResponder: JmDNS? = null
    private var primaryResponder: JmDNS? = null

    private var announcedSelf: Triple<String, String, Int>? = null
    private var announcedPrimary: Pair<String, Int>? = null
    private var ownNodeId: String? = null

    private val _peers = MutableStateFlow<List<DiscoveredPeer>>(emptyList())
    override val peers: StateFlow<List<DiscoveredPeer>> = _peers.asStateFlow()

    /** Android acquires its multicast lock here. */
    protected open fun onNetworkStart() = Unit

    /** Released once nothing is listening any more. */
    protected open fun onNetworkStop() = Unit

    override suspend fun announceSelf(nodeId: String, ipv4: String, port: Int) {
        val wanted = Triple(nodeId, ipv4, port)
        if (announcedSelf == wanted) return

        withContext(Dispatchers.IO) {
            closeSelf()
            runCatching {
                onNetworkStart()
                val short = nodeId.take(8)
                val responder = JmDNS.create(InetAddress.getByName(ipv4), "$HOST_PREFIX$short")
                responder.registerService(
                    ServiceInfo.create(
                        ServiceAdvertiser.SERVICE_TYPE,
                        short,
                        port,
                        0,
                        0,
                        mapOf("nodeId" to nodeId),
                    )
                )
                responder.addServiceListener(ServiceAdvertiser.SERVICE_TYPE, peerListener)
                selfResponder = responder
                ownNodeId = nodeId
                announcedSelf = wanted
                logger.info("Announced self as $short at $ipv4:$port; browsing for peers")
            }.onFailure {
                logger.warn("Could not announce self: ${it.message}")
                onNetworkStop()
            }
        }
    }

    override suspend fun claimPrimaryName(ipv4: String, port: Int) {
        val wanted = ipv4 to port
        if (announcedPrimary == wanted) return

        withContext(Dispatchers.IO) {
            releasePrimaryName()
            runCatching {
                val responder =
                    JmDNS.create(InetAddress.getByName(ipv4), ServiceAdvertiser.HOST_NAME)
                responder.registerService(
                    ServiceInfo.create("_http._tcp.local.", "TuCount", port, "role=primary")
                )
                primaryResponder = responder
                announcedPrimary = wanted
                logger.info("Claimed ${ServiceAdvertiser.HOST_NAME}.local -> $ipv4:$port")
            }.onFailure { logger.warn("Could not claim the primary name: ${it.message}") }
        }
    }

    override fun releasePrimaryName() {
        val responder = primaryResponder ?: return
        runCatching {
            responder.unregisterAllServices()
            responder.close()
        }
        primaryResponder = null
        announcedPrimary = null
        logger.info("Released ${ServiceAdvertiser.HOST_NAME}.local")
    }

    override fun shutdown() {
        releasePrimaryName()
        closeSelf()
        onNetworkStop()
    }

    private fun closeSelf() {
        selfResponder?.let { responder ->
            runCatching {
                responder.removeServiceListener(ServiceAdvertiser.SERVICE_TYPE, peerListener)
                responder.unregisterAllServices()
                responder.close()
            }
        }
        selfResponder = null
        announcedSelf = null
        _peers.value = emptyList()
    }

    private val peerListener = object : ServiceListener {

        override fun serviceAdded(event: ServiceEvent) {
            // Resolution is asynchronous; this only asks for the detail.
            selfResponder?.requestServiceInfo(event.type, event.name, 1_000)
        }

        override fun serviceResolved(event: ServiceEvent) {
            val info = event.info ?: return
            val nodeId = info.getPropertyString("nodeId") ?: return
            if (nodeId == ownNodeId) return // our own announcement

            val address = info.inet4Addresses.firstOrNull()?.hostAddress ?: return
            val peer = DiscoveredPeer(nodeId, address, info.port)

            // Drop anything at the same address as well as the same id. A terminal that
            // was reinstalled comes back with a new node id, and mDNS does not always
            // retract the old announcement — which left the operator choosing between
            // two entries for one physical terminal, one of them dead.
            _peers.value = _peers.value
                .filterNot { it.nodeId == nodeId || it.hostPort == peer.hostPort } + peer
            logger.info("Discovered peer ${nodeId.take(8)} at ${peer.hostPort}")
        }

        override fun serviceRemoved(event: ServiceEvent) {
            val nodeId = event.info?.getPropertyString("nodeId") ?: return
            _peers.value = _peers.value.filterNot { it.nodeId == nodeId }
            logger.info("Peer ${nodeId.take(8)} went away")
        }
    }

    private companion object {
        const val HOST_PREFIX = "tucount-"
    }
}
