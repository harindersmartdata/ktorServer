package com.example.ktorserver.ui

import com.example.ktorserver.data.NodeRole

/**
 * Plain-language names for things the screen used to state in protocol terms.
 *
 * Shop staff are the people who will read this screen at eight in the morning when a
 * till will not ring up a sale. "Replication SECONDARY · epoch 7" tells them nothing
 * they can act on; "Backup till — copying sales from the main till" does.
 *
 * The technical wording is not deleted, only moved: it is still on screen under
 * Technical details, where it is what an engineer needs when something is genuinely wrong.
 */
object Wording {

    fun role(role: NodeRole): String = when (role) {
        NodeRole.PRIMARY -> "Main till"
        NodeRole.SECONDARY -> "Backup till"
        NodeRole.UNCONFIGURED -> "Not set up"
    }

    /** One sentence saying whether this terminal is doing its job. */
    fun headline(
        running: Boolean,
        role: NodeRole,
        noPrimaryAnywhere: Boolean,
        primaryUnavailable: Boolean,
    ): String = when {
        !running -> "Stopped"
        noPrimaryAnywhere -> "No till is taking sales"
        primaryUnavailable -> "The main till is not responding"
        role == NodeRole.PRIMARY -> "Taking sales"
        role == NodeRole.SECONDARY -> "Backing up the main till"
        else -> "Not set up yet"
    }

    /** What, if anything, the person in front of it should do. */
    fun guidance(
        running: Boolean,
        role: NodeRole,
        noPrimaryAnywhere: Boolean,
        primaryUnavailable: Boolean,
        peerReachable: Boolean,
        unreplicated: Long,
    ): String? = when {
        !running -> "This till is not serving. Tap Start below."
        noPrimaryAnywhere -> "Both tills are running but neither is the main one, so " +
            "sales will be refused. Make one of them the main till."
        primaryUnavailable -> "If the main till really is switched off or broken, make " +
            "this one the main till. If it is still working, leave this alone."
        role == NodeRole.UNCONFIGURED -> "Choose whether this is the main till or a " +
            "backup for one that already exists."
        role == NodeRole.PRIMARY && !peerReachable ->
            "No backup is connected. Sales are safe here, but there is no second copy."
        role == NodeRole.PRIMARY && unreplicated > 0 ->
            "$unreplicated sale(s) not copied to the backup yet."
        else -> null
    }

    /** State of the other terminal, in a line. */
    fun peerSummary(
        role: NodeRole,
        peerReachable: Boolean,
        peerPaired: Boolean,
        unreplicated: Long,
    ): String = when {
        !peerPaired -> "No other till connected yet"
        !peerReachable -> "Other till not reachable"
        role == NodeRole.PRIMARY && unreplicated > 0 -> "Backup connected · $unreplicated to copy"
        role == NodeRole.PRIMARY -> "Backup connected and up to date"
        else -> "Main till connected"
    }

    /** True when the headline should read as a problem rather than business as usual. */
    fun isProblem(
        running: Boolean,
        noPrimaryAnywhere: Boolean,
        primaryUnavailable: Boolean,
    ): Boolean = !running || noPrimaryAnywhere || primaryUnavailable
}
