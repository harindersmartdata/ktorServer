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
            "WHERE id = ${ReplicationState.SINGLETON_ID} AND :seq > lastAppliedSeq"
    )
    suspend fun recordApplied(nodeId: String, seq: Long)

    /**
     * Forces the ack mark, including downwards.
     *
     * Only for when the peer itself reports holding less than we believed — a replaced
     * or reset terminal at the same address. Ordinary acks use [recordPeerAck].
     */
    @Query(
        "UPDATE replication_state SET peerAckedSeq = :seq " +
            "WHERE id = ${ReplicationState.SINGLETON_ID}"
    )
    suspend fun forcePeerAck(seq: Long)

    /**
     * Moves the peer to a new address without touching anything else.
     *
     * Used when discovery finds the SAME peer somewhere new, which is routine under
     * DHCP. Deliberately not [put] with a changed address: that resets the ack mark,
     * and the peer still holds everything it held a moment ago.
     */
    @Query(
        "UPDATE replication_state SET peerAddress = :address " +
            "WHERE id = ${ReplicationState.SINGLETON_ID}"
    )
    suspend fun updatePeerAddress(address: String)

    /** Never moves backwards: a late reply must not undo a later ack. */
    @Query(
        "UPDATE replication_state SET peerAckedSeq = :seq " +
            "WHERE id = ${ReplicationState.SINGLETON_ID} AND :seq > peerAckedSeq"
    )
    suspend fun recordPeerAck(seq: Long)
}
