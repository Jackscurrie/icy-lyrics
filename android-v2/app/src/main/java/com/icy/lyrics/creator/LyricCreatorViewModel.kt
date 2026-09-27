package com.icy.lyrics.creator

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.icy.lyrics.IcyLyricsApplication
import com.icy.lyrics.controller.AndroidMobileBackend
import com.icy.lyrics.core.lyrics.model.LyricsDocument
import com.icy.lyrics.core.lyrics.model.LyricsSource
import com.icy.lyrics.core.lyrics.provider.LyricsResolution
import com.icy.lyrics.core.lyrics.parser.TtmlParser
import com.icy.lyrics.media.NowPlayingSnapshot
import java.util.ArrayDeque
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

internal enum class CreatorStage {
  CHOOSE,
  EDIT,
  TIME,
  PREVIEW,
}

internal fun CreatorStage.backDestination(): CreatorStage? = when (this) {
  CreatorStage.CHOOSE -> null
  CreatorStage.EDIT -> CreatorStage.CHOOSE
  CreatorStage.TIME -> CreatorStage.EDIT
  CreatorStage.PREVIEW -> CreatorStage.TIME
}

internal data class CreatorExportPayload(
  val id: Long,
  val filename: String,
  val rawTtml: String,
  val exportedAsStaticDraft: Boolean,
  val omittedTimingIssueCount: Int,
)

internal data class CreatorPendingTtmlImport(
  val rawTtml: String,
  val message: String,
  val targetSnapshot: NowPlayingSnapshot,
)

internal data class LyricCreatorUiState(
  val snapshot: NowPlayingSnapshot? = null,
  val project: CreatorProject? = null,
  val editorText: String = "",
  val stage: CreatorStage = CreatorStage.CHOOSE,
  val loading: Boolean = false,
  val loadedLabel: String = "",
  val selectedTargetIndex: Int = 0,
  val ignoreBackground: Boolean = false,
  val headphoneDelayMs: Int = 0,
  val validationIssues: List<CreatorValidationIssue> = emptyList(),
  val previewDocument: LyricsDocument? = null,
  val localLyricsExist: Boolean = false,
  val draftRecovered: Boolean = false,
  val dirty: Boolean = false,
  val message: String? = null,
  val pendingExport: CreatorExportPayload? = null,
  val pendingTtmlImport: CreatorPendingTtmlImport? = null,
  val localSaveGeneration: Long = 0L,
) {
  val projectMatchesPlayback: Boolean
    get() = project != null && snapshot?.identity?.exactStorageKey == project.uri
}

internal class LyricCreatorViewModel(application: Application) : AndroidViewModel(application) {
  private val container = (application as IcyLyricsApplication).container
  private val services = container.services
  private val backend = AndroidMobileBackend(services)
  private val draftStore = CreatorDraftStore(application)
  private val delayStore = CreatorDelayStore(application)
  private val mutableState = MutableStateFlow(LyricCreatorUiState())
  private val undoHistory = ArrayDeque<Pair<CreatorProject, Int>>()
  private var trackLoadJob: Job? = null
  private var autosaveJob: Job? = null
  private var delaySaveJob: Job? = null
  private var exportSequence = 0L

  val state = mutableState.asStateFlow()

  init {
    viewModelScope.launch {
      mutableState.update { it.copy(headphoneDelayMs = delayStore.getDelayMs()) }
    }
    viewModelScope.launch {
      container.mediaTracker.snapshots.collectLatest { snapshot ->
        mutableState.update { it.copy(snapshot = snapshot) }
        if (snapshot != null && mutableState.value.project == null && trackLoadJob?.isActive != true) {
          loadTrack(snapshot, preferDraft = true)
        }
      }
    }
  }

  fun navigate(stage: CreatorStage) {
    if (stage != CreatorStage.CHOOSE && mutableState.value.project == null) return
    if (
      stage == CreatorStage.PREVIEW &&
      mutableState.value.project?.let(::creatorPreviewMode) == CreatorPreviewMode.EMPTY
    ) {
      showMessage("Add lyric text before opening Preview.")
      return
    }
    mutableState.update { it.copy(stage = stage) }
  }

