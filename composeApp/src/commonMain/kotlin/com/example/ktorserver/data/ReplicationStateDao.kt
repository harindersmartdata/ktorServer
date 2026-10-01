package com.example.ktorserver.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ReplicationStateDao {

    @Query("SELECT * FROM replication_state WHERE id = ${ReplicationState.SINGLETON_ID}")
    suspend fun get(): ReplicationState?

    @Query("SELECT * FROM replication_state WHERE id = ${ReplicationState.SINGLETON_ID}")
    fun observe(): Flow<ReplicationState?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(state: ReplicationState)

    @Query(
        "UPDATE replication_state SET lastAppliedNodeId = :nodeId, lastAppliedSeq = :seq " +
            "WHERE id = ${ReplicationState.SINGLETON_ID}"
    )
    suspend fun recordApplied(nodeId: String, seq: Long)
}
