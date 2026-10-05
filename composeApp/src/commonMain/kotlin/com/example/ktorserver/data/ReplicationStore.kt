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

        // Pointing at a DIFFERENT peer means a peer that has never seen any of our
        // changes. Keeping the old ack counter would silently skip everything before
        // it, leaving a brand-new secondary permanently missing the back catalogue.
        val peerChanged = peerAddress != null && peerAddress != state.peerAddress

        val next = state.copy(
            role = role,
            epoch = epoch,
            peerAddress = peerAddress ?: state.peerAddress,
            peerNodeId = if (peerChanged) null else state.peerNodeId,
            peerAckedSeq = if (peerChanged) 0L else state.peerAckedSeq,
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

    /**
     * Full peer observation: epoch **and** role.
     *
     * [observeEpoch] alone cannot break a tie. If two nodes end up claiming primary at
     * the same epoch — which a careless role change can cause — neither sees an epoch
     * *higher* than its own, so neither stands down and the split never heals. Both
     * terminals then keep taking sales forever.
     *
     * The tie is broken deterministically on node id: the larger id yields. Both sides
     * compute the same answer from the same two values, so exactly one demotes without
     * any negotiation.
     */
    suspend fun observePeer(
        incomingEpoch: Long,
        incomingRole: String?,
        fromNodeId: String?,
    ): ReplicationState {
        val state = observeEpoch(incomingEpoch, fromNodeId)

        val bothClaimPrimary = state.role == NodeRole.PRIMARY &&
            incomingRole == NodeRole.PRIMARY.name &&
            incomingEpoch == state.epoch &&
            fromNodeId != null

        if (bothClaimPrimary && state.nodeId > fromNodeId!!) {
            return demote(state.epoch)
        }
        return state
    }

    /** True when this node may accept writes. */
    suspend fun isPrimary(): Boolean = current().role == NodeRole.PRIMARY

    /**
     * Records how far this node has consumed the peer's stream, so a reconnect resumes
     * instead of replaying from the beginning.
     */
    suspend fun recordApplied(peerNodeId: String, seq: Long) = dao.recordApplied(peerNodeId, seq)

    /**
     * Records how far the peer has confirmed storing OUR changes.
     *
     * Everything above this is unreplicated and would be lost if we failed right now.
     */
    suspend fun recordPeerAck(seq: Long) = dao.recordPeerAck(seq)
}
