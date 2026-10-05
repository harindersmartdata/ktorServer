package com.example.ktorserver.server

import com.example.ktorserver.data.ApplyChangesRequest
import com.example.ktorserver.data.ApplyChangesResponse
import com.example.ktorserver.data.ChangeLogDao
import com.example.ktorserver.data.NodeRole
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
import kotlinx.serialization.json.Json

/** Ships locally-originated changes to the peer. */
interface ChangeReplicator {
    /** Changes written here that the peer has not confirmed. */
    val pending: StateFlow<Long>

    /** False once a push has failed, true again after a successful one. */
    val peerReachable: StateFlow<Boolean>

    /**
     * Pushes everything outstanding and waits for the peer to confirm.
     *
     * Returns true only if the peer stored it. Returns false when the peer is
     * unreachable — the caller carries on regardless, per the configured behaviour.
     */
    suspend fun push(): Boolean

    /**
     * Pushes our unacknowledged changes even though we are no longer primary.
     *
     * Used to hand orphans to the node that superseded us. Separate from [push] because
     * it deliberately ignores the role check that normally stops a secondary writing.
     */
    suspend fun handOver(): Boolean

    fun close()
}

/** Used when no peer is configured; every write is simply local. */
object NoReplication : ChangeReplicator {
    override val pending = MutableStateFlow(0L).asStateFlow()
    override val peerReachable = MutableStateFlow(true).asStateFlow()
    override suspend fun push(): Boolean = true
    override suspend fun handOver(): Boolean = true
    override fun close() = Unit
}

/**
 * Synchronous replication over HTTP, with the agreed degradation.
 *
 * A write waits up to [syncTimeoutMillis] for the secondary to confirm. If the
 * secondary is slow or down the write is NOT rolled back and NOT blocked: it stays in
 * change_log and is picked up by the catch-up loop when the peer returns.
 *
 * The consequence, recorded deliberately: the no-data-loss guarantee holds only while
 * the secondary is healthy. Whenever it is not, this is async replication, and a
 * failover during that window loses whatever had not been acknowledged. The alternative
 * -- refusing sales when the secondary is down -- was rejected, because a dead
 * secondary must not be able to stop the till.
 */
class HttpChangeReplicator(
    private val store: ReplicationStore,
    private val changeLog: ChangeLogDao,
    private val syncTimeoutMillis: Long = 2_000,
    private val catchUpIntervalMillis: Long = 5_000,
) : ChangeReplicator {

    private val logger = KtorSimpleLogger("Replicator")

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val client = HttpClient(CIO) {
        install(ContentNegotiation) { json(json) }
        install(HttpTimeout) {
            requestTimeoutMillis = syncTimeoutMillis
            connectTimeoutMillis = syncTimeoutMillis
            socketTimeoutMillis = syncTimeoutMillis
        }
        expectSuccess = false
    }

    private val _pending = MutableStateFlow(0L)
    override val pending: StateFlow<Long> = _pending.asStateFlow()

    private val _peerReachable = MutableStateFlow(true)
    override val peerReachable: StateFlow<Boolean> = _peerReachable.asStateFlow()

    override suspend fun push(): Boolean = send(requirePrimary = true)

    override suspend fun handOver(): Boolean = send(requirePrimary = false)

    private suspend fun send(requirePrimary: Boolean): Boolean {
        val state = store.current()
        val peer = state.peerAddress ?: return true
        if (requirePrimary && state.role != NodeRole.PRIMARY) return true

        val entries = changeLog.since(state.nodeId, state.peerAckedSeq)
        if (entries.isEmpty()) {
            _pending.value = 0
            return true
        }

        return try {
            val response: ApplyChangesResponse = client.post("http://$peer/replication/apply") {
                contentType(ContentType.Application.Json)
                setBody(ApplyChangesRequest(state.nodeId, state.epoch, entries))
            }.body()

            // The peer's answer carries its epoch. If it is ahead of us we have been
            // superseded and must stop writing — the ack path doubles as a fencing check.
            val after = store.observeEpoch(response.epoch, null)
            if (requirePrimary && after.role != NodeRole.PRIMARY) {
                logger.warn("Peer is at epoch ${response.epoch}; stood down")
                _peerReachable.value = true
                return false
            }

            if (response.accepted) {
                store.recordPeerAck(response.appliedThroughSeq)
                _pending.value = changeLog.pendingSince(state.nodeId, response.appliedThroughSeq)
                _peerReachable.value = true
                true
            } else {
                logger.warn("Peer rejected batch: ${response.reason}")
                _peerReachable.value = true
                false
            }
        } catch (cause: Exception) {
            // Agreed behaviour: do not block the till. The entries stay in change_log
            // and the catch-up loop retries.
            _pending.value = changeLog.pendingSince(state.nodeId, state.peerAckedSeq)
            _peerReachable.value = false
            if (requirePrimary) {
                // Routine while the peer is down; noisy if logged loudly.
                logger.debug("Peer unreachable (${cause::class.simpleName}); queued ${_pending.value}")
            } else {
                // An operator asked for this one, so a failure must be explainable.
                logger.warn("Handover failed: ${cause::class.simpleName}: ${cause.message}")
            }
            false
        }
    }

    /** Drains the backlog once the peer is reachable again. */
    fun startCatchUp(scope: CoroutineScope) {
        scope.launch {
            while (isActive) {
                delay(catchUpIntervalMillis)
                val state = store.current()
                if (state.role == NodeRole.PRIMARY && state.peerAddress != null) {
                    push()
                }
            }
        }
    }

    override fun close() = client.close()
}
