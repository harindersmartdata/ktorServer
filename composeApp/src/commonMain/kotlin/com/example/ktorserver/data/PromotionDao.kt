package com.example.ktorserver.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface PromotionDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun record(record: PromotionRecord)

    @Query("SELECT * FROM promotion_log ORDER BY at DESC LIMIT :limit")
    suspend fun recent(limit: Int = 50): List<PromotionRecord>
}
