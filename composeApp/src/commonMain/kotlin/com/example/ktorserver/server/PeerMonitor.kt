package com.example.ktorserver.server

import com.example.ktorserver.data.HeartbeatRequest
import com.example.ktorserver.data.NodeRole
import com.example.ktorserver.data.ReplicationStatus
import com.example.ktorserver.data.ReplicationState
import com.example.ktorserver.data.ReplicationStore
import com.example.ktorserver.util.localIpv4Address
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.post
import io.ktor.client.request.header
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.util.logging.KtorSimpleLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import kotlinx.serialization.json.Json

/**
 * Keeps each node's picture of the other one current.
 *
 * Both nodes run this and both call the peer's heartbeat endpoint, so the epoch travels
 * in both directions on one request. The reply is the peer's own status, which means a
 * single round trip tells us it is alive, what role it thinks it has, and what epoch it
 * is on.
 *
 * Liveness is **probed, not assumed**. That matters most on Android, where OEM battery
 * management kills foreground services: a secondary whose process has been reaped looks
 * exactly like a healthy one until somebody asks it a question.
 */
class PeerMonitor(
    private val store: ReplicationStore,
    private val nodeToken: (suspend () -> String?)? = null,
    /** Claims the fixed name while this terminal is primary. */
    private val advertiser: ServiceAdvertiser? = null,
    private val servicePort: Int = KtorServer.DEFAULT_PORT,
    private val heartbeatIntervalMillis: Long = 2_000,
    private val failureThresholdMillis: Long = 10_000,
    private val startupGraceMillis: Long = 5_000,
) {

    private val logger = KtorSimpleLogger("PeerMonitor")

    private val client = HttpClient(CIO) {
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        install(HttpTimeout) {
            requestTimeoutMillis = heartbeatIntervalMillis
            connectTimeoutMillis = heartbeatIntervalMillis
            socketTimeoutMillis = heartbeatIntervalMillis
        }
        expectSuccess = false
    }

    private val _peerReachable = MutableStateFlow(false)
    val peerReachable: StateFlow<Boolean> = _peerReachable.asStateFlow()

    private val _lastContactAt = MutableStateFlow<Long?>(null)
    val lastContactAt: StateFlow<Long?> = _lastContactAt.asStateFlow()

    private val _peerRole = MutableStateFlow<String?>(null)
    val peerRole: StateFlow<String?> = _peerRole.asStateFlow()

    /**
     * Why the last probe failed, or null when the peer is answering.
     *
     * Without this, a wrong token, a blocked network and a terminal that is simply
     * switched off all look identical from the UI — "peer not responding" — and an
     * operator has no way to tell which problem they are actually looking at.
     */
    private val _peerError = MutableStateFlow<String?>(null)
    val peerError: StateFlow<String?> = _peerError.asStateFlow()

    /**
     * The signal the operator acts on: this node is a secondary and the primary has
     * been silent for longer than the failure threshold.
     *
     * Deliberately NOT a trigger for automatic promotion. A network partition and a
     * dead primary look identical from here, and promoting on a guess is how both
     * terminals end up taking sales at once.
     */
    private val _primaryUnavailable = MutableStateFlow(false)
    val primaryUnavailable: StateFlow<Boolean> = _primaryUnavailable.asStateFlow()

    /**
     * Neither terminal is primary, and both are up.
     *
     * A quiet way to lose a shop: every write is refused with 421 and the redirect
     * points at a terminal that refuses it too, so terminals fail with nowhere to go while
     * both screens look healthy. It happened here after a run of forced promotions left
     * both sides demoted, and nothing said so. Reachability is required before raising
     * it, because a peer that is merely unreachable might well be primary.
     */
    private val _noPrimaryAnywhere = MutableStateFlow(false)
    val noPrimaryAnywhere: StateFlow<Boolean> = _noPrimaryAnywhere.asStateFlow()

    /**
     * Whether this node has finished its post-startup epoch check.
     *
     * A node that restarts believing it is primary must not take sales before checking
     * whether someone was promoted while it was gone — the window step 2 left open. It
     * latches true once the peer has answered, or once the grace period expires without
     * contact, and never goes false again.
     *
     * A **latch**, deliberately, not a recomputed condition. Recomputing it each tick
     * made a freshly promoted node reject writes until the next heartbeat, which would
     * stop a terminal for seconds at exactly the worst moment. Role is read live from the
     * database at request time; this only answers "have we finished checking yet".
     *
     * The grace fallback is a compromise: if the peer is unreachable we genuinely
     * cannot know, and refusing to trade until it answers would let a dead secondary
     * keep the terminal shut.
     */
    private val _startupConfirmed = MutableStateFlow(false)
    val startupConfirmed: StateFlow<Boolean> = _startupConfirmed.asStateFlow()

    private var startedAt = 0L
    private var everContacted = false

    fun start(scope: CoroutineScope) {
        startedAt = Clock.System.now().toEpochMilliseconds()
        scope.launch {
            while (isActive) {
                tick()
                delay(heartbeatIntervalMillis)
            }
        }
    }

    private suspend fun tick() {
        val state = store.current()

        // Done before anything peer-related: a primary with no peer configured still
        // owns the name, and a demoted one must give it up even if the peer is silent.
        syncAdvertisement(state)
        followDiscoveredPeer(state)

        val peer = store.current().peerAddress

        if (peer == null) {
            // Standalone: no peer to contradict us, so nothing to wait for.
            _startupConfirmed.value = true
            _primaryUnavailable.value = false
            return
        }

        val now = Clock.System.now().toEpochMilliseconds()

        try {
            val status: ReplicationStatus =
                client.post("http://$peer/replication/heartbeat") {
                    nodeToken?.invoke()?.let { header(HttpHeaders.Authorization, "Bearer $it") }
                    contentType(ContentType.Application.Json)
                    setBody(HeartbeatRequest(state.nodeId, state.epoch, state.role.name))
                }.body()

            _lastContactAt.value = now
            _peerReachable.value = true
            _peerError.value = null
            _peerRole.value = status.role
            everContacted = true

            // The peer also reports how much of our stream it actually holds. If that is
            // less than we recorded, it was reset or replaced and we must rewind.
            val before = store.current().peerAckedSeq
            if (store.reconcilePeerAck(status.lastAppliedSeq)) {
                if (status.lastAppliedSeq < before) {
                    logger.warn(
                        "Peer holds only up to seq ${status.lastAppliedSeq} of our " +
                            "stream; rewinding and resending"
                    )
                }
            }

            // The reply carries the peer's epoch. If it outranks ours we stand down here,
            // before the write guard is ever consulted.
            val after = store.observePeer(status.epoch, status.role, status.nodeId)
            if (state.role == NodeRole.PRIMARY && after.role != NodeRole.PRIMARY) {
                logger.warn("Stood down: peer is at epoch ${status.epoch}")
            }
        } catch (cause: Exception) {
            _peerReachable.value = false
            _peerError.value = "${cause::class.simpleName}: ${cause.message?.take(160)}"
            logger.debug("Peer $peer unreachable (${cause::class.simpleName}): ${cause.message}")
        }

        val current = store.current()
        val silentFor = _lastContactAt.value?.let { now - it } ?: (now - startedAt)

        _primaryUnavailable.value =
            current.role == NodeRole.SECONDARY && silentFor > failureThresholdMillis

        _noPrimaryAnywhere.value = current.role != NodeRole.PRIMARY &&
            _peerReachable.value &&
            _peerRole.value != null &&
            _peerRole.value != NodeRole.PRIMARY.name

        if (everContacted || now - startedAt > startupGraceMillis) {
            _startupConfirmed.value = true
        }
    }

    /**
     * One-shot exchange with the peer, outside the polling loop.
     *
     * Used twice around a promotion: beforehand to find out whether the old primary is
     * actually dead, and immediately afterwards to announce the new epoch so it stands
     * down at once instead of waiting for its own next heartbeat. That second call is
     * what keeps the two-primary window to a round trip rather than a poll interval.
     *
     * Returns null when the peer does not answer, which is the normal case during a
     * real failover.
     */
    suspend fun probe(): ReplicationStatus? {
        val state = store.current()
        val peer = state.peerAddress ?: return null
        return try {
            client.post("http://$peer/replication/heartbeat") {
                nodeToken?.invoke()?.let { header(HttpHeaders.Authorization, "Bearer $it") }
                contentType(ContentType.Application.Json)
                setBody(HeartbeatRequest(state.nodeId, state.epoch, state.role.name))
            }.body<ReplicationStatus>()
        } catch (cause: Exception) {
            logger.debug("Probe of $peer failed (${cause::class.simpleName})")
            null
        }
    }

    /**
     * Keeps the advertised name attached to the primary.
     *
     * Re-asserted every tick rather than only on transitions, so a terminal whose IP
     * changed under DHCP republishes under the new address without needing a restart.
     */
    private suspend fun syncAdvertisement(state: ReplicationState) {
        val mdns = advertiser ?: return
        val address = localIpv4Address() ?: return

        // Announced whatever the role: the other terminal has to be able to find this
        // one in order to replicate to it, not only when it happens to be primary.
        mdns.announceSelf(state.nodeId, address, servicePort)

        // The client-facing name belongs to the primary alone.
        if (state.role == NodeRole.PRIMARY) {
            mdns.claimPrimaryName(address, servicePort)
        } else {
            mdns.releasePrimaryName()
        }
    }

    /**
     * Keeps the configured peer address pointing at where the peer actually is.
     *
     * DHCP moved both test devices within a single afternoon, and a configured address
     * silently stops working the moment that happens. Discovery is matched on node id,
     * so this follows the terminal we are paired with rather than whatever else happens
     * to be on the network.
     */
    private suspend fun followDiscoveredPeer(state: ReplicationState) {
        val mdns = advertiser ?: return
        val seen = mdns.peers.value
        if (seen.isEmpty()) return

        val match = when {
            // Once we know who our peer is, only ever follow that one.
            state.peerNodeId != null -> seen.firstOrNull { it.nodeId == state.peerNodeId }
            // Before the first contact a single visible terminal is unambiguous; more
            // than one and an operator has to say which, since guessing could pair the
            // wrong pair of terminals.
            seen.size == 1 -> seen.single()
            else -> null
        } ?: return

        if (match.hostPort != state.peerAddress) {
            store.followPeerTo(match.hostPort)
            logger.info("Peer ${match.nodeId.take(8)} is now at ${match.hostPort}")
        }
    }

    fun close() {
        advertiser?.shutdown()
        client.close()
    }
}
