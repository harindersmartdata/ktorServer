package com.example.ktorserver.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/**
 * Row in the `users` table and the JSON shape returned by the API.
 *
 * [id] is a globally unique string, NOT an autoincrement integer. Two servers both
 * assigning `id = 4` to different rows would collide the moment one replicated to the
 * other, so row identity has to be generated independently of any sequence.
 */
@Entity(
    tableName = "users",
    indices = [Index(value = ["email"], unique = true)],
)
@Serializable
data class User(
    @PrimaryKey
    val id: String,
    @ColumnInfo(name = "name")
    val name: String,
    @ColumnInfo(name = "email")
    val email: String,
    @ColumnInfo(name = "createdAt")
    val createdAt: Long,
)

/** Insert, update or delete — the three things replication has to carry. */
enum class ChangeOp { INSERT, UPDATE, DELETE }

/**
 * One row-level change, append-only, in origin order.
 *
 * This is the unit of replication. Nothing in it is `users`-specific: [tableName] and
 * the JSON [payload] mean a new TuCount table replicates without touching this code.
 *
 * Ordering is by ([nodeId], [seq]) — a logical sequence, never wall-clock time, because
 * two terminals' clocks disagree and sales would replay out of order.
 *
 * A DELETE carries a null [payload]; the log entry IS the tombstone. Without it a
 * secondary never learns a row vanished and resurrects it on failover.
 */
@Entity(
    tableName = "change_log",
    primaryKeys = ["nodeId", "seq"],
    indices = [Index(value = ["txId"]), Index(value = ["tableName", "rowId"])],
)
@Serializable
data class ChangeLogEntry(
    /** Which server originated this change. */
    val nodeId: String,
    /** Monotonic per-node counter. Gaps mean lost changes. */
    val seq: Long,
    /** Groups changes written in one transaction so they apply atomically downstream. */
    val txId: String,
    val tableName: String,
    /** Primary key of the affected row, as a string. */
    val rowId: String,
    val op: ChangeOp,
    /** JSON of the row after the change; null for DELETE. */
    val payload: String?,
    /** Fencing token — the primary's epoch when this was written. See ReplicationState. */
    val epoch: Long,
    /** Informational only. Never used for ordering. */
    val createdAt: Long,
)

/**
 * Single-row table holding this server's replication identity and state.
 *
 * [epoch] is the fencing token: it increments on every promotion. A node that sees a
 * higher epoch than its own knows it has been superseded and must stop accepting
 * writes — the guard against two primaries both taking sales during a network split.
 */
@Entity(tableName = "replication_state")
data class ReplicationState(
    @PrimaryKey
    val id: Int = SINGLETON_ID,
    /** Stable identity of this server, generated once and persisted. */
    val nodeId: String,
    val role: NodeRole,
    val epoch: Long,
    /** High-water mark applied from the peer, for resuming a stream. */
    val lastAppliedNodeId: String? = null,
    val lastAppliedSeq: Long = 0L,
    /** Where the peer listens, e.g. "192.168.1.11:8080". Null until configured. */
    val peerAddress: String? = null,
    /** Learned on first contact; lets us tell a client who the real primary is. */
    val peerNodeId: String? = null,
    /**
     * Highest seq of OUR changes the peer has confirmed storing.
     *
     * Everything above this is unreplicated. It is the queue depth after a degraded
     * write, and the resume point when the peer comes back.
     */
    val peerAckedSeq: Long = 0L,
) {
    companion object { const val SINGLETON_ID = 1 }
}

enum class NodeRole { PRIMARY, SECONDARY, UNCONFIGURED }

/**
 * One promotion, recorded permanently.
 *
 * A failover is the moment a shop's data can diverge, so it must leave a trace that
 * outlives the UI: which node claimed which epoch, when, whether the old primary was
 * still answering at the time, and whether a human overrode the safety check. When two
 * terminals later disagree about the day's takings, this is the record that explains why.
 *
 * Keyed on (nodeId, epoch) rather than epoch alone: a correct system never issues the
 * same epoch twice, but the log must still be able to record it if that ever happens.
 */
