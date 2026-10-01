package com.example.ktorserver.data

import kotlinx.coroutines.flow.Flow
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * This server's replication identity, role and epoch.
 *
 * The node id is generated once, on first open, and persisted. It must survive restarts:
 * it is the author stamp on every change this node originates, and a node that forgot
 * its own id would restart its sequence from 1 and overwrite its own history on the peer.
 *
 * The **epoch** is the fencing token. It increases on every promotion and never goes
 * backwards. It is what stops two nodes both taking sales during a network split: a
 * node that learns of a higher epoch knows it has been superseded and demotes itself.
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
     * Sets this node's role explicitly, as the operator chose it during setup.
     *
     * Configuring a node as PRIMARY for the first time moves it to epoch 1 so that
     * changes are never written under epoch 0, which means "nobody has claimed primary
     * yet" and must not appear in the log as an authority.
     */
    suspend fun configure(role: NodeRole, peerAddress: String?): ReplicationState {
        val state = current()
        val epoch = if (role == NodeRole.PRIMARY && state.epoch == 0L) 1L else state.epoch
        val next = state.copy(
            role = role,
            epoch = epoch,
            peerAddress = peerAddress ?: state.peerAddress,
        )
        dao.put(next)
        return next
    }

    /**
     * Promotes this node to primary, claiming a new epoch.
     *
     * The epoch is bumped past anything this node has seen, so every write it now
     * accepts is stamped with an authority strictly greater than the old primary's.
     * When the old primary comes back it sees the higher epoch and stands down.
     */
    suspend fun promote(): ReplicationState {
        val state = current()
        val next = state.copy(role = NodeRole.PRIMARY, epoch = state.epoch + 1)
        dao.put(next)
        return next
    }

    /**
     * Steps down to secondary, adopting [atEpoch] if it is ahead of ours.
     *
     * Called when this node discovers it has been superseded. Adopting the epoch matters:
     * a node that demoted but kept its old epoch would promote itself back into a
     * conflicting authority next time.
     */
    suspend fun demote(atEpoch: Long): ReplicationState {
        val state = current()
        val next = state.copy(
            role = NodeRole.SECONDARY,
            epoch = maxOf(state.epoch, atEpoch),
        )
        dao.put(next)
        return next
    }

    /**
     * Fencing check, applied to every epoch this node learns about from a peer.
     *
     * A higher epoch means someone else was promoted while we were away. Whatever we
     * believe about ourselves, we are no longer the authority and must stop accepting
     * writes immediately — before any further sale is taken against stale state.
     *
     * Returns the resulting state, demoted if we were superseded.
     */
    suspend fun observeEpoch(incomingEpoch: Long, fromNodeId: String? = null): ReplicationState {
        val state = current()
        val withPeer =
            if (fromNodeId != null && state.peerNodeId != fromNodeId) {
                state.copy(peerNodeId = fromNodeId).also { dao.put(it) }
            } else {
                state
            }

        if (incomingEpoch <= withPeer.epoch) return withPeer
        return demote(incomingEpoch)
    }

    /** True when this node may accept writes. */
    suspend fun isPrimary(): Boolean = current().role == NodeRole.PRIMARY

    /**
     * Records how far this node has consumed the peer's stream, so a reconnect resumes
     * instead of replaying from the beginning.
     */
    suspend fun recordApplied(peerNodeId: String, seq: Long) = dao.recordApplied(peerNodeId, seq)
}
