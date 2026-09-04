package com.icy.lyrics.core.platform.provider

import com.icy.lyrics.core.lyrics.model.LyricsSource
import com.icy.lyrics.core.lyrics.model.TrackIdentity
import com.icy.lyrics.core.lyrics.parser.TtmlParser
import com.icy.lyrics.core.lyrics.provider.LyricsProvider
import com.icy.lyrics.core.lyrics.provider.LyricsProviderId
import com.icy.lyrics.core.lyrics.provider.LyricsRequest
import com.icy.lyrics.core.lyrics.provider.ProviderResult
import com.icy.lyrics.core.lyrics.provider.ProviderUnavailableReason
import com.icy.lyrics.core.platform.storage.LocalTtmlRepository

class LocalTtmlProvider(
  private val repository: LocalTtmlRepository,
  private val enabled: suspend () -> Boolean = { true },
) : LyricsProvider {
  override val id = LyricsProviderId.LOCAL_TTML

  override suspend fun fetch(request: LyricsRequest): ProviderResult {
    if (!enabled()) {
      return ProviderResult.Unavailable(
        ProviderUnavailableReason.DISABLED,
        "Saved local TTML is disabled; stored files were retained.",
      )
    }
    val stored = repository.get(request.track)
      ?: return ProviderResult.NotFound("No saved TTML matches this exact track identity.")
    return ProviderResult.Found(
      document = stored.document,
      fromCache = false,
      rawFormat = "ttml",
      message = "Saved local TTML",
    )
  }

  suspend fun import(
    track: TrackIdentity,
    rawTtml: String,
    sourceUri: String? = null,
    origin: String = "user-import",
  ) = TtmlParser.parse(rawTtml, track.exactStorageKey, LyricsSource.LOCAL_TTML).also { document ->
    repository.save(track, rawTtml, document, sourceUri, origin)
  }

  suspend fun remove(track: TrackIdentity): Boolean = repository.delete(track)
}
