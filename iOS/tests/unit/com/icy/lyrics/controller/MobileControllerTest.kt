@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.icy.lyrics.controller

import com.icy.lyrics.core.lyrics.model.LyricsDocument
import com.icy.lyrics.core.lyrics.model.LyricsMetadata
import com.icy.lyrics.core.lyrics.model.StaticLyricLine
import com.icy.lyrics.core.lyrics.model.StaticLyrics
import com.icy.lyrics.core.lyrics.model.TrackIdentity
import com.icy.lyrics.core.lyrics.provider.LyricsProviderId
import com.icy.lyrics.core.lyrics.provider.LyricsResolution
import com.icy.lyrics.core.lyrics.provider.ProviderAttempt
import com.icy.lyrics.core.lyrics.provider.ProviderAttemptOutcome
import com.icy.lyrics.core.platform.diagnostics.DiagnosticEvent
import com.icy.lyrics.core.platform.settings.AppSettings
import com.icy.lyrics.core.platform.storage.StoredLocalTtml
import com.icy.lyrics.media.NowPlayingSnapshot
import com.icy.lyrics.ui.LyricsUiStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Exercises the actual shared state machine with deterministic, independently controlled I/O. */
class MobileControllerTest {
  @Test
  fun importFinishingAfterTrackChangeSavesCapturedIdentityWithoutReplacingCurrentLyrics() = runTest {
    val h = Harness(backgroundScope)
    val importCanFinish = CompletableDeferred<Unit>()
    h.backend.import = { importCanFinish.await() }
    h.playback.snapshots.value = snapshot("Original", localSuffix = "123")
    runCurrent()
    val captured = h.controller.prepareImport()!!
    h.controller.importTtml(captured, "<tt>captured lyrics</tt>", "file:///picked.ttml")
    runCurrent()

    h.playback.snapshots.value = snapshot("Current", localSuffix = "456")
    runCurrent()
    val current = h.playback.snapshots.value!!.identity
    val visibleBeforeImportFinishes = ready(h.controller)
    val requestsBeforeImportFinishes = h.backend.requests.toList()
    importCanFinish.complete(Unit)
    runCurrent()

    assertEquals(captured, h.backend.imports.single().track)
    assertEquals("spotify:local:Artist:Album:Original:123", h.backend.imports.single().track.uri)
    assertEquals("file:///picked.ttml", h.backend.imports.single().sourceUri)
    assertEquals(current, h.controller.state.value.nowPlaying?.identity)
    assertEquals(visibleBeforeImportFinishes, ready(h.controller))
    assertEquals(requestsBeforeImportFinishes, h.backend.requests)
  }

  @Test
  fun importForCurrentTrackForcesOneFreshLookupAfterTheSaveCompletes() = runTest {
    val h = Harness(backgroundScope)
    val saved = CompletableDeferred<Unit>()
    h.backend.import = { saved.await() }
    h.playback.snapshots.value = snapshot("Current")
    runCurrent()
    val track = h.controller.prepareImport()!!
    h.controller.importTtml(track, "<tt>new lyrics</tt>", null)
    runCurrent()
    assertEquals(1, h.backend.requests.size)

    saved.complete(Unit)
    runCurrent()
    assertEquals(listOf(true, false), h.backend.requests.map { it.allowCached })
    assertEquals(listOf(track, track), h.backend.requests.map { it.track })
    assertTrue(h.backend.requests[1].requestId > h.backend.requests[0].requestId)
  }

  @Test
  fun changingTracksCancelsCooperativeProviderWorkWithoutShowingAnError() = runTest {
    val h = Harness(backgroundScope)
    var oldLookupCancelled = false
    h.backend.resolve = { request ->
      if (request.track.title == "Old") {
        try { awaitCancellation() } finally { oldLookupCancelled = true }
      } else found(request.track)
    }
    h.playback.snapshots.value = snapshot("Old")
    runCurrent()
    assertIs<LyricsUiStatus.Loading>(h.controller.state.value.lyrics)
    h.playback.snapshots.value = snapshot("New")
    runCurrent()

    assertTrue(oldLookupCancelled)
    assertEquals("New", (ready(h.controller) as StaticLyrics).lines.single().text)
    assertNull(h.controller.state.value.diagnostics.error)
    assertNull(h.controller.state.value.transientMessage)
  }

