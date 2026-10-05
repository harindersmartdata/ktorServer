package com.example.ktorserver.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

/** A change plus its already-decoded row, so the transaction body does no JSON work. */
data class PreparedChange(val entry: ChangeLogEntry, val row: User?)

/**
 * Every mutation goes through here.
 *
 * The row change and its change_log entry are written in ONE transaction. If they could
 * drift apart, a crash between the two would leave either a row the secondary never
 * hears about, or a log entry describing a row that does not exist — both silently
 * corrupt the replica. Atomicity here is what makes the log trustworthy.
 *
 * The sequence number is read inside the transaction too, so two concurrent writes
 * cannot be handed the same [ChangeLogEntry.seq].
 */
@Dao
abstract class WriteDao {

    /** Strict: a duplicate email must fail so the route can answer 409. */
    @Insert
    protected abstract suspend fun insertUser(user: User)

    /**
     * Replication upsert: the primary's version wins.
     *
     * REPLACE resolves a clash on the primary key *or* on the unique email index, which
     * is what we want when applying someone else's authoritative change.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract suspend fun upsertUser(user: User)

    @Query("DELETE FROM users WHERE id = :id")
    protected abstract suspend fun deleteUserRow(id: String): Int

    @Insert
    protected abstract suspend fun insertLog(entry: ChangeLogEntry)

    /** Returns -1 when this (nodeId, seq) is already present — i.e. already applied. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insertLogIfNew(entry: ChangeLogEntry): Long

    @Query("SELECT COALESCE(MAX(seq), 0) FROM change_log WHERE nodeId = :nodeId")
    protected abstract suspend fun maxSeq(nodeId: String): Long

    /** Returns the seq assigned to this change. */
    @Transaction
    open suspend fun insertUserLogged(
        user: User,
        nodeId: String,
        epoch: Long,
        txId: String,
        payload: String,
        now: Long,
    ): Long {
        insertUser(user)
        return appendLog(nodeId, epoch, txId, "users", user.id, ChangeOp.INSERT, payload, now)
    }

    /** Returns the seq assigned, or -1 when nothing was deleted. */
    @Transaction
    open suspend fun deleteUserLogged(
        id: String,
        nodeId: String,
        epoch: Long,
        txId: String,
        now: Long,
    ): Long {
        // No log entry when nothing was deleted — the peer must not replay a no-op.
        if (deleteUserRow(id) == 0) return -1L
        return appendLog(nodeId, epoch, txId, "users", id, ChangeOp.DELETE, null, now)
    }

    /**
     * Applies a batch received from the peer, atomically and idempotently.
     *
     * The whole batch is one transaction, so a multi-table TuCount sale either lands
     * complete or not at all — a half-applied order is worse than a missing one.
     *
     * Idempotency comes from the log's (nodeId, seq) primary key: if the entry is
     * already there the insert is ignored and the row change is skipped. A retry after
     * a timeout that actually succeeded is therefore harmless, which is what makes
     * at-least-once delivery safe.
     *
     * Entries keep their originating nodeId and seq, so the log stays a faithful record
     * of who wrote what.
     */
    @Transaction
    open suspend fun applyBatch(changes: List<PreparedChange>): Long {
        var appliedThrough = 0L
        for (change in changes) {
            val entry = change.entry
            if (insertLogIfNew(entry) != -1L) {
                when (entry.op) {
                    ChangeOp.INSERT, ChangeOp.UPDATE -> change.row?.let { upsertUser(it) }
                    ChangeOp.DELETE -> deleteUserRow(entry.rowId)
                }
            }
            if (entry.seq > appliedThrough) appliedThrough = entry.seq
        }
        return appliedThrough
    }

    /**
     * Writes a log entry for a row that already exists, without touching the row.
     *
     * Only for backfilling pre-replication data. Everything written since goes through
     * the logged insert/delete paths, which keep row and entry in one transaction.
     */
    @Transaction
    open suspend fun logExistingRow(
        rowId: String,
        payload: String,
        nodeId: String,
        epoch: Long,
        txId: String,
        now: Long,
    ): Long = appendLog(nodeId, epoch, txId, "users", rowId, ChangeOp.INSERT, payload, now)

    protected suspend fun appendLog(
        nodeId: String,
        epoch: Long,
        txId: String,
        table: String,
        rowId: String,
        op: ChangeOp,
        payload: String?,
        now: Long,
    ): Long {
        val seq = maxSeq(nodeId) + 1
        insertLog(
            ChangeLogEntry(
                nodeId = nodeId,
                seq = seq,
                txId = txId,
                tableName = table,
                rowId = rowId,
                op = op,
                payload = payload,
                epoch = epoch,
                createdAt = now,
            )
        )
        return seq
    }
}
