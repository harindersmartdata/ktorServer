package com.example.ktorserver.server

import com.example.ktorserver.data.HeartbeatRequest
import com.example.ktorserver.data.NodeRole
import com.example.ktorserver.data.ReplicationStatus
import com.example.ktorserver.data.ReplicationStore
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
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
     * Whether this node has finished its post-startup epoch check.
     *
     * A node that restarts believing it is primary must not take sales before checking
     * whether someone was promoted while it was gone — the window step 2 left open. It
     * latches true once the peer has answered, or once the grace period expires without
     * contact, and never goes false again.
     *
     * A **latch**, deliberately, not a recomputed condition. Recomputing it each tick
     * made a freshly promoted node reject writes until the next heartbeat, which would
     * stop a till for seconds at exactly the worst moment. Role is read live from the
     * database at request time; this only answers "have we finished checking yet".
     *
     * The grace fallback is a compromise: if the peer is unreachable we genuinely
     * cannot know, and refusing to trade until it answers would let a dead secondary
     * keep the till shut.
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
        val peer = state.peerAddress

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
                    contentType(ContentType.Application.Json)
                    setBody(HeartbeatRequest(state.nodeId, state.epoch, state.role.name))
                }.body()

            _lastContactAt.value = now
            _peerReachable.value = true
            _peerRole.value = status.role
            everContacted = true

            // The reply carries the peer's epoch. If it outranks ours we stand down here,
            // before the write guard is ever consulted.
            val after = store.observePeer(status.epoch, status.role, status.nodeId)
            if (state.role == NodeRole.PRIMARY && after.role != NodeRole.PRIMARY) {
                logger.warn("Stood down: peer is at epoch ${status.epoch}")
            }
        } catch (cause: Exception) {
            _peerReachable.value = false
            logger.debug("Peer $peer unreachable (${cause::class.simpleName})")
        }

        val current = store.current()
        val silentFor = _lastContactAt.value?.let { now - it } ?: (now - startedAt)

        _primaryUnavailable.value =
            current.role == NodeRole.SECONDARY && silentFor > failureThresholdMillis

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
                contentType(ContentType.Application.Json)
                setBody(HeartbeatRequest(state.nodeId, state.epoch, state.role.name))
            }.body<ReplicationStatus>()
        } catch (cause: Exception) {
            logger.debug("Probe of $peer failed (${cause::class.simpleName})")
            null
        }
    }

    fun close() = client.close()
}
