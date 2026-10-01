package com.example.ktorserver.data

import androidx.room.Dao
import androidx.room.Query

@Dao
interface ChangeLogDao {

    /** Everything this node originated after [afterSeq] — the replication stream. */
    @Query(
        "SELECT * FROM change_log WHERE nodeId = :nodeId AND seq > :afterSeq " +
            "ORDER BY seq LIMIT :limit"
    )
    suspend fun since(nodeId: String, afterSeq: Long, limit: Int = 500): List<ChangeLogEntry>

    @Query("SELECT COALESCE(MAX(seq), 0) FROM change_log WHERE nodeId = :nodeId")
    suspend fun latestSeq(nodeId: String): Long

    /** How far behind the peer is, in changes. */
    @Query("SELECT COUNT(*) FROM change_log WHERE nodeId = :nodeId AND seq > :ackedSeq")
    suspend fun pendingSince(nodeId: String, ackedSeq: Long): Long

    @Query("SELECT * FROM change_log WHERE txId = :txId ORDER BY seq")
    suspend fun byTransaction(txId: String): List<ChangeLogEntry>
}
