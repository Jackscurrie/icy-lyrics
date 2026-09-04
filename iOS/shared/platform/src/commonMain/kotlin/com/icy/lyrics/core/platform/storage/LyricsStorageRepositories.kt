package com.icy.lyrics.core.platform.storage

import com.icy.lyrics.core.platform.runtime.epochMillis
import com.icy.lyrics.core.lyrics.model.LyricsDocument
import com.icy.lyrics.core.lyrics.model.TrackIdentity
import com.icy.lyrics.core.lyrics.provider.LyricsProviderId
import com.icy.lyrics.core.platform.database.LocalTtmlDao
import com.icy.lyrics.core.platform.database.LocalTtmlEntity
import com.icy.lyrics.core.platform.database.LyricsCacheDao
import com.icy.lyrics.core.platform.database.LyricsCacheEntity
import com.icy.lyrics.core.platform.database.TrackAliasDao
import com.icy.lyrics.core.platform.database.TrackAliasEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal val PlatformJson = Json {
  ignoreUnknownKeys = true
  encodeDefaults = true
  classDiscriminator = "documentType"
}

data class StoredLocalTtml(
  val trackKey: String,
  val metadataKey: String,
  val title: String,
  val artists: List<String>,
  val album: String,
  val rawTtml: String,
  val document: LyricsDocument,
  val schemaVersion: Int,
  val origin: String,
  val sourceUri: String?,
  val importedAtEpochMs: Long,
  val updatedAtEpochMs: Long,
)

class LocalTtmlRepository(
  private val dao: LocalTtmlDao,
  private val clock: () -> Long = ::epochMillis,
) {
  suspend fun get(track: TrackIdentity): StoredLocalTtml? {
    return dao.get(TrackKeys.exact(track))?.decode()
  }

  fun observe(track: TrackIdentity): Flow<StoredLocalTtml?> {
    return dao.observe(TrackKeys.exact(track)).map { it?.decode() }
  }

  fun observeLibrary(): Flow<List<StoredLocalTtml>> {
    return dao.observeAll().map { entries -> entries.mapNotNull { it.decode() } }
  }

  suspend fun library(): List<StoredLocalTtml> = dao.all().mapNotNull { it.decode() }

  suspend fun save(
    track: TrackIdentity,
    rawTtml: String,
    document: LyricsDocument,
    sourceUri: String? = null,
    origin: String = "user-import",
  ) {
    require(rawTtml.isNotBlank()) { "TTML cannot be blank" }
    val now = clock()
    val existing = dao.get(TrackKeys.exact(track))
    dao.upsert(
      LocalTtmlEntity(
        trackKey = TrackKeys.exact(track),
        metadataKey = TrackKeys.metadata(track),
        title = track.title,
        artists = PlatformJson.encodeToString(track.artists),
        album = track.album,
        rawTtml = rawTtml,
        documentJson = PlatformJson.encodeToString<LyricsDocument>(document),
        schemaVersion = CURRENT_SCHEMA_VERSION,
        origin = origin.take(80),
        sourceUri = sourceUri,
        importedAtEpochMs = existing?.importedAtEpochMs ?: now,
        updatedAtEpochMs = now,
      ),
    )
  }

  suspend fun delete(track: TrackIdentity): Boolean = dao.delete(TrackKeys.exact(track)) > 0

  suspend fun deleteByTrackKey(trackKey: String): Boolean = dao.delete(trackKey) > 0

  private fun LocalTtmlEntity.decode(): StoredLocalTtml? {
    val document = runCatching {
      PlatformJson.decodeFromString<LyricsDocument>(documentJson)
    }.getOrNull() ?: return null
    return StoredLocalTtml(
      trackKey = trackKey,
      metadataKey = metadataKey,
      title = title,
      artists = runCatching { PlatformJson.decodeFromString<List<String>>(artists) }
        .getOrDefault(emptyList()),
      album = album,
      rawTtml = rawTtml,
      document = document,
      schemaVersion = schemaVersion,
      origin = origin,
      sourceUri = sourceUri,
      importedAtEpochMs = importedAtEpochMs,
      updatedAtEpochMs = updatedAtEpochMs,
    )
  }

  companion object {
    const val CURRENT_SCHEMA_VERSION = 1
  }
}

sealed interface CachedLyrics {
  val fetchedAtEpochMs: Long
  val expiresAtEpochMs: Long
  val isExpired: Boolean

  data class Hit(
    val document: LyricsDocument,
    val rawPayload: String?,
    val rawFormat: String?,
    val sourceVerified: Boolean,
    override val fetchedAtEpochMs: Long,
    override val expiresAtEpochMs: Long,
    override val isExpired: Boolean,
  ) : CachedLyrics

  data class Negative(
    override val fetchedAtEpochMs: Long,
    override val expiresAtEpochMs: Long,
    override val isExpired: Boolean,
  ) : CachedLyrics
}

