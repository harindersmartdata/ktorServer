package com.example.ktorserver.ui

import com.example.ktorserver.data.ReplicationState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** Everything the screen needs to show replication, bundled so [App] keeps one parameter. */
class ReplicationUi(
    val state: Flow<ReplicationState?>,
    val peerReachable: StateFlow<Boolean>,
    val primaryUnavailable: StateFlow<Boolean>,
    /** False only while a restarted node is still checking its epoch with the peer. */
    val startupConfirmed: StateFlow<Boolean>,
    /** Promotion is a human decision, so this is only ever called from a button. */
    val onPromote: () -> Unit,
)
