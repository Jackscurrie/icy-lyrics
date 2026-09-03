package com.icy.lyrics.core.platform.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface LocalTtmlDao {
  @Query("SELECT * FROM local_ttml WHERE trackKey = :trackKey LIMIT 1")
  suspend fun get(trackKey: String): LocalTtmlEntity?

  @Query("SELECT * FROM local_ttml WHERE trackKey = :trackKey LIMIT 1")
  fun observe(trackKey: String): Flow<LocalTtmlEntity?>

  @Query("SELECT * FROM local_ttml WHERE metadataKey = :metadataKey ORDER BY updatedAtEpochMs DESC LIMIT 1")
  suspend fun getByMetadataKey(metadataKey: String): LocalTtmlEntity?

  @Query("SELECT * FROM local_ttml ORDER BY updatedAtEpochMs DESC")
  fun observeAll(): Flow<List<LocalTtmlEntity>>

  @Query("SELECT * FROM local_ttml ORDER BY updatedAtEpochMs DESC")
  suspend fun all(): List<LocalTtmlEntity>

  @Upsert
  suspend fun upsert(entity: LocalTtmlEntity)

  @Query("DELETE FROM local_ttml WHERE trackKey = :trackKey")
  suspend fun delete(trackKey: String): Int

  @Query("SELECT COUNT(*) FROM local_ttml")
  suspend fun count(): Int
}

@Dao
interface LyricsCacheDao {
  @Query("SELECT * FROM lyrics_cache WHERE providerId = :providerId AND trackKey = :trackKey LIMIT 1")
  suspend fun get(providerId: String, trackKey: String): LyricsCacheEntity?

  @Query("SELECT * FROM lyrics_cache WHERE providerId = :providerId AND metadataKey = :metadataKey ORDER BY fetchedAtEpochMs DESC LIMIT 1")
  suspend fun getByMetadataKey(providerId: String, metadataKey: String): LyricsCacheEntity?

  @Upsert
  suspend fun upsert(entity: LyricsCacheEntity)

  @Query("DELETE FROM lyrics_cache WHERE providerId = :providerId AND trackKey = :trackKey")
  suspend fun delete(providerId: String, trackKey: String): Int

  @Query("DELETE FROM lyrics_cache WHERE providerId = :providerId AND metadataKey = :metadataKey")
  suspend fun deleteByMetadataKey(providerId: String, metadataKey: String): Int

  @Query("DELETE FROM lyrics_cache WHERE expiresAtEpochMs < :nowEpochMs")
  suspend fun deleteExpired(nowEpochMs: Long): Int

  @Query(
    """
      DELETE FROM lyrics_cache
      WHERE rowid NOT IN (
        SELECT rowid FROM lyrics_cache
        ORDER BY fetchedAtEpochMs DESC
        LIMIT :keep
      )
    """,
  )
  suspend fun trimToNewest(keep: Int): Int

  @Query("DELETE FROM lyrics_cache")
  suspend fun clear(): Int
}

@Dao
interface TrackAliasDao {
  @Query("SELECT * FROM track_aliases WHERE aliasKey = :aliasKey LIMIT 1")
  suspend fun get(aliasKey: String): TrackAliasEntity?

  @Upsert
  suspend fun upsert(entity: TrackAliasEntity)

  @Query("DELETE FROM track_aliases WHERE aliasKey = :aliasKey")
  suspend fun delete(aliasKey: String): Int
}

@Dao
interface DeviceTimingDao {
  @Query("SELECT * FROM device_timing WHERE deviceKey = :deviceKey LIMIT 1")
  suspend fun get(deviceKey: String): DeviceTimingEntity?

  @Query("SELECT * FROM device_timing WHERE deviceKey = :deviceKey LIMIT 1")
  fun observe(deviceKey: String): Flow<DeviceTimingEntity?>

  @Query("SELECT * FROM device_timing ORDER BY displayName COLLATE NOCASE")
  fun observeAll(): Flow<List<DeviceTimingEntity>>

  @Upsert
  suspend fun upsert(entity: DeviceTimingEntity)

  @Query("DELETE FROM device_timing WHERE deviceKey = :deviceKey")
  suspend fun delete(deviceKey: String): Int
}

@Dao
interface DiagnosticEventDao {
  @Insert(onConflict = OnConflictStrategy.ABORT)
  suspend fun insert(entity: DiagnosticEventEntity): Long

  @Query("SELECT * FROM diagnostic_events ORDER BY createdAtEpochMs DESC, id DESC LIMIT :limit")
  suspend fun recent(limit: Int): List<DiagnosticEventEntity>

  @Query("SELECT * FROM diagnostic_events ORDER BY createdAtEpochMs DESC, id DESC LIMIT :limit")
  fun observeRecent(limit: Int): Flow<List<DiagnosticEventEntity>>

  @Query("DELETE FROM diagnostic_events WHERE createdAtEpochMs < :oldestEpochMs")
  suspend fun deleteOlderThan(oldestEpochMs: Long): Int

  @Query(
    """
      DELETE FROM diagnostic_events
      WHERE id NOT IN (
        SELECT id FROM diagnostic_events
        ORDER BY createdAtEpochMs DESC, id DESC
        LIMIT :keep
      )
    """,
  )
  suspend fun trimToNewest(keep: Int): Int

  @Query("DELETE FROM diagnostic_events")
  suspend fun clear(): Int
}