class LyricsCacheRepository(
  private val dao: LyricsCacheDao,
  private val clock: () -> Long = ::epochMillis,
) {
  suspend fun get(
    provider: LyricsProviderId,
    track: TrackIdentity,
    allowExpired: Boolean = false,
  ): CachedLyrics? {
    val now = clock()
    val entity = dao.get(provider.name, TrackKeys.exact(track))
      ?: if (TrackKeys.mayUseMetadataFallback(track)) {
        dao.getByMetadataKey(provider.name, TrackKeys.metadata(track))
      } else {
        null
      }
      ?: return null
    val expired = entity.expiresAtEpochMs < now
    if (expired && !allowExpired) return null
    if (entity.negative) {
      return CachedLyrics.Negative(entity.fetchedAtEpochMs, entity.expiresAtEpochMs, expired)
    }
    val encoded = entity.documentJson ?: return null
    val document = runCatching {
      PlatformJson.decodeFromString<LyricsDocument>(encoded)
    }.getOrNull() ?: return null
    return CachedLyrics.Hit(
      document = document,
      rawPayload = entity.rawPayload,
      rawFormat = entity.rawFormat,
      sourceVerified = entity.sourceVerified,
      fetchedAtEpochMs = entity.fetchedAtEpochMs,
      expiresAtEpochMs = entity.expiresAtEpochMs,
      isExpired = expired,
    )
  }

  suspend fun put(
    provider: LyricsProviderId,
    track: TrackIdentity,
    document: LyricsDocument,
    ttlMs: Long = DEFAULT_POSITIVE_TTL_MS,
    rawPayload: String? = null,
    rawFormat: String? = null,
    sourceVerified: Boolean = true,
  ) {
    val now = clock()
    dao.upsert(
      LyricsCacheEntity(
        providerId = provider.name,
        trackKey = TrackKeys.exact(track),
        metadataKey = TrackKeys.metadata(track),
        documentJson = PlatformJson.encodeToString<LyricsDocument>(document),
        rawPayload = rawPayload,
        rawFormat = rawFormat,
        syncKind = document.syncKind.name,
        negative = false,
        sourceVerified = sourceVerified,
        fetchedAtEpochMs = now,
        expiresAtEpochMs = now + ttlMs.coerceAtLeast(0L),
      ),
    )
  }

  suspend fun putNegative(
    provider: LyricsProviderId,
    track: TrackIdentity,
    ttlMs: Long = DEFAULT_NEGATIVE_TTL_MS,
  ) {
    val now = clock()
    dao.upsert(
      LyricsCacheEntity(
        providerId = provider.name,
        trackKey = TrackKeys.exact(track),
        metadataKey = TrackKeys.metadata(track),
        negative = true,
        fetchedAtEpochMs = now,
        expiresAtEpochMs = now + ttlMs.coerceAtLeast(0L),
      ),
    )
  }

  suspend fun invalidate(provider: LyricsProviderId, track: TrackIdentity): Boolean {
    val exactDeleted = dao.delete(provider.name, TrackKeys.exact(track))
    val metadataDeleted = if (TrackKeys.mayUseMetadataFallback(track)) {
      dao.deleteByMetadataKey(provider.name, TrackKeys.metadata(track))
    } else {
      0
    }
    return exactDeleted + metadataDeleted > 0
  }

  suspend fun pruneExpired(): Int =
    dao.deleteExpired(clock()) + dao.trimToNewest(MAX_CACHE_ROWS)

  companion object {
    const val DEFAULT_POSITIVE_TTL_MS = 3L * 24L * 60L * 60L * 1_000L
    const val DEFAULT_NEGATIVE_TTL_MS = 60L * 60L * 1_000L
    const val MAX_CACHE_ROWS = 250
  }
}

class TrackAliasRepository(
  private val dao: TrackAliasDao,
  private val clock: () -> Long = ::epochMillis,
) {
  suspend fun resolve(aliasKey: String): String? = dao.get(aliasKey)?.canonicalTrackUri

  suspend fun resolve(track: TrackIdentity): String? = resolve(TrackKeys.metadata(track))

  suspend fun remember(aliasKey: String, canonicalTrackUri: String, evidence: String) {
    require(aliasKey.isNotBlank()) { "Alias key cannot be blank" }
    require(canonicalTrackUri.isNotBlank()) { "Canonical track URI cannot be blank" }
    val now = clock()
    val existing = dao.get(aliasKey)
    dao.upsert(
      TrackAliasEntity(
        aliasKey = aliasKey,
        canonicalTrackUri = canonicalTrackUri,
        evidence = evidence.take(80),
        createdAtEpochMs = existing?.createdAtEpochMs ?: now,
        updatedAtEpochMs = now,
      ),
    )
  }

  suspend fun remember(track: TrackIdentity, canonicalTrackUri: String, evidence: String) {
    remember(TrackKeys.metadata(track), canonicalTrackUri, evidence)
  }

  suspend fun forget(aliasKey: String): Boolean = dao.delete(aliasKey) > 0
}