  fun back(): Boolean {
    val previous = mutableState.value.stage.backDestination() ?: return false
    navigate(previous)
    return true
  }

  fun switchToCurrentTrack() {
    val snapshot = mutableState.value.snapshot
    if (snapshot == null) {
      showMessage("Play a song in Spotify first.")
      return
    }
    if (!flushDraftBlocking()) return
    undoHistory.clear()
    loadTrack(snapshot, preferDraft = true)
  }

  fun useCurrentLyrics() {
    val snapshot = mutableState.value.snapshot
    if (snapshot == null) {
      showMessage("Play a song in Spotify first.")
      return
    }
    if (!flushDraftBlocking()) return
    undoHistory.clear()
    loadTrack(snapshot, preferDraft = false)
  }

  fun createBlankForCurrentTrack() {
    val snapshot = mutableState.value.snapshot
    if (snapshot == null) {
      showMessage("Play a song in Spotify first.")
      return
    }
    if (!flushDraftBlocking()) return
    val project = createEmptyCreatorProject(snapshot.identity.exactStorageKey).copy(
      metadata = metadataFrom(snapshot),
      source = CreatorSourceProvenance(CreatorSource.DRAFT, "New mobile draft"),
    )
    undoHistory.clear()
    // Do not replace an existing recovery draft until the user actually edits
    // this blank project.
    replaceProject(project, "New mobile draft", stage = CreatorStage.EDIT, dirty = false)
  }

  fun updateLyricsText(value: String) {
    val project = mutableState.value.project ?: return
    val updated = normalizeBackgroundAttachments(project.reconcileMobilePlainText(value).lines)
    mutateProject(project.copy(lines = updated), editorText = value)
  }

  fun updateTitle(value: String) = updateMetadata { copy(name = value) }

  fun updateArtists(value: String) = updateMetadata {
    copy(artists = value.csvValues())
  }

  fun updateAlbum(value: String) = updateMetadata {
    copy(albums = value.csvValues())
  }

  fun updateSongwriters(value: String) = updateMetadata {
    copy(songwriters = value.csvValues())
  }

  fun updateLanguage(value: String) = updateMetadata { copy(language = value.trim()) }

  fun setBackground(lineIndex: Int, enabled: Boolean) {
    val project = mutableState.value.project ?: return
    if (lineIndex !in project.lines.indices) return
    if (enabled && project.lines.take(lineIndex).none { !it.isBackground }) {
      showMessage("A background line needs an earlier lead line.")
      return
    }
    val lines = project.lines.toMutableList()
    lines[lineIndex] = lines[lineIndex].copy(isBackground = enabled)
    mutateProject(project.copy(lines = normalizeBackgroundAttachments(lines)))
  }

  fun setSecondSpeaker(lineIndex: Int, enabled: Boolean) {
    val project = mutableState.value.project ?: return
    if (lineIndex !in project.lines.indices) return
    val lines = project.lines.toMutableList()
    lines[lineIndex] = lines[lineIndex].copy(isSecondSpeaker = enabled)
    mutateProject(project.copy(lines = lines))
  }

  fun setIgnoreBackground(value: Boolean) {
    mutableState.update { current ->
      val selectedFragmentId = current.project
        ?.let { creatorTimingTargets(it, current.ignoreBackground) }
        ?.getOrNull(current.selectedTargetIndex)
        ?.fragment
        ?.id
      val targets = current.project?.let { creatorTimingTargets(it, value) }.orEmpty()
      val retainedIndex = selectedFragmentId
        ?.let { id -> targets.indexOfFirst { it.fragment.id == id } }
        ?.takeIf { it >= 0 }
      current.copy(
        ignoreBackground = value,
        selectedTargetIndex = (retainedIndex ?: current.selectedTargetIndex)
          .coerceIn(0, targets.lastIndex.coerceAtLeast(0)),
      )
    }
  }