@Entity(tableName = "promotion_log", primaryKeys = ["nodeId", "epoch"])
@Serializable
data class PromotionRecord(
    val nodeId: String,
    val epoch: Long,
    val at: Long,
    /** True when the operator promoted despite the peer still claiming primary. */
    val forced: Boolean,
    /** Whether the peer answered a probe at the moment of promotion. */
    val peerReachable: Boolean,
    val peerRoleAtPromotion: String? = null,
    val peerEpochAtPromotion: Long? = null,
    val reason: String? = null,
)

// ---- API DTOs ---------------------------------------------------------------

@Serializable
data class CreateUserRequest(val name: String, val email: String)

@Serializable
data class UsersResponse(val count: Int, val users: List<User>)

@Serializable
data class HealthResponse(
    val status: String,
    val uptimeMillis: Long,
    val userCount: Int,
    val serverTime: String,
    val storage: String = "sqlite",
    val nodeId: String? = null,
    val role: String? = null,
    val epoch: Long? = null,
    /** Highest change_log seq this node has originated. Peer-ack tracking is step 3. */
    val localSeq: Long = 0,
)

@Serializable
data class ErrorResponse(val error: String, val details: String? = null)

// ---- Replication control DTOs ----------------------------------------------

@Serializable
data class ReplicationStatus(
    val nodeId: String,
    val role: String,
    val epoch: Long,
    val peerAddress: String? = null,
    val peerNodeId: String? = null,
    /** Highest seq this node originated. */
    val localSeq: Long = 0,
    /** Highest peer seq this node has applied. */
    val lastAppliedSeq: Long = 0,
    /** Highest of OUR seqs the peer has confirmed. */
    val peerAckedSeq: Long = 0,
    /** localSeq - peerAckedSeq: changes at risk if we died right now. */
    val unreplicated: Long = 0,
    /** Live monitor state; null when this node is not running a monitor. */
    val peerReachable: Boolean? = null,
    /** Secondary only: the primary has been silent past the failure threshold. */
    val primaryUnavailable: Boolean? = null,
    /** False while a restarted primary is still confirming nobody outranks it. */
    val writesEnabled: Boolean? = null,
)

/** Body of `POST /replication/role`. */
@Serializable
data class ConfigureRoleRequest(
    val role: String,
    val peerAddress: String? = null,
)

/**
 * Returned with 421 when a write reaches a node that is not primary.
 *
 * Carries the primary's address so a client can retry without a discovery round, and
 * the epoch so a client holding a stale answer can tell it is out of date.
 */
@Serializable
data class NotPrimaryResponse(
    val error: String = "This node is not the primary",
    val role: String,
    val epoch: Long,
    val primaryAddress: String? = null,
)

/**
 * Sent by the primary to the secondary, carrying the epoch it is writing under.
 *
 * This is what keeps the fencing token globally monotonic. A secondary that has never
 * heard the primary's epoch would promote itself to an epoch the old primary already
 * used, and nothing could then tell which of the two is newer.
 */
@Serializable
data class HeartbeatRequest(
    val nodeId: String,
    val epoch: Long,
)

// ---- Replication transport --------------------------------------------------

/**
 * A batch of changes pushed from primary to secondary.
 *
 * [epoch] is the authority the sender claims. A receiver that is already past it
 * rejects the batch rather than letting a superseded primary write to it.
 */
@Serializable
data class ApplyChangesRequest(
    val nodeId: String,
    val epoch: Long,
    val entries: List<ChangeLogEntry>,
)

@Serializable
data class ApplyChangesResponse(
    val accepted: Boolean,
    /** Highest seq from the sender that the receiver now holds. */
    val appliedThroughSeq: Long,
    /** The receiver's epoch, so a stale sender learns it has been superseded. */
    val epoch: Long,
    val reason: String? = null,
)

/** Body of `POST /replication/promote`. */
@Serializable
data class PromoteRequest(
    /**
     * Promote even though the peer is still answering and claiming primary.
     *
     * The safety check exists because promoting a second primary is how a shop ends up
     * with two divergent sets of takings. Overriding it is sometimes correct — a primary
     * that answers health checks but has a wedged database, say — so it is allowed, and
     * recorded.
     */
    val force: Boolean = false,
    val reason: String? = null,
)

/** Returned with 409 when the safety check blocks a promotion. */
@Serializable
data class PromotionRefused(
    val error: String,
    val peerRole: String,
    val peerEpoch: Long,
    val ourEpoch: Long,
    val hint: String = "Pass force=true to promote anyway. Both terminals will accept " +
        "sales until the other one is told to stand down.",
)
