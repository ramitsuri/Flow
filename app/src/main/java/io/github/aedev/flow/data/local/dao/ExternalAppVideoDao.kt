package io.github.aedev.flow.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import io.github.aedev.flow.data.local.entity.ExternalAppVideoEntity

@Dao
interface ExternalAppVideoDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(tracks: List<ExternalAppVideoEntity>)

    @Query("DELETE FROM external_app_video WHERE id IN (:ids)")
    suspend fun delete(ids: List<String>)

    @Query("SELECT * FROM external_app_video")
    suspend fun get(): List<ExternalAppVideoEntity>
}