  fun selectTarget(index: Int) {
    val lastIndex = mutableState.value.project
      ?.let { creatorTimingTargets(it, mutableState.value.ignoreBackground).lastIndex }
      ?: return
    mutableState.update { it.copy(selectedTargetIndex = index.coerceIn(0, lastIndex.coerceAtLeast(0))) }
  }

  fun stepTarget(delta: Int) = selectTarget(mutableState.value.selectedTargetIndex + delta)

  fun applyTiming(action: CreatorTimingAction, capturedAtElapsedMs: Long) {
    val current = mutableState.value
    val project = current.project ?: return
    val snapshot = current.snapshot
    if (snapshot == null || snapshot.identity.exactStorageKey != project.uri) {
      showMessage("Return to the song this draft belongs to before recording timing.")
      return
    }
    val rawPosition = snapshot.currentPositionMs(capturedAtElapsedMs)
    rememberUndo(project, current.selectedTargetIndex)
    val result = applyCreatorTimingAction(
      project = project,
      targetIndex = current.selectedTargetIndex,
      action = action,
      playbackPositionMs = rawPosition,
      options = CreatorTimingOptions(
        headphoneDelayMs = current.headphoneDelayMs.toLong(),
        ignoreBackground = current.ignoreBackground,
      ),
    )
    mutateProject(result.project, selectedTargetIndex = result.targetIndex)
  }

  fun clearSelectedTiming() {
    val current = mutableState.value
    val project = current.project ?: return
    val target = creatorTimingTargets(project, current.ignoreBackground)
      .getOrNull(current.selectedTargetIndex) ?: return
    rememberUndo(project, current.selectedTargetIndex)
    mutateProject(
      project.updateFragment(target) { it.copy(startTimeMs = null, endTimeMs = null) },
      selectedTargetIndex = current.selectedTargetIndex,
    )
  }

  fun nudgeSelected(deltaMs: Long) {
    val current = mutableState.value
    val project = current.project ?: return
    val target = creatorTimingTargets(project, current.ignoreBackground)
      .getOrNull(current.selectedTargetIndex) ?: return
    if (target.fragment.startTimeMs == null && target.fragment.endTimeMs == null) return
    rememberUndo(project, current.selectedTargetIndex)
    mutateProject(
      project.updateFragment(target) { fragment ->
        val start = fragment.startTimeMs?.let { (it + deltaMs).coerceAtLeast(0L) }
        val end = fragment.endTimeMs?.let { shifted ->
          maxOf((shifted + deltaMs).coerceAtLeast(0L), start ?: 0L)
        }
        fragment.copy(startTimeMs = start, endTimeMs = end)
      },
      selectedTargetIndex = current.selectedTargetIndex,
    )
  }

  fun clearAllTiming() {
    val current = mutableState.value
    val project = current.project ?: return
    rememberUndo(project, current.selectedTargetIndex)
    val lines = project.lines.map { line ->
      line.copy(
        tokens = line.tokens.map { token ->
          token.copy(fragments = token.fragments.map { it.copy(startTimeMs = null, endTimeMs = null) })
        },
        startTimeMs = null,
        endTimeMs = null,
      )
    }
    mutateProject(project.copy(lines = lines), selectedTargetIndex = 0)
  }

  fun undoTiming() {
    val previous = undoHistory.pollLast() ?: return
    mutateProject(previous.first, selectedTargetIndex = previous.second, recordDirty = true)
  }

  fun setHeadphoneDelay(value: Int) {
    val normalized = CreatorDelayStore.normalize(value)
    mutableState.update { it.copy(headphoneDelayMs = normalized) }
    delaySaveJob?.cancel()
    delaySaveJob = viewModelScope.launch {
      runCatching { delayStore.setDelayMs(normalized) }
        .onFailure { showMessage(it.message ?: "The headphone delay could not be saved.") }
    }
  }

  fun playPause() = container.mediaTracker.playPause()

  fun seekRelative(deltaMs: Long, nowElapsedMs: Long) {
    val snapshot = mutableState.value.snapshot ?: return
    container.mediaTracker.seekTo(snapshot.currentPositionMs(nowElapsedMs) + deltaMs)
  }

