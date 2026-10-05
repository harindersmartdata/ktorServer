package com.example.ktorserver.ui

import com.example.ktorserver.data.ReplicationState
import com.example.ktorserver.server.PromotionCoordinator
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** Everything the screen needs to show replication, bundled so [App] keeps one parameter. */
class ReplicationUi(
    val state: Flow<ReplicationState?>,
    val peerReachable: StateFlow<Boolean>,
    val primaryUnavailable: StateFlow<Boolean>,
    /** False only while a restarted node is still checking its epoch with the peer. */
    val startupConfirmed: StateFlow<Boolean>,
    /**
     * Runs the same coordinator the HTTP endpoint uses, so the button cannot skip the
     * safety check. Returns the outcome so the screen can offer the override when the
     * old primary turns out to still be alive.
     */
    val promote: suspend (force: Boolean) -> PromotionCoordinator.Outcome,
)