  @Test
  fun providerIgnoringCancellationCannotPublishALateResultForAnOldTrack() = runTest {
    val h = Harness(backgroundScope)
    lateinit var lateResult: Continuation<LyricsResolution>
    h.backend.resolve = { request ->
      if (request.track.title == "Old") suspendCoroutine<LyricsResolution> { lateResult = it }
      else found(request.track)
    }
    h.playback.snapshots.value = snapshot("Old")
    runCurrent()
    val old = h.playback.snapshots.value!!.identity
    h.playback.snapshots.value = snapshot("New")
    runCurrent()
    val currentDocument = ready(h.controller)
    val currentDiagnostics = h.controller.state.value.diagnostics

    lateResult.resume(found(old, LyricsProviderId.SPICY))
    runCurrent()
    assertEquals(currentDocument, ready(h.controller))
    assertEquals(currentDiagnostics, h.controller.state.value.diagnostics)
  }

  @Test
  fun providerIgnoringCancellationCannotReplaceCurrentStateWithALateFailure() = runTest {
    val h = Harness(backgroundScope)
    lateinit var lateResult: Continuation<LyricsResolution>
    h.backend.resolve = { request ->
      if (request.track.title == "Old") suspendCoroutine<LyricsResolution> { lateResult = it }
      else found(request.track)
    }
    h.playback.snapshots.value = snapshot("Old")
    runCurrent()
    h.playback.snapshots.value = snapshot("New")
    runCurrent()
    val currentState = h.controller.state.value

    lateResult.resumeWithException(IllegalStateException("Old request failed"))
    runCurrent()
    assertEquals(currentState, h.controller.state.value)
  }

  @Test
  fun stoppingPlaybackClearsLyricsAndRejectsAProviderResultArrivingAfterward() = runTest {
    val h = Harness(backgroundScope)
    lateinit var lateResult: Continuation<LyricsResolution>
    h.backend.resolve = { suspendCoroutine { lateResult = it } }
    h.playback.snapshots.value = snapshot("Old")
    runCurrent()
    val old = h.playback.snapshots.value!!.identity
    h.playback.snapshots.value = null
    runCurrent()
    lateResult.resume(found(old))
    runCurrent()

    assertIs<LyricsUiStatus.Idle>(h.controller.state.value.lyrics)
    assertNull(h.controller.state.value.nowPlaying)
    assertNull(h.controller.state.value.diagnostics.trackUri)
  }

  @Test
  fun queuedHigherPrioritySourceKeepsFallbackVisibleUntilItCanBePromoted() = runTest {
    val h = Harness(backgroundScope)
    val promotion = CompletableDeferred<LyricsResolution>()
    h.backend.resolve = { request ->
      if (h.backend.requests.size == 1) found(request.track, LyricsProviderId.LRCLIB, queuedFirst = true)
      else promotion.await()
    }
    h.playback.snapshots.value = snapshot("Queued song")
    runCurrent()
    val fallback = ready(h.controller)
    assertEquals(LyricsProviderId.LRCLIB.expectedSource, fallback.metadata.source)
    advanceTimeBy(1_999)
    runCurrent()
    assertEquals(1, h.backend.requests.size)

    advanceTimeBy(1)
    runCurrent()
    assertEquals(2, h.backend.requests.size)
    assertEquals(fallback, ready(h.controller))
    assertTrue(h.backend.requests[1].allowCached)
    assertEquals(h.backend.requests[0].requestId, h.backend.requests[1].requestId)

    promotion.complete(found(h.playback.snapshots.value!!.identity, LyricsProviderId.SPICY))
    runCurrent()
    assertEquals(LyricsProviderId.SPICY.expectedSource, ready(h.controller).metadata.source)
    assertEquals(LyricsProviderId.SPICY.expectedSource, h.controller.state.value.diagnostics.selectedSource)
    advanceTimeBy(60_000)
    runCurrent()
    assertEquals(2, h.backend.requests.size)
  }

