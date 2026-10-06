package com.example.ktorserver.server

import com.example.ktorserver.data.NodeRole
import com.example.ktorserver.data.ReplicationStore
import com.example.ktorserver.security.SecretsStore
import io.ktor.util.logging.KtorSimpleLogger

/**
 * Turns "pair this terminal with that one" into a single action.
 *
 * Doing it by hand meant setting a token, a role and a peer address separately, in the
 * right order, through three different calls — which is three chances to leave a till
 * half-configured and no way for the person doing it to tell.
 */
class PairingCoordinator(
    private val store: ReplicationStore,
    private val secrets: SecretsStore,
) {

    private val logger = KtorSimpleLogger("Pairing")

    sealed interface Result {
        data object Paired : Result
        data class Rejected(val reason: String) : Result
    }

    /**
     * Joins this terminal to [peer] as its secondary.
     *
     * [code] is what is printed on the other terminal's screen. Both must end up holding
     * the same value or every replication call is refused as unauthorised, which is the
     * single most likely thing to be wrong in a fresh install.
     */
    suspend fun pairAsSecondary(peer: DiscoveredPeer, code: String): Result {
        val normalised = SecretsStore.normalise(code)
        if (normalised.length < SecretsStore.MIN_LENGTH) {
            return Result.Rejected("That code looks too short — check the other terminal's screen.")
        }

        return runCatching {
            secrets.setNodeToken(normalised)
            store.configure(NodeRole.SECONDARY, peer.hostPort)
            store.rememberPeerNode(peer.nodeId)
            logger.warn("Paired as secondary to ${peer.nodeId.take(8)} at ${peer.hostPort}")
            Result.Paired
        }.getOrElse { Result.Rejected(it.message ?: "Could not pair with that terminal.") }
    }

    /** Makes this terminal the primary of the pair, keeping any peer already known. */
    suspend fun makePrimary(): Result = runCatching {
        store.configure(NodeRole.PRIMARY, null)
        Result.Paired
    }.getOrElse { Result.Rejected(it.message ?: "Could not become the primary.") }
}