  fun seekTo(positionMs: Long) = container.mediaTracker.seekTo(positionMs)

  fun saveDraftNow() {
    val project = mutableState.value.project ?: return
    autosaveJob?.cancel()
    autosaveJob = viewModelScope.launch {
      runCatching { draftStore.save(project.uri, serializeCreatorProjectJson(project)) }
        .onSuccess {
          mutableState.update { current -> current.copy(message = "Draft saved on this phone.") }
        }
        .onFailure { showMessage(it.message ?: "The draft could not be saved.") }
    }
  }

  /** Completes the final atomic draft/settings write before the Activity can be cleared. */
  fun flushDraftBlocking(): Boolean {
    val current = mutableState.value
    val project = current.project
    autosaveJob?.cancel()
    delaySaveJob?.cancel()
    val (draftFailure, delayFailure) = runBlocking {
      var draftError: Throwable? = null
      var delayError: Throwable? = null
      if (project != null && (current.dirty || current.draftRecovered)) {
        draftError = runCatching {
          draftStore.save(project.uri, serializeCreatorProjectJson(project))
        }.exceptionOrNull()
      }
      delayError = runCatching {
        delayStore.setDelayMs(current.headphoneDelayMs)
      }.exceptionOrNull()
      draftError to delayError
    }
    if (draftFailure != null) {
      showMessage(draftFailure.message ?: "The latest Lyric Creator draft could not be saved.")
    } else if (delayFailure != null) {
      showMessage(delayFailure.message ?: "The headphone delay could not be saved.")
    }
    return draftFailure == null
  }

