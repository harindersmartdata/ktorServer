package com.example.ktorserver.ui

import com.example.ktorserver.data.ReplicationState
import com.example.ktorserver.server.DiscoveredPeer
import com.example.ktorserver.server.PairingCoordinator
import com.example.ktorserver.server.PromotionCoordinator
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** Everything the screen needs to show replication, bundled so [App] keeps one parameter. */
class ReplicationUi(
    val state: Flow<ReplicationState?>,
    val peerReachable: StateFlow<Boolean>,
    val primaryUnavailable: StateFlow<Boolean>,
    /** Both terminals up, neither primary — nothing is taking sales. */
    val noPrimaryAnywhere: StateFlow<Boolean>,
    /** Sales taken here that the other till has not confirmed yet. */
    val unreplicated: StateFlow<Long>,
    /** False only while a restarted node is still checking its epoch with the peer. */
    val startupConfirmed: StateFlow<Boolean>,
    /**
     * Runs the same coordinator the HTTP endpoint uses, so the button cannot skip the
     * safety check. Returns the outcome so the screen can offer the override when the
     * old primary turns out to still be alive.
     */
    val promote: suspend (force: Boolean, reason: String?) -> PromotionCoordinator.Outcome,
    /** Terminals mDNS has found, so nobody has to type an address. */
    val discovered: StateFlow<List<DiscoveredPeer>>,
    /** Joins this terminal to another as its secondary, in one action. */
    val pairAsSecondary: suspend (DiscoveredPeer, String) -> PairingCoordinator.Result,
    /** Makes this terminal the primary of the pair. */
    val makePrimary: suspend () -> PairingCoordinator.Result,
    /** Turns the token requirement on `/users` on or off. */
    val setRequireAuth: suspend (Boolean) -> Unit,
)