  @Test
  fun pendingWithoutFallbackRetriesFreshWithTheExpectedBackoff() = runTest {
    val h = Harness(backgroundScope)
    h.backend.resolve = { request ->
      if (h.backend.requests.size <= 2) LyricsResolution.Pending(
        LyricsProviderId.SPICY, message = "Preparing lyrics", attempts = listOf(queuedAttempt()),
      ) else found(request.track, LyricsProviderId.SPICY)
    }
    h.playback.snapshots.value = snapshot("Queued song")
    runCurrent()
    assertIs<LyricsUiStatus.Loading>(h.controller.state.value.lyrics)
    advanceTimeBy(2_000)
    runCurrent()
    assertEquals(2, h.backend.requests.size)
    advanceTimeBy(2_999)
    runCurrent()
    assertEquals(2, h.backend.requests.size)
    advanceTimeBy(1)
    runCurrent()

    assertEquals(listOf(true, false, false), h.backend.requests.map { it.allowCached })
    assertEquals(LyricsProviderId.SPICY.expectedSource, ready(h.controller).metadata.source)
  }

  @Test
  fun switchingTracksCancelsPendingPromotionAndNeverRetriesTheOldTrack() = runTest {
    val h = Harness(backgroundScope)
    h.backend.resolve = { request -> found(request.track, queuedFirst = request.track.title == "Old") }
    h.playback.snapshots.value = snapshot("Old")
    runCurrent()
    h.playback.snapshots.value = snapshot("New")
    runCurrent()
    advanceTimeBy(60_000)
    runCurrent()

    assertEquals(listOf("Old", "New"), h.backend.requests.map { it.track.title })
    assertEquals("New", (ready(h.controller) as StaticLyrics).lines.single().text)
  }

  @Test
  fun concurrentSettingsEditsPreserveSavedSettingsAndTheActiveDeviceTiming() = runTest {
    val backend = FakeBackend(AppSettings(rememberLocalTtml = false, spicyTokenSharingConsent = true))
    backend.settingsWriteDelayMs = 20L
    backend.deviceTiming.value = MobileDeviceTiming("bt:route:headphones", "Headphones", 340)
    val h = Harness(backgroundScope, backend)
    runCurrent()
    h.controller.setGlobalTimingOffset(126)
    h.controller.setRevealEnabled(true)
    h.controller.setKeepScreenAwake(false)
    runCurrent()
    repeat(3) { advanceTimeBy(20); runCurrent() }

    assertEquals(3, backend.settingsWrites.size)
    val saved = backend.settings.value
    assertEquals(130, saved.globalTimingOffsetMs)
    assertTrue(saved.revealEnabled)
    assertFalse(saved.keepScreenAwake)
    assertFalse(saved.rememberLocalTtml)
    assertTrue(saved.spicyTokenSharingConsent)
    val visible = h.controller.state.value.settings
    assertEquals("bt:route:headphones", visible.activeBluetoothDeviceId)
    assertEquals("Headphones", visible.activeBluetoothDeviceName)
    assertEquals(340, visible.activeBluetoothTimingOffsetMs)
    assertEquals(340, visible.effectiveTimingOffsetMs)
    assertEquals(130, visible.globalTimingOffsetMs)
    assertTrue(visible.revealEnabled)
  }

  @Test
  fun globalAndDeviceTimingAdjustmentsClampAndRoundWithoutChangingEachOther() = runTest {
    val h = Harness(backgroundScope)
    runCurrent()
    h.controller.setGlobalTimingOffset(99_999)
    h.controller.setBluetoothTimingOffset(-126)
    runCurrent()
    assertEquals(5_000, h.backend.settings.value.globalTimingOffsetMs)
    assertEquals(listOf<Int?>(-130), h.backend.deviceWrites)
    h.controller.setBluetoothTimingOffset(null)
    h.controller.setGlobalTimingOffset(-99_999)
    runCurrent()
    assertEquals(-5_000, h.backend.settings.value.globalTimingOffsetMs)
    assertEquals(listOf(-130, null), h.backend.deviceWrites)
  }

