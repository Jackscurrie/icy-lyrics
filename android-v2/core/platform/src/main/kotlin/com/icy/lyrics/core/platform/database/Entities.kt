package com.icy.lyrics.core.platform.database

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
  tableName = "local_ttml",
  indices = [Index(value = ["metadataKey"])],
)
data class LocalTtmlEntity(
  @PrimaryKey val trackKey: String,
  val metadataKey: String,
  val title: String,
  val artists: String,
  val album: String,
  val rawTtml: String,
  val documentJson: String,
  val schemaVersion: Int = 1,
  val origin: String,
  val sourceUri: String? = null,
  val importedAtEpochMs: Long,
  val updatedAtEpochMs: Long,
)

@Entity(
  tableName = "lyrics_cache",
  primaryKeys = ["providerId", "trackKey"],
  indices = [
    Index(value = ["metadataKey"]),
    Index(value = ["expiresAtEpochMs"]),
  ],
)
data class LyricsCacheEntity(
  val providerId: String,
  val trackKey: String,
  val metadataKey: String,
  val documentJson: String? = null,
  val rawPayload: String? = null,
  val rawFormat: String? = null,
  val syncKind: String? = null,
  val negative: Boolean = false,
  val sourceVerified: Boolean = false,
  val fetchedAtEpochMs: Long,
  val expiresAtEpochMs: Long,
  val etag: String? = null,
)

@Entity(
  tableName = "track_aliases",
  indices = [Index(value = ["canonicalTrackUri"])],
)
data class TrackAliasEntity(
  @PrimaryKey val aliasKey: String,
  val canonicalTrackUri: String,
  val evidence: String,
  val createdAtEpochMs: Long,
  val updatedAtEpochMs: Long,
)

@Entity(tableName = "device_timing")
data class DeviceTimingEntity(
  @PrimaryKey val deviceKey: String,
  val displayName: String,
  val routeType: Int,
  val identityKind: String,
  val offsetMs: Int,
  val updatedAtEpochMs: Long,
)

@Entity(
  tableName = "diagnostic_events",
  indices = [
    Index(value = ["createdAtEpochMs"]),
    Index(value = ["component"]),
  ],
)
data class DiagnosticEventEntity(
  @PrimaryKey(autoGenerate = true) val id: Long = 0L,
  val createdAtEpochMs: Long,
  val severity: String,
  val component: String,
  val code: String,
  val providerId: String? = null,
  val trackKeyHash: String? = null,
  val httpStatus: Int? = null,
  val message: String,
  val details: String? = null,
)