  fun importTtml(uri: Uri) {
    val snapshot = mutableState.value.snapshot
    if (snapshot == null) {
      showMessage("Play the matching Spotify song before opening TTML.")
      return
    }
    viewModelScope.launch {
      mutableState.update { it.copy(loading = true) }
      try {
        val raw = withContext(Dispatchers.IO) {
          getApplication<Application>().contentResolver.openInputStream(uri)
            ?.bufferedReader()
            ?.use { it.readCreatorTextLimited(MAX_IMPORT_CHARS) }
        } ?: error("The selected TTML file was empty or unreadable.")
        val identity = inspectCreatorTtmlIdentity(raw)
        val embeddedSpotifyId = identity.spotifyTrackId
        val currentSpotifyId = snapshot.identity.spotifyTrackId
        val confirmation = when {
          embeddedSpotifyId == null ->
            "This TTML does not identify a Spotify track. Use it for ${snapshot.displayTitle} by ${snapshot.displayArtist}?"
          embeddedSpotifyId != currentSpotifyId ->
            "This TTML identifies Spotify track $embeddedSpotifyId, not the song currently playing. Rebind it to ${snapshot.displayTitle} by ${snapshot.displayArtist}?"
          else -> null
        }
        if (confirmation != null) {
          mutableState.update {
            it.copy(
              loading = false,
              pendingTtmlImport = CreatorPendingTtmlImport(raw, confirmation, snapshot),
            )
          }
          return@launch
        }
        openImportedTtml(raw, snapshot, rebindIdentity = false)
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (error: Throwable) {
        showMessage(error.message ?: "That TTML file could not be opened.")
      } finally {
        mutableState.update { it.copy(loading = false) }
      }
    }
  }

  fun confirmTtmlImport() {
    val pending = mutableState.value.pendingTtmlImport ?: return
    val snapshot = mutableState.value.snapshot
    if (snapshot?.identity?.exactStorageKey != pending.targetSnapshot.identity.exactStorageKey) {
      mutableState.update { it.copy(pendingTtmlImport = null) }
      showMessage("The song changed while that question was open. Return to the intended song and open the TTML again.")
      return
    }
    mutableState.update { it.copy(pendingTtmlImport = null, loading = true) }
    viewModelScope.launch {
      try {
        openImportedTtml(pending.rawTtml, pending.targetSnapshot, rebindIdentity = true)
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (error: Throwable) {
        showMessage(error.message ?: "That TTML file could not be opened.")
      } finally {
        mutableState.update { it.copy(loading = false) }
      }
    }
  }

  fun cancelTtmlImport() {
    mutableState.update { it.copy(pendingTtmlImport = null) }
  }

  fun requestExport() {
    val project = mutableState.value.project ?: return
    val export = runCatching { CreatorTtmlCodec.encodeForExport(project) }.getOrElse {
      showMessage(it.message ?: "This draft contains text that cannot be exported safely.")
      return
    }
    exportSequence += 1L
    mutableState.update {
      it.copy(
        pendingExport = CreatorExportPayload(
          id = exportSequence,
          filename = creatorFilename(project.metadata.name),
          rawTtml = export.rawTtml,
          exportedAsStaticDraft = export.exportedAsStaticDraft,
          omittedTimingIssueCount = export.omittedTimingIssueCount,
        ),
      )
    }
  }

  fun cancelExport() {
    mutableState.update { it.copy(pendingExport = null) }
  }

  fun writeExport(uri: Uri) {
    val payload = mutableState.value.pendingExport ?: return
    viewModelScope.launch {
      try {
        withContext(Dispatchers.IO) {
          getApplication<Application>().contentResolver.openOutputStream(uri, "wt")
            ?.bufferedWriter(Charsets.UTF_8)
            ?.use { it.write(payload.rawTtml) }
            ?: error("Android could not open the selected file.")
        }
        val message = if (payload.exportedAsStaticDraft) {
          "Draft TTML exported as static lyrics. ${payload.omittedTimingIssueCount} unfinished or invalid timing value(s) were omitted, not fabricated."
        } else {
          "Word-synced TTML exported."
        }
        mutableState.update { it.copy(pendingExport = null, message = message) }
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (error: Throwable) {
        mutableState.update { it.copy(pendingExport = null) }
        showMessage(error.message ?: "TTML export failed.")
      }
    }
  }

  fun saveToLocalLyrics() {
    val project = mutableState.value.project ?: return
    viewModelScope.launch {
      mutableState.update { it.copy(loading = true) }
      try {
        val raw = withContext(Dispatchers.Default) { serializeCreatorTtml(project) }
        services.localTtmlProvider.import(
          track = currentTrackFor(project),
          rawTtml = raw,
          sourceUri = "icy-lyrics://lyric-creator",
          origin = "lyric-creator",
        )
        draftStore.save(project.uri, serializeCreatorProjectJson(project))
        mutableState.update {
          it.copy(
            loading = false,
            localLyricsExist = true,
            dirty = false,
            message = "Word-synced TTML saved to Local lyrics.",
            localSaveGeneration = it.localSaveGeneration + 1L,
          )
        }
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (error: Throwable) {
        mutableState.update { it.copy(loading = false) }
        showMessage(error.message ?: "The finished lyrics could not be saved.")
      }
    }
  }

  fun clearMessage() = mutableState.update { it.copy(message = null) }

  private suspend fun openImportedTtml(
    rawTtml: String,
    snapshot: NowPlayingSnapshot,
    rebindIdentity: Boolean,
  ) {
    if (!flushDraftBlocking()) return
    var project = withContext(Dispatchers.Default) {
      parseCreatorTtml(
        rawTtml = rawTtml,
        trackUri = snapshot.identity.exactStorageKey,
        metadataHint = metadataFrom(snapshot),
      )
    }
    if (rebindIdentity) {
      project = project.rebindToTrack(
        trackUri = snapshot.identity.exactStorageKey,
        currentTrackMetadata = metadataFrom(snapshot),
      )
    }
    undoHistory.clear()
    replaceProject(project, "Opened TTML", stage = CreatorStage.EDIT, dirty = true)
  }

  private fun loadTrack(snapshot: NowPlayingSnapshot, preferDraft: Boolean) {
    val track = snapshot.identity
    trackLoadJob?.cancel()
    trackLoadJob = viewModelScope.launch {
      mutableState.update { it.copy(loading = true, message = null) }
      try {
        val localExists = withContext(Dispatchers.IO) {
          services.localTtmlRepository.get(track) != null
        }
        val recovered = if (preferDraft) withContext(Dispatchers.IO) {
          draftStore.load(track.exactStorageKey)
        } else null
        val project: CreatorProject
        val label: String
        val wasRecovered: Boolean
        if (recovered != null) {
          project = deserializeCreatorProjectJson(recovered.serializedProject)
          require(project.uri == track.exactStorageKey) { "The saved draft belongs to another song." }
          label = "Recovered phone draft"
          wasRecovered = true
        } else {
          val resolution = withContext(Dispatchers.IO) {
            backend.resolve(track, allowCached = true, requestId = System.nanoTime())
          }
          if (resolution is LyricsResolution.Found) {
            project = creatorProjectFromLyricsDocument(
              document = resolution.document,
              trackUri = track.exactStorageKey,
              metadataHint = metadataFrom(snapshot),
            )
            label = "Loaded ${project.source.label}"
          } else {
            project = createEmptyCreatorProject(track.exactStorageKey).copy(
              metadata = metadataFrom(snapshot),
              source = CreatorSourceProvenance(CreatorSource.DRAFT, "New mobile draft"),
            )
            label = "No lyrics found — start a new draft"
          }
          wasRecovered = false
        }
        undoHistory.clear()
        replaceProject(
          project = project,
          label = label,
          stage = CreatorStage.CHOOSE,
          dirty = false,
          localExists = localExists,
          draftRecovered = wasRecovered,
        )
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (error: Throwable) {
        val blank = createEmptyCreatorProject(track.exactStorageKey).copy(metadata = metadataFrom(snapshot))
        replaceProject(blank, "New mobile draft", stage = CreatorStage.CHOOSE, dirty = false)
        showMessage(error.message ?: "Lyrics could not be loaded for the creator.")
      } finally {
        mutableState.update { it.copy(loading = false) }
      }
    }
  }

  private fun replaceProject(
    project: CreatorProject,
    label: String,
    stage: CreatorStage,
    dirty: Boolean,
    localExists: Boolean = mutableState.value.localLyricsExist,
    draftRecovered: Boolean = false,
  ) {
    val issues = CreatorTtmlCodec.validate(project)
    mutableState.update {
      it.copy(
        project = project,
        editorText = project.editorText(),
        stage = stage,
        loadedLabel = label,
        selectedTargetIndex = 0,
        validationIssues = issues,
        previewDocument = previewDocument(project, issues),
        localLyricsExist = localExists,
        draftRecovered = draftRecovered,
        dirty = dirty,
      )
    }
    if (dirty) scheduleAutosave(project)
  }

  private fun mutateProject(
    project: CreatorProject,
    editorText: String = mutableState.value.editorText,
    selectedTargetIndex: Int = mutableState.value.selectedTargetIndex,
    recordDirty: Boolean = true,
  ) {
    val issues = CreatorTtmlCodec.validate(project)
    val targetLastIndex = creatorTimingTargets(project, mutableState.value.ignoreBackground).lastIndex
    mutableState.update {
      it.copy(
        project = project,
        editorText = editorText,
        selectedTargetIndex = selectedTargetIndex.coerceIn(0, targetLastIndex.coerceAtLeast(0)),
        validationIssues = issues,
        previewDocument = previewDocument(project, issues),
        dirty = it.dirty || recordDirty,
      )
    }
    scheduleAutosave(project)
  }

  private fun updateMetadata(transform: CreatorMetadata.() -> CreatorMetadata) {
    val project = mutableState.value.project ?: return
    mutateProject(project.copy(metadata = project.metadata.transform()))
  }

  private fun previewDocument(
    project: CreatorProject,
    issues: List<CreatorValidationIssue>,
  ): LyricsDocument? {
    if (issues.isNotEmpty() || creatorPreviewMode(project) != CreatorPreviewMode.FULLY_TIMED) return null
    return runCatching {
      TtmlParser.parse(serializeCreatorTtml(project), project.uri, LyricsSource.LOCAL_TTML)
    }.getOrNull()
  }

  private fun rememberUndo(project: CreatorProject, targetIndex: Int) {
    undoHistory.addLast(project to targetIndex)
    while (undoHistory.size > MAX_UNDO_STEPS) undoHistory.removeFirst()
  }

  private fun scheduleAutosave(project: CreatorProject) {
    if (project.uri.isBlank()) return
    autosaveJob?.cancel()
    autosaveJob = viewModelScope.launch {
      delay(AUTOSAVE_DELAY_MS)
      runCatching { draftStore.save(project.uri, serializeCreatorProjectJson(project)) }
        .onFailure { showMessage(it.message ?: "The recovery draft could not be saved.") }
    }
  }

  private fun currentTrackFor(project: CreatorProject) = mutableState.value.snapshot
    ?.identity
    ?.takeIf { it.exactStorageKey == project.uri }
    ?: com.icy.lyrics.core.lyrics.model.TrackIdentity(
      uri = project.uri,
      title = project.metadata.name,
      artists = project.metadata.artists,
      album = project.metadata.albums.firstOrNull().orEmpty(),
    )

  private fun showMessage(message: String) {
    mutableState.update { it.copy(message = message.take(MAX_MESSAGE_CHARS)) }
  }

  companion object {
    private const val AUTOSAVE_DELAY_MS = 350L
    private const val MAX_IMPORT_CHARS = 2_000_000
    private const val MAX_UNDO_STEPS = 80
    private const val MAX_MESSAGE_CHARS = 1_000
  }
}

private fun metadataFrom(snapshot: NowPlayingSnapshot): CreatorMetadata = CreatorMetadata(
  name = snapshot.displayTitle,
  artists = snapshot.artist?.takeIf(String::isNotBlank)?.let(::listOf).orEmpty(),
  albums = snapshot.album?.takeIf(String::isNotBlank)?.let(::listOf).orEmpty(),
  spotifyTrackId = snapshot.identity.spotifyTrackId.orEmpty(),
)

private fun String.csvValues(): List<String> = split(',')
  .map(String::trim)
  .filter(String::isNotEmpty)
  .distinct()

private fun normalizeBackgroundAttachments(lines: List<CreatorLine>): List<CreatorLine> {
  var lastLeadId: String? = null
  return lines.map { line ->
    if (line.isBackground) line.copy(attachedToLineId = lastLeadId)
    else line.also { lastLeadId = it.id }.copy(attachedToLineId = null)
  }
}

private fun CreatorProject.updateFragment(
  target: CreatorTimingTarget,
  transform: (CreatorFragment) -> CreatorFragment,
): CreatorProject {
  val line = lines.getOrNull(target.lineIndex) ?: return this
  val token = line.tokens.getOrNull(target.tokenIndex) ?: return this
  val fragment = token.fragments.getOrNull(target.fragmentIndex) ?: return this
  val fragments = token.fragments.toMutableList().apply {
    this[target.fragmentIndex] = transform(fragment)
  }
  val tokens = line.tokens.toMutableList().apply {
    this[target.tokenIndex] = token.copy(fragments = fragments)
  }
  val nextLines = lines.toMutableList().apply {
    this[target.lineIndex] = line.copy(tokens = tokens).synchronizeTiming()
  }
  return copy(lines = nextLines)
}

private fun creatorFilename(title: String): String {
  val safe = title.trim()
    .replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_")
    .trim('.', ' ')
    .take(100)
    .ifBlank { "Icy Lyrics" }
  return "$safe.ttml"
}

private fun java.io.Reader.readCreatorTextLimited(maxChars: Int): String {
  val result = StringBuilder(minOf(maxChars, 64 * 1_024))
  val buffer = CharArray(8_192)
  while (true) {
    val count = read(buffer)
    if (count < 0) return result.toString()
    if (result.length + count > maxChars) error("That TTML file is larger than the 2 MB limit.")
    result.append(buffer, 0, count)
  }
}
