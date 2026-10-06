package com.example.ktorserver.server

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Another terminal seen on the network. */
data class DiscoveredPeer(
    val nodeId: String,
    val address: String,
    val port: Int,
) {
    val hostPort: String get() = "$address:$port"
}

/**
 * This terminal's presence on the local network, and what it can see of the other.
 *
 * Two jobs, both solving an address that will not stay still:
 *
 *  - **One constant URL for the POS app.** Whichever terminal is primary claims
 *    `tucount.local`, so the app is configured once and keeps working after a failover.
 *  - **Peer discovery.** Every terminal announces itself whatever its role, so the two
 *    find each other by node id rather than by an address DHCP keeps reassigning. Both
 *    devices moved address during a single afternoon of testing; a configured IP would
 *    have broken replication each time.
 */
interface ServiceAdvertiser {

    /** Announce this terminal, whatever its role, so the other can find it. */
    suspend fun announceSelf(nodeId: String, ipv4: String, port: Int)

    /** Claim the client-facing name. Only a primary may, or clients would see two. */
    suspend fun claimPrimaryName(ipv4: String, port: Int)

    /** Give up the client-facing name on demotion. */
    fun releasePrimaryName()

    /** Terminals currently visible, excluding this one. */
    val peers: StateFlow<List<DiscoveredPeer>>

    fun shutdown()

    companion object {
        /** The name tills are configured with. Changing it means reconfiguring them. */
        const val HOST_NAME = "tucount"

        /** mDNS service type both terminals announce themselves under. */
        const val SERVICE_TYPE = "_tucount._tcp.local."

        /** What an operator puts in the POS app. */
        fun url(port: Int): String = "http://$HOST_NAME.local:$port"
    }
}

/** Used where mDNS is unavailable; configured peer addresses still work. */
object NoAdvertising : ServiceAdvertiser {
    override suspend fun announceSelf(nodeId: String, ipv4: String, port: Int) = Unit
    override suspend fun claimPrimaryName(ipv4: String, port: Int) = Unit
    override fun releasePrimaryName() = Unit
    override val peers: StateFlow<List<DiscoveredPeer>> =
        MutableStateFlow<List<DiscoveredPeer>>(emptyList()).asStateFlow()
    override fun shutdown() = Unit
}
