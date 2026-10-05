package com.example.ktorserver.server

import com.example.ktorserver.data.NodeRole
import com.example.ktorserver.data.PromotionDao
import com.example.ktorserver.data.PromotionRecord
import com.example.ktorserver.data.ReplicationState
import com.example.ktorserver.data.ReplicationStore
import io.ktor.util.logging.KtorSimpleLogger
import kotlinx.datetime.Clock

/**
 * The single path by which a node becomes primary.
 *
 * Both the HTTP endpoint and the on-screen button go through here. They must: an
 * operator tapping "make this terminal primary" has to meet exactly the same safety
 * check as a script calling the API, or the check is decoration.
 */
class PromotionCoordinator(
    private val store: ReplicationStore,
    private val promotions: PromotionDao,
    private val monitor: PeerMonitor?,
) {

    private val logger = KtorSimpleLogger("Promotion")

    sealed interface Outcome {
        data class Promoted(val state: ReplicationState, val forced: Boolean) : Outcome

        /** The peer is alive and still claims primary. Not a failover — a second primary. */
        data class Refused(
            val peerRole: String,
            val peerEpoch: Long,
            val ourEpoch: Long,
        ) : Outcome
    }

    suspend fun promote(force: Boolean = false, reason: String? = null): Outcome {
        val before = store.current()
        val peer = monitor?.probe()

        val peerStillPrimary = peer != null &&
            peer.role == NodeRole.PRIMARY.name &&
            peer.epoch >= before.epoch

        if (peerStillPrimary && !force) {
            logger.warn("Promotion refused: peer still primary at epoch ${peer!!.epoch}")
            return Outcome.Refused(peer.role, peer.epoch, before.epoch)
        }

        val state = store.promote()

        promotions.record(
            PromotionRecord(
                nodeId = state.nodeId,
                epoch = state.epoch,
                at = Clock.System.now().toEpochMilliseconds(),
                forced = force && peerStillPrimary,
                peerReachable = peer != null,
                peerRoleAtPromotion = peer?.role,
                peerEpochAtPromotion = peer?.epoch,
                reason = reason,
            )
        )

        logger.warn(
            "PROMOTED to epoch ${state.epoch} (node ${state.nodeId}) " +
                "forced=$force peerReachable=${peer != null}"
        )

        // Announce the new epoch at once. Without this the old primary keeps taking
        // sales until its own next heartbeat; with it the overlap is one round trip.
        monitor?.probe()

        return Outcome.Promoted(state, forced = force && peerStillPrimary)
    }
}
