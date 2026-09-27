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
  userAgent: String = "IcyLyricsAndroid/1.2.0 (https://jackscurrie.com/icy-lyrics; jack@jackscurrie.com)",
  requestSpacingMs: Long = 300L,
  maxResponseBytes: Long = 2L * 1_024L * 1_024L,
  allowInsecureForTests: Boolean = false,
): LrclibConfig = LrclibConfig(baseUrl.toString().toHttpUrl(), userAgent, requestSpacingMs, maxResponseBytes, allowInsecureForTests)

fun IcyLyricsDatabaseConfig(
  endpoint: HttpUrl,
  userAgent: String = "IcyLyricsAndroid/1.2.0 (+https://jackscurrie.com/icy-lyrics)",
  maxResponseBytes: Long = 2L * 1_024L * 1_024L,
  requestTimeoutMs: Long = 15_000L,
  positiveCacheTtlMs: Long = 30L * 24L * 60L * 60L * 1_000L,
  negativeCacheTtlMs: Long = 60L * 60L * 1_000L,
  allowInsecureForTests: Boolean = false,
): IcyLyricsDatabaseConfig = IcyLyricsDatabaseConfig(
  endpoint = endpoint.toString().toHttpUrl(),
  userAgent = userAgent,
  maxResponseBytes = maxResponseBytes,
  requestTimeoutMs = requestTimeoutMs,
  positiveCacheTtlMs = positiveCacheTtlMs,
  negativeCacheTtlMs = negativeCacheTtlMs,
  allowInsecureForTests = allowInsecureForTests,
)

fun SpotifyCatalogConfig(
  baseUrl: HttpUrl,
  maxResponseBytes: Long = 1L * 1_024L * 1_024L,
  searchLimit: Int = 8,
  requestTimeoutMs: Long = 8_000L,
  allowInsecureForTests: Boolean = false,
  icyCatalogEndpoint: HttpUrl? = null,
  icyCatalogSearchLimit: Int = 100,
  icyCatalogUserAgent: String =
    "IcyLyricsAndroidTV (+https://jackscurrie.com/icy-lyrics)",
): SpotifyCatalogConfig = SpotifyCatalogConfig(
  baseUrl = baseUrl.toString().toHttpUrl(),
  maxResponseBytes = maxResponseBytes,
  searchLimit = searchLimit,
  requestTimeoutMs = requestTimeoutMs,
  allowInsecureForTests = allowInsecureForTests,
  icyCatalogEndpoint = icyCatalogEndpoint?.toString()?.toHttpUrl(),
  icyCatalogSearchLimit = icyCatalogSearchLimit,
  icyCatalogUserAgent = icyCatalogUserAgent,
)

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

fun IcyLyricsDatabaseProvider(
  client: OkHttpClient,
  cache: LyricsCacheRepository,
  config: IcyLyricsDatabaseConfig = IcyLyricsDatabaseConfig(),
  enabled: suspend () -> Boolean = { true },
  online: () -> Boolean = { true },
  diagnostics: DiagnosticSink = DiagnosticSink.NONE,
): IcyLyricsDatabaseProvider = IcyLyricsDatabaseProvider(
  client = OkHttpTransport(client),
  cache = cache,
  config = config,
  enabled = enabled,
  online = online,
  diagnostics = diagnostics,
)

fun SpotifyTrackResolver(
  client: OkHttpClient,
  tokenSource: SpotifyAccessTokenSource,
  aliases: TrackAliasRepository,
  config: SpotifyCatalogConfig = SpotifyCatalogConfig(),
  diagnostics: DiagnosticSink = DiagnosticSink.NONE,
  icyCatalogEnabled: suspend () -> Boolean = { true },
): SpotifyTrackResolver = SpotifyTrackResolver(
  OkHttpTransport(client),
  tokenSource,
  aliases,
  config,
  diagnostics,
  icyCatalogEnabled,
)
