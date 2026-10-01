package com.example.ktorserver.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction

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

    @Insert
    protected abstract suspend fun insertUser(user: User)

    @Query("DELETE FROM users WHERE id = :id")
    protected abstract suspend fun deleteUserRow(id: String): Int

    @Insert
    protected abstract suspend fun insertLog(entry: ChangeLogEntry)

    @Query("SELECT COALESCE(MAX(seq), 0) FROM change_log WHERE nodeId = :nodeId")
    protected abstract suspend fun maxSeq(nodeId: String): Long

    @Transaction
    open suspend fun insertUserLogged(
        user: User,
        nodeId: String,
        epoch: Long,
        txId: String,
        payload: String,
        now: Long,
    ) {
        insertUser(user)
        appendLog(nodeId, epoch, txId, "users", user.id, ChangeOp.INSERT, payload, now)
    }

    @Transaction
    open suspend fun deleteUserLogged(
        id: String,
        nodeId: String,
        epoch: Long,
        txId: String,
        now: Long,
    ): Boolean {
        val removed = deleteUserRow(id) > 0
        // No log entry when nothing was deleted — the peer must not replay a no-op.
        if (removed) {
            appendLog(nodeId, epoch, txId, "users", id, ChangeOp.DELETE, null, now)
        }
        return removed
    }

    /**
     * Applies a change that arrived FROM the peer. The entry keeps its originating
     * nodeId and seq, so the log stays a faithful record of who wrote what.
     */
    @Transaction
    open suspend fun applyReplicated(entry: ChangeLogEntry, decoded: User?) {
        when (entry.op) {
            ChangeOp.INSERT, ChangeOp.UPDATE -> decoded?.let { insertUser(it) }
            ChangeOp.DELETE -> deleteUserRow(entry.rowId)
        }
        insertLog(entry)
    }

    protected suspend fun appendLog(
        nodeId: String,
        epoch: Long,
        txId: String,
        table: String,
        rowId: String,
        op: ChangeOp,
        payload: String?,
        now: Long,
    ) {
        insertLog(
            ChangeLogEntry(
                nodeId = nodeId,
                seq = maxSeq(nodeId) + 1,
                txId = txId,
                tableName = table,
                rowId = rowId,
                op = op,
                payload = payload,
                epoch = epoch,
                createdAt = now,
            )
        )
    }
}