  private class Harness(scope: CoroutineScope, val backend: FakeBackend = FakeBackend()) {
    val playback = FakePlayback()
    val controller = MobileController(scope, backend, playback, playbackReady = true)
  }

  private data class ResolveRequest(val track: TrackIdentity, val allowCached: Boolean, val requestId: Long)
  private data class ImportRequest(val track: TrackIdentity, val text: String, val sourceUri: String?)

  private class FakeBackend(initial: AppSettings = AppSettings()) : MobileBackend {
    override val settings = MutableStateFlow(initial)
    override val library = MutableStateFlow(emptyList<StoredLocalTtml>())
    override val diagnosticEvents = MutableStateFlow(emptyList<DiagnosticEvent>())
    override val deviceTiming = MutableStateFlow(MobileDeviceTiming(null, null, null))
    val requests = mutableListOf<ResolveRequest>()
    val imports = mutableListOf<ImportRequest>()
    val settingsWrites = mutableListOf<AppSettings>()
    val deviceWrites = mutableListOf<Int?>()
    var settingsWriteDelayMs = 0L
    var resolve: suspend (ResolveRequest) -> LyricsResolution = { found(it.track) }
    var import: suspend (ImportRequest) -> Unit = {}
    override suspend fun currentSettings() = settings.value
    override suspend fun updateSettings(value: AppSettings) {
      delay(settingsWriteDelayMs)
      settingsWrites += value
      settings.value = value
    }
    override suspend fun resolve(track: TrackIdentity, allowCached: Boolean, requestId: Long): LyricsResolution {
      val request = ResolveRequest(track, allowCached, requestId)
      requests += request
      return resolve(request)
    }
    override suspend fun importTtml(track: TrackIdentity, text: String, sourceUri: String?) {
      val request = ImportRequest(track, text, sourceUri)
      import(request)
      imports += request
    }
    override suspend fun deleteSavedLyrics(trackKey: String) = false
    override suspend fun clearDiagnostics() = Unit
    override suspend fun setDeviceTiming(value: Int?) { deviceWrites += value }
  }

  private class FakePlayback : PlaybackGateway {
    override val snapshots = MutableStateFlow<NowPlayingSnapshot?>(null)
    override fun playPause() = Unit
    override fun previous() = Unit
    override fun next() = Unit
    override fun seekTo(positionMs: Long) = Unit
  }

  private companion object {
    fun ready(controller: MobileController): LyricsDocument = assertIs<LyricsUiStatus.Ready>(controller.state.value.lyrics).document

    fun snapshot(title: String, localSuffix: String = "123") = NowPlayingSnapshot(
      packageName = "com.spotify.music", title = title, artist = "Artist", album = "Album",
      durationMs = 123_000L, positionMs = 1_000L, playbackSpeed = 1f, playbackState = 3,
      artwork = null, capturedAtElapsedMs = 1_000L, rawMediaId = null,
      rawUri = "spotify:local:Artist:Album:$title:$localSuffix", extras = emptyMap(), availableActions = 0L,
    )

    fun queuedAttempt() = ProviderAttempt(LyricsProviderId.SPICY, ProviderAttemptOutcome.QUEUED)

    fun found(track: TrackIdentity, provider: LyricsProviderId = LyricsProviderId.LRCLIB, queuedFirst: Boolean = false): LyricsResolution.Found {
      val document = StaticLyrics(LyricsMetadata(trackUri = track.uri, source = provider.expectedSource), listOf(StaticLyricLine(track.title)))
      return LyricsResolution.Found(document, provider, buildList {
        if (queuedFirst) add(queuedAttempt())
        add(ProviderAttempt(provider, ProviderAttemptOutcome.FOUND, source = provider.expectedSource, syncKind = document.syncKind.name))
      })
    }
  }
}
