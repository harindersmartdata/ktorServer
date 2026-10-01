package com.example.ktorserver.data

import kotlinx.coroutines.flow.Flow
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * This server's replication identity and role.
 *
 * The node id is generated once, on first open, and persisted. It must survive restarts:
 * it is the author stamp on every change this node originates, and a node that forgot
 * its own id would restart its sequence from 1 and overwrite its own history on the peer.
 */
class ReplicationStore(private val dao: ReplicationStateDao) {

    @OptIn(ExperimentalUuidApi::class)
    suspend fun bootstrap(): ReplicationState {
        dao.get()?.let { return it }
        val fresh = ReplicationState(
            nodeId = Uuid.random().toString(),
            role = NodeRole.UNCONFIGURED,
            epoch = 0L,
        )
        dao.put(fresh)
        return fresh
    }

    suspend fun current(): ReplicationState = dao.get() ?: bootstrap()

    fun observe(): Flow<ReplicationState?> = dao.observe()

    /**
     * Records how far this node has consumed the peer's stream, so a reconnect resumes
     * instead of replaying from the beginning.
     */
    suspend fun recordApplied(peerNodeId: String, seq: Long) = dao.recordApplied(peerNodeId, seq)
}
