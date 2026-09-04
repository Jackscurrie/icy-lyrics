@file:Suppress("FunctionName")

package com.icy.lyrics.core.platform.provider

import com.icy.lyrics.core.lyrics.provider.LyricsProviderId
import com.icy.lyrics.core.platform.diagnostics.DiagnosticSink
import com.icy.lyrics.core.platform.network.HttpUrl.Companion.toHttpUrl
import com.icy.lyrics.core.platform.network.OkHttpTransport
import com.icy.lyrics.core.platform.storage.LyricsCacheRepository
import com.icy.lyrics.core.platform.storage.TrackAliasRepository
import kotlinx.coroutines.delay
import okhttp3.HttpUrl
import okhttp3.OkHttpClient

/** Source-compatible Android entry points; the implementations are shared. */
fun SpicyLyricsConfig(
  endpoint: HttpUrl,
  compatibilityVersion: String = "6.3.12",
  maxResponseBytes: Long = 4L * 1_024L * 1_024L,
  allowInsecureForTests: Boolean = false,
): SpicyLyricsConfig = SpicyLyricsConfig(endpoint.toString().toHttpUrl(), compatibilityVersion, maxResponseBytes, allowInsecureForTests)

fun LrclibConfig(
  baseUrl: HttpUrl,
  userAgent: String = "IcyLyricsAndroidV2/1.0",
  requestSpacingMs: Long = 300L,
  maxResponseBytes: Long = 2L * 1_024L * 1_024L,
  allowInsecureForTests: Boolean = false,
): LrclibConfig = LrclibConfig(baseUrl.toString().toHttpUrl(), userAgent, requestSpacingMs, maxResponseBytes, allowInsecureForTests)

fun SpotifyCatalogConfig(
  baseUrl: HttpUrl,
  maxResponseBytes: Long = 1L * 1_024L * 1_024L,
  searchLimit: Int = 8,
  requestTimeoutMs: Long = 8_000L,
  allowInsecureForTests: Boolean = false,
): SpotifyCatalogConfig = SpotifyCatalogConfig(baseUrl.toString().toHttpUrl(), maxResponseBytes, searchLimit, requestTimeoutMs, allowInsecureForTests)

fun SpicyLyricsProvider(
  id: LyricsProviderId = LyricsProviderId.SPICY,
  client: OkHttpClient,
  tokenSource: SpotifyAccessTokenSource,
  cache: LyricsCacheRepository,
  config: SpicyLyricsConfig = SpicyLyricsConfig(),
  enabled: suspend () -> Boolean = { false },
  tokenSharingConsent: suspend () -> Boolean = { false },
  online: () -> Boolean = { true },
  diagnostics: DiagnosticSink = DiagnosticSink.NONE,
  hostCircuitBreaker: SpicyHostCircuitBreaker = SpicyHostCircuitBreaker(),
): SpicyLyricsProvider = SpicyLyricsProvider(id, OkHttpTransport(client), tokenSource, cache, config, enabled, tokenSharingConsent, online, diagnostics, hostCircuitBreaker)

fun LrclibProvider(
  client: OkHttpClient,
  cache: LyricsCacheRepository,
  config: LrclibConfig = LrclibConfig(),
  enabled: suspend () -> Boolean = { true },
  online: () -> Boolean = { true },
  diagnostics: DiagnosticSink = DiagnosticSink.NONE,
  wait: suspend (Long) -> Unit = { delay(it) },
): LrclibProvider = LrclibProvider(OkHttpTransport(client), cache, config, enabled, online, diagnostics, wait)

fun SpotifyTrackResolver(
  client: OkHttpClient,
  tokenSource: SpotifyAccessTokenSource,
  aliases: TrackAliasRepository,
  config: SpotifyCatalogConfig = SpotifyCatalogConfig(),
  diagnostics: DiagnosticSink = DiagnosticSink.NONE,
): SpotifyTrackResolver = SpotifyTrackResolver(OkHttpTransport(client), tokenSource, aliases, config, diagnostics)
