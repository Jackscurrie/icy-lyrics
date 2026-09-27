package com.icy.lyrics.creator

import android.app.Activity
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.icy.lyrics.media.NowPlayingSnapshot
import com.icy.lyrics.ui.Artwork
import com.icy.lyrics.ui.LocalIcyUiPlatform
import com.icy.lyrics.ui.LyricsCanvas
import com.icy.lyrics.ui.rememberAndroidIcyUiPlatform
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.roundToInt

/** Phone-only Lyric Creator shared by the Play and personal distributions. */
class LyricCreatorActivity : ComponentActivity() {
  private val viewModel: LyricCreatorViewModel by viewModels()

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    setContent {
      val uiPlatform = rememberAndroidIcyUiPlatform()
      val state by viewModel.state.collectAsStateWithLifecycle()
      val snackbar = remember { SnackbarHostState() }
      val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
      ) { uri -> uri?.let(viewModel::importTtml) }
      val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/ttml+xml"),
      ) { uri ->
        if (uri == null) viewModel.cancelExport() else viewModel.writeExport(uri)
      }

      LaunchedEffect(state.pendingExport?.id) {
        state.pendingExport?.let { exportLauncher.launch(it.filename) }
      }
      LaunchedEffect(state.localSaveGeneration) {
        if (state.localSaveGeneration > 0L) setResult(Activity.RESULT_OK)
      }
      LaunchedEffect(state.message) {
        state.message?.let {
          snackbar.showSnackbar(it)
          viewModel.clearMessage()
        }
      }
      val handleBack: () -> Unit = {
        if (!viewModel.back() && viewModel.flushDraftBlocking()) finish()
      }
      BackHandler(onBack = handleBack)

      CompositionLocalProvider(LocalIcyUiPlatform provides uiPlatform) {
        CreatorTheme {
          LyricCreatorScreen(
          state = state,
          snackbar = snackbar,
          onBack = handleBack,
          onNavigate = viewModel::navigate,
          onSwitchTrack = viewModel::switchToCurrentTrack,
          onUseCurrentLyrics = viewModel::useCurrentLyrics,
          onCreateBlank = viewModel::createBlankForCurrentTrack,
          onOpenTtml = {
            importLauncher.launch(
              arrayOf("application/ttml+xml", "application/xml", "text/xml", "text/plain"),
            )
          },
          onLyricsText = viewModel::updateLyricsText,
          onTitle = viewModel::updateTitle,
          onArtists = viewModel::updateArtists,
          onAlbum = viewModel::updateAlbum,
          onSongwriters = viewModel::updateSongwriters,
          onLanguage = viewModel::updateLanguage,
          onBackground = viewModel::setBackground,
          onSecondSpeaker = viewModel::setSecondSpeaker,
          onSelectTarget = viewModel::selectTarget,
          onStepTarget = viewModel::stepTarget,
          onTiming = viewModel::applyTiming,
          onUndo = viewModel::undoTiming,
          onClearSelected = viewModel::clearSelectedTiming,
          onClearAll = viewModel::clearAllTiming,
          onNudge = viewModel::nudgeSelected,
          onIgnoreBackground = viewModel::setIgnoreBackground,
          onDelay = viewModel::setHeadphoneDelay,
          onPlayPause = viewModel::playPause,
          onSeekRelative = viewModel::seekRelative,
          onSeek = viewModel::seekTo,
          onSaveDraft = viewModel::saveDraftNow,
          onSaveLocal = viewModel::saveToLocalLyrics,
          onExport = viewModel::requestExport,
          )
          state.pendingTtmlImport?.let { pending ->
            AlertDialog(
            onDismissRequest = viewModel::cancelTtmlImport,
            title = { Text("Use this TTML for the current song?") },
            text = { Text(pending.message) },
            confirmButton = {
              Button(onClick = viewModel::confirmTtmlImport) { Text("Use for current song") }
            },
            dismissButton = {
              TextButton(onClick = viewModel::cancelTtmlImport) { Text("Cancel") }
            },
            )
          }
        }
      }
    }
  }

  override fun onStop() {
    viewModel.flushDraftBlocking()
    super.onStop()
  }
}

@Composable
private fun CreatorTheme(content: @Composable () -> Unit) {
  MaterialTheme(
    colorScheme = darkColorScheme(
      primary = Color(0xFF8FD7FF),
      onPrimary = Color(0xFF062336),
      secondary = Color(0xFFC8E6FF),
      background = Color(0xFF03060B),
      surface = Color(0xFF152131),
      surfaceVariant = Color(0xFF21344A),
      error = Color(0xFFFF7B83),
      onError = Color(0xFF360006),
    ),
    content = content,
  )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LyricCreatorScreen(
  state: LyricCreatorUiState,
  snackbar: SnackbarHostState,
  onBack: () -> Unit,
  onNavigate: (CreatorStage) -> Unit,
  onSwitchTrack: () -> Unit,
  onUseCurrentLyrics: () -> Unit,
  onCreateBlank: () -> Unit,
  onOpenTtml: () -> Unit,
  onLyricsText: (String) -> Unit,
  onTitle: (String) -> Unit,
  onArtists: (String) -> Unit,
  onAlbum: (String) -> Unit,
  onSongwriters: (String) -> Unit,
  onLanguage: (String) -> Unit,
  onBackground: (Int, Boolean) -> Unit,
  onSecondSpeaker: (Int, Boolean) -> Unit,
  onSelectTarget: (Int) -> Unit,
  onStepTarget: (Int) -> Unit,
  onTiming: (CreatorTimingAction, Long) -> Unit,
  onUndo: () -> Unit,
  onClearSelected: () -> Unit,
  onClearAll: () -> Unit,
  onNudge: (Long) -> Unit,
  onIgnoreBackground: (Boolean) -> Unit,
  onDelay: (Int) -> Unit,
  onPlayPause: () -> Unit,
  onSeekRelative: (Long, Long) -> Unit,
  onSeek: (Long) -> Unit,
  onSaveDraft: () -> Unit,
  onSaveLocal: () -> Unit,
  onExport: () -> Unit,
) {
  var showSettings by remember { mutableStateOf(false) }
  Scaffold(
    containerColor = Color.Transparent,
    snackbarHost = { SnackbarHost(snackbar) },
    topBar = {
      TopAppBar(
        title = {
          Column {
            Text("Lyric Creator", fontWeight = FontWeight.Bold)
            state.project?.metadata?.name?.takeIf(String::isNotBlank)?.let {
              Text(
                it,
                style = MaterialTheme.typography.labelMedium,
                color = Color.White.copy(alpha = 0.62f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
              )
            }
          }
        },
        navigationIcon = {
          IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
          }
        },
        actions = {
          IconButton(onClick = { showSettings = true }) {
            Icon(Icons.Default.Settings, "Lyric Creator settings")
          }
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
      )
    },
  ) { padding ->
    Box(
      Modifier
        .fillMaxSize()
        .background(
          Brush.verticalGradient(
            listOf(Color(0xFF10243A), Color(0xFF070A10), Color.Black),
          ),
        )
        .padding(padding)
        .imePadding(),
    ) {
      Column(Modifier.fillMaxSize()) {
        CreatorStepper(
          selected = state.stage,
          enabled = state.project != null,
          onNavigate = onNavigate,
        )
        AnimatedContent(
          targetState = state.stage,
          transitionSpec = { fadeIn() togetherWith fadeOut() },
          modifier = Modifier.fillMaxSize(),
          label = "creator-stage",
        ) { stage ->
          when (stage) {
            CreatorStage.CHOOSE -> ChooseCreatorSource(
              state,
              onContinue = { onNavigate(CreatorStage.EDIT) },
              onSwitchTrack,
              onUseCurrentLyrics,
              onCreateBlank,
              onOpenTtml,
            )
            CreatorStage.EDIT -> EditCreatorLyrics(
              state,
              onLyricsText,
              onTitle,
              onArtists,
              onAlbum,
              onSongwriters,
              onLanguage,
              onBackground,
              onSecondSpeaker,
              onSaveDraft,
              onContinue = { onNavigate(CreatorStage.TIME) },
            )
            CreatorStage.TIME -> TimeCreatorLyrics(
              state,
              onSelectTarget,
              onStepTarget,
              onTiming,
              onUndo,
              onClearSelected,
              onClearAll,
              onNudge,
              onPlayPause,
              onSeekRelative,
              onSeek,
              onPreview = { onNavigate(CreatorStage.PREVIEW) },
            )
            CreatorStage.PREVIEW -> PreviewCreatorLyrics(
              state,
              onPlayPause,
              onSeekRelative,
              onSeek,
              onBackToTiming = { onNavigate(CreatorStage.TIME) },
              onSaveLocal,
              onExport,
            )
          }
        }
      }
      if (state.loading) {
        Surface(
          color = Color.Black.copy(alpha = 0.62f),
          modifier = Modifier.fillMaxSize(),
        ) {
          Box(contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
          }
        }
      }
    }
  }
  if (showSettings) {
    CreatorSettingsSheet(
      state = state,
      onDismiss = { showSettings = false },
      onIgnoreBackground = onIgnoreBackground,
      onDelay = onDelay,
    )
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CreatorSettingsSheet(
  state: LyricCreatorUiState,
  onDismiss: () -> Unit,
  onIgnoreBackground: (Boolean) -> Unit,
  onDelay: (Int) -> Unit,
) {
  ModalBottomSheet(
    onDismissRequest = onDismiss,
    containerColor = Color(0xFF121D2A),
    contentColor = Color.White,
  ) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .navigationBarsPadding()
        .padding(horizontal = 20.dp, vertical = 8.dp),
      verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
      Text("Creator settings", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Black)
      Text(
        "These settings affect timing capture and preview only. Saved TTML always uses the song's canonical timeline.",
        style = MaterialTheme.typography.bodyMedium,
        color = Color.White.copy(alpha = 0.78f),
      )
      HeadphoneDelayControl(state.headphoneDelayMs, onDelay)
      Surface(
        shape = RoundedCornerShape(16.dp),
        color = Color(0xFF1C2A3A),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.16f)),
      ) {
        Row(
          verticalAlignment = Alignment.CenterVertically,
          modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
          Column(Modifier.weight(1f)) {
            Text("Skip background while timing", fontWeight = FontWeight.Bold)
            Text(
              "Background vocals remain in the project but timing buttons move through lead lyrics only.",
              style = MaterialTheme.typography.bodySmall,
              color = Color.White.copy(alpha = 0.72f),
            )
          }
          Spacer(Modifier.width(12.dp))
          Switch(checked = state.ignoreBackground, onCheckedChange = onIgnoreBackground)
        }
      }
      Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp)) {
        Text("Done")
      }
      Spacer(Modifier.height(4.dp))
    }
  }
}

@Composable
private fun CreatorStepper(
  selected: CreatorStage,
  enabled: Boolean,
  onNavigate: (CreatorStage) -> Unit,
) {
  val labels = listOf(
    CreatorStage.CHOOSE to "Choose",
    CreatorStage.EDIT to "Edit",
    CreatorStage.TIME to "Time",
    CreatorStage.PREVIEW to "Preview",
  )
  Row(
    modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
    horizontalArrangement = Arrangement.spacedBy(6.dp),
  ) {
    labels.forEach { (stage, label) ->
      val active = selected == stage
      Surface(
        onClick = { if (stage == CreatorStage.CHOOSE || enabled) onNavigate(stage) },
        enabled = stage == CreatorStage.CHOOSE || enabled,
        shape = RoundedCornerShape(50),
        color = if (active) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.08f),
        contentColor = if (active) MaterialTheme.colorScheme.onPrimary else Color.White.copy(alpha = 0.72f),
        modifier = Modifier.weight(1f),
      ) {
        Text(
          label,
          modifier = Modifier.padding(vertical = 9.dp),
          textAlign = TextAlign.Center,
          style = MaterialTheme.typography.labelMedium,
          fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
        )
      }
    }
  }
}

@Composable
private fun ChooseCreatorSource(
  state: LyricCreatorUiState,
  onContinue: () -> Unit,
  onSwitchTrack: () -> Unit,
  onUseCurrentLyrics: () -> Unit,
  onCreateBlank: () -> Unit,
  onOpenTtml: () -> Unit,
) {
  LazyColumn(
    contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 32.dp),
    verticalArrangement = Arrangement.spacedBy(14.dp),
    modifier = Modifier.fillMaxSize(),
  ) {
    item {
      CreatorCard {
        Text("Current song", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))
        val snapshot = state.snapshot
        if (snapshot == null) {
          Text("Play a song in Spotify, then return here.", color = Color.White.copy(alpha = 0.7f))
        } else {
          Row(verticalAlignment = Alignment.CenterVertically) {
            Artwork(snapshot.artwork, Modifier.size(76.dp))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
              Text(snapshot.displayTitle, fontWeight = FontWeight.Bold, fontSize = 19.sp)
              Text(snapshot.displayArtist, color = Color.White.copy(alpha = 0.65f))
            }
          }
        }
      }
    }
    state.project?.let { project ->
      item {
        CreatorCard {
          Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
              Text("Ready to edit", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
              Text(state.loadedLabel, color = Color.White.copy(alpha = 0.66f))
            }
            if (state.draftRecovered) AssistChip(onClick = {}, label = { Text("Draft recovered") })
          }
          Spacer(Modifier.height(10.dp))
          Text(
            "${project.lines.size} lines · ${creatorTimingTargets(project).size} timing parts",
            style = MaterialTheme.typography.bodySmall,
            color = Color.White.copy(alpha = 0.58f),
          )
          if (!state.projectMatchesPlayback) {
            Spacer(Modifier.height(10.dp))
            Text(
              "Spotify is now playing a different song. Timing stays locked to protect this draft.",
              color = Color(0xFFFFC66D),
              fontWeight = FontWeight.SemiBold,
            )
            TextButton(onClick = onSwitchTrack) { Text("Switch creator to current song") }
          }
          Spacer(Modifier.height(12.dp))
          Button(onClick = onContinue, modifier = Modifier.fillMaxWidth().height(52.dp)) {
            Text("Continue with these lyrics")
          }
        }
      }
    }
    item {
      CreatorCard {
        Text("Start another way", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(
          "Load the best enabled source again, open a TTML file, or begin with blank lyrics.",
          color = Color.White.copy(alpha = 0.62f),
          style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(10.dp))
        Button(onClick = onUseCurrentLyrics, modifier = Modifier.fillMaxWidth()) {
          Icon(Icons.Default.Refresh, null)
          Spacer(Modifier.width(8.dp))
          Text("Load current lyrics")
        }
        OutlinedButton(onClick = onOpenTtml, modifier = Modifier.fillMaxWidth()) {
          Icon(Icons.Default.FolderOpen, null)
          Spacer(Modifier.width(8.dp))
          Text("Open TTML")
        }
        TextButton(onClick = onCreateBlank, modifier = Modifier.fillMaxWidth()) {
          Text("Start a blank draft")
        }
      }
    }
  }
}

@Composable
private fun EditCreatorLyrics(
  state: LyricCreatorUiState,
  onLyricsText: (String) -> Unit,
  onTitle: (String) -> Unit,
  onArtists: (String) -> Unit,
  onAlbum: (String) -> Unit,
  onSongwriters: (String) -> Unit,
  onLanguage: (String) -> Unit,
  onBackground: (Int, Boolean) -> Unit,
  onSecondSpeaker: (Int, Boolean) -> Unit,
  onSaveDraft: () -> Unit,
  onContinue: () -> Unit,
) {
  val project = state.project ?: return
  LazyColumn(
    contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 40.dp),
    verticalArrangement = Arrangement.spacedBy(14.dp),
    modifier = Modifier.fillMaxSize(),
  ) {
    item {
      CreatorCard {
        Text("Lyrics", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(
          "Paste one lyric line per row. Normal spaces create separate timing buttons. Put \\ inside a word to time its syllables separately.",
          style = MaterialTheme.typography.bodySmall,
          color = Color.White.copy(alpha = 0.64f),
        )
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
          value = state.editorText,
          onValueChange = onLyricsText,
          modifier = Modifier.fillMaxWidth().height(300.dp),
          label = { Text("Song lyrics") },
          placeholder = { Text("First line\nSecond line") },
          keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
        )
      }
    }
    item {
      CreatorCard {
        Text("Track details", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        CreatorField("Title", project.metadata.name, onTitle)
        CreatorField("Artists (comma separated)", project.metadata.artists.joinToString(), onArtists)
        CreatorField("Album", project.metadata.albums.joinToString(), onAlbum)
        CreatorField("Songwriters (comma separated)", project.metadata.songwriters.joinToString(), onSongwriters)
        CreatorField("Language code", project.metadata.language, onLanguage)
      }
    }
    if (project.lines.isNotEmpty()) {
      item {
        Text(
          "Line roles",
          style = MaterialTheme.typography.titleMedium,
          fontWeight = FontWeight.Bold,
          modifier = Modifier.semantics { heading() },
        )
        Text(
          "Background lines attach to the nearest lead line above them.",
          style = MaterialTheme.typography.bodySmall,
          color = Color.White.copy(alpha = 0.58f),
        )
      }
      itemsIndexed(project.lines, key = { _, line -> line.id }) { index, line ->
        Surface(
          shape = RoundedCornerShape(16.dp),
          color = Color.White.copy(alpha = 0.055f),
          modifier = Modifier.fillMaxWidth(),
        ) {
          Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
              "${index + 1}. ${line.text().ifBlank { "Empty line" }}",
              maxLines = 2,
              overflow = TextOverflow.Ellipsis,
              fontWeight = FontWeight.SemiBold,
            )
            ToggleLine("Background vocal", line.isBackground) { onBackground(index, it) }
            ToggleLine("Speaker 2 / opposite side", line.isSecondSpeaker) {
              onSecondSpeaker(index, it)
            }
          }
        }
      }
    }
    item {
      Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedButton(onClick = onSaveDraft, modifier = Modifier.weight(1f).height(52.dp)) {
          Icon(Icons.Default.Save, null)
          Spacer(Modifier.width(6.dp))
          Text("Save draft")
        }
        Button(
          onClick = onContinue,
          enabled = creatorTimingTargets(project).isNotEmpty(),
          modifier = Modifier.weight(1f).height(52.dp),
        ) {
          Text("Time words")
          Spacer(Modifier.width(4.dp))
          Icon(Icons.Default.ChevronRight, null)
        }
      }
    }
  }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TimeCreatorLyrics(
  state: LyricCreatorUiState,
  onSelectTarget: (Int) -> Unit,
  onStepTarget: (Int) -> Unit,
  onTiming: (CreatorTimingAction, Long) -> Unit,
  onUndo: () -> Unit,
  onClearSelected: () -> Unit,
  onClearAll: () -> Unit,
  onNudge: (Long) -> Unit,
  onPlayPause: () -> Unit,
  onSeekRelative: (Long, Long) -> Unit,
  onSeek: (Long) -> Unit,
  onPreview: () -> Unit,
) {
  val project = state.project ?: return
  val targets = remember(project, state.ignoreBackground) {
    creatorTimingTargets(project, state.ignoreBackground)
  }
  val target = targets.getOrNull(state.selectedTargetIndex)
  val listState = rememberLazyListState()
  val positionMs = rememberCreatorPosition(state.snapshot)
  val correctedPosition = CreatorDelayStore.correctedTimingPositionMs(positionMs, state.headphoneDelayMs)
  val playback = remember(project, correctedPosition) {
    creatorPlaybackActivity(project, correctedPosition)
  }
  val timedCount = targets.count {
    it.fragment.startTimeMs != null && it.fragment.endTimeMs != null &&
      it.fragment.endTimeMs > it.fragment.startTimeMs
  }
  val targetByFragment = remember(targets) {
    targets.mapIndexed { index, item -> item.fragment.id to index }.toMap()
  }

  LaunchedEffect(target?.lineIndex) {
    target?.lineIndex?.let { listState.animateScrollToItem((it + 1).coerceAtLeast(1)) }
  }

  BoxWithConstraints(Modifier.fillMaxSize()) {
    val controlMaxHeight = minOf(460.dp, (maxHeight - 96.dp).coerceAtLeast(160.dp))
    Column(Modifier.fillMaxSize()) {
    if (!state.projectMatchesPlayback) {
      Surface(color = Color(0xFF6E4516), modifier = Modifier.fillMaxWidth()) {
        Text(
          "Timing is paused because Spotify is playing another song. Return to ${project.metadata.name} to continue.",
          modifier = Modifier.padding(12.dp),
          fontWeight = FontWeight.SemiBold,
        )
      }
    }
    LazyColumn(
      state = listState,
      contentPadding = PaddingValues(14.dp, 8.dp, 14.dp, 12.dp),
      verticalArrangement = Arrangement.spacedBy(10.dp),
      modifier = Modifier.weight(1f),
    ) {
      item {
        CreatorFocusCard(targets, state.selectedTargetIndex, correctedPosition, timedCount)
      }
      itemsIndexed(project.lines, key = { _, line -> line.id }) { lineIndex, line ->
        val lineTargetIndexes = line.tokens.flatMap { token ->
          token.fragments.mapNotNull { fragment -> targetByFragment[fragment.id] }
        }
        val lineHasInvalidTiming = lineTargetIndexes.any { index ->
          classifyCreatorTimingTarget(targets, index).isInvalidForCreatorDisplay()
        }
        Surface(
          shape = RoundedCornerShape(16.dp),
          color = if (target?.lineIndex == lineIndex) {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
          } else if (lineHasInvalidTiming) {
            Color(0xFF351C25)
          } else Color(0xFF152131),
          border = if (target?.lineIndex == lineIndex) {
            androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.55f))
          } else if (lineHasInvalidTiming) {
            androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f))
          } else androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.1f)),
          modifier = Modifier.fillMaxWidth(),
        ) {
          Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
              Text(
                "Line ${lineIndex + 1}",
                style = MaterialTheme.typography.labelMedium,
                color = Color.White.copy(alpha = 0.58f),
                modifier = Modifier.weight(1f),
              )
              if (line.isBackground) Text("BACKGROUND", color = Color(0xFFB7D7FF), fontSize = 10.sp)
              if (line.isSecondSpeaker) Text(" · SPEAKER 2", color = Color(0xFFC8B7FF), fontSize = 10.sp)
            }
            Spacer(Modifier.height(7.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
              line.tokens.forEach { token ->
                token.fragments.forEach { fragment ->
                  val index = targetByFragment[fragment.id]
                  if (index != null) {
                    val selected = index == state.selectedTargetIndex
                    val playing = fragment.id in playback.fragmentIds
                    CreatorTimingTargetChip(
                      label = fragment.text.ifBlank { "Empty" },
                      status = classifyCreatorTimingTarget(targets, index),
                      selected = selected,
                      playing = playing,
                      onClick = { onSelectTarget(index) },
                    )
                  }
                }
              }
            }
          }
        }
      }
    }

    Surface(
      color = Color(0xF20A0E14),
      tonalElevation = 8.dp,
      modifier = Modifier
        .fillMaxWidth()
        .heightIn(max = controlMaxHeight)
        .navigationBarsPadding(),
    ) {
      Column(
        Modifier
          .verticalScroll(rememberScrollState())
          .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(9.dp),
      ) {
        CreatorTransport(
          snapshot = state.snapshot,
          positionMs = positionMs,
          onPlayPause = onPlayPause,
          onSeekRelative = onSeekRelative,
          onSeek = onSeek,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          TimingCaptureButton(
            label = "Start",
            color = Color(0xFF1B78A6),
            enabled = state.projectMatchesPlayback && target != null,
            modifier = Modifier.weight(1f),
          ) { onTiming(CreatorTimingAction.START, it) }
          TimingCaptureButton(
            label = "Commit",
            color = Color(0xFF3B67C5),
            enabled = state.projectMatchesPlayback && target != null,
            modifier = Modifier.weight(1.25f),
          ) { onTiming(CreatorTimingAction.END_AND_NEXT, it) }
          TimingCaptureButton(
            label = "End",
            color = Color(0xFF614BA9),
            enabled = state.projectMatchesPlayback && target != null,
            modifier = Modifier.weight(1f),
          ) { onTiming(CreatorTimingAction.END, it) }
        }
        Row(
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically,
          modifier = Modifier.fillMaxWidth(),
        ) {
          IconButton(onClick = { onStepTarget(-1) }) { Icon(Icons.Default.ChevronLeft, "Previous word") }
          IconButton(onClick = onUndo) { Icon(Icons.AutoMirrored.Filled.Undo, "Undo timing") }
          TextButton(onClick = { onNudge(-10L) }) { Text("−10 ms") }
          TextButton(onClick = { onNudge(10L) }) { Text("+10 ms") }
          IconButton(onClick = onClearSelected) { Icon(Icons.Default.DeleteSweep, "Clear selected timing") }
          IconButton(onClick = { onStepTarget(1) }) { Icon(Icons.Default.ChevronRight, "Next word") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          TextButton(onClick = onClearAll, modifier = Modifier.weight(1f)) { Text("Clear all timing") }
          Button(
            onClick = onPreview,
            enabled = creatorPreviewMode(project) != CreatorPreviewMode.EMPTY,
            modifier = Modifier.weight(1f),
          ) {
            Text("Preview")
            Icon(Icons.Default.ChevronRight, null)
          }
        }
      }
    }
  }
  }
}

@Composable
private fun CreatorFocusCard(
  targets: List<CreatorTimingTarget>,
  selectedIndex: Int,
  correctedPosition: Long,
  timedCount: Int,
) {
  val target = targets.getOrNull(selectedIndex)
  val status = classifyCreatorTimingTarget(targets, selectedIndex)
  CreatorCard {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Text(
        "${timedCount}/${targets.size} timed",
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.weight(1f),
      )
      Text(formatCreatorTime(correctedPosition), fontWeight = FontWeight.Bold)
    }
    Spacer(Modifier.height(8.dp))
    Text(
      targets.getOrNull(selectedIndex - 1)?.label.orEmpty(),
      color = Color.White.copy(alpha = 0.34f),
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )
    Text(
      target?.label ?: "No timing target",
      style = MaterialTheme.typography.headlineLarge,
      fontWeight = FontWeight.Black,
      color = Color.White,
      modifier = Modifier.fillMaxWidth(),
      textAlign = TextAlign.Center,
    )
    Text(
      targets.getOrNull(selectedIndex + 1)?.label.orEmpty(),
      color = Color.White.copy(alpha = 0.34f),
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
      textAlign = TextAlign.End,
      modifier = Modifier.fillMaxWidth(),
    )
    target?.let {
      Text(
        "${it.fragment.startTimeMs?.let(::formatCreatorTime) ?: "—"}  →  ${it.fragment.endTimeMs?.let(::formatCreatorTime) ?: "—"}",
        modifier = Modifier.fillMaxWidth(),
        textAlign = TextAlign.Center,
        style = MaterialTheme.typography.bodySmall,
        color = if (status.isInvalidForCreatorDisplay()) MaterialTheme.colorScheme.error else Color.White.copy(alpha = 0.76f),
      )
      Text(
        status.creatorDisplayLabel(),
        modifier = Modifier.fillMaxWidth(),
        textAlign = TextAlign.Center,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Bold,
        color = if (status.isInvalidForCreatorDisplay()) MaterialTheme.colorScheme.error else Color(0xFF7FE0B5),
      )
    }
  }
}

@Composable
private fun CreatorTimingTargetChip(
  label: String,
  status: CreatorTimingTargetStatus,
  selected: Boolean,
  playing: Boolean,
  onClick: () -> Unit,
) {
  val container = when {
    playing -> Color(0xFF15547A)
    status == CreatorTimingTargetStatus.TIMED -> Color(0xFF163D35)
    status == CreatorTimingTargetStatus.PARTIAL -> Color(0xFF5A381F)
    status == CreatorTimingTargetStatus.OVERLAP -> Color(0xFF5B254A)
    else -> Color(0xFF572630)
  }
  val outline = when {
    selected -> MaterialTheme.colorScheme.primary
    status.isInvalidForCreatorDisplay() -> MaterialTheme.colorScheme.error.copy(alpha = 0.7f)
    else -> Color.White.copy(alpha = 0.18f)
  }
  Surface(
    onClick = onClick,
    shape = RoundedCornerShape(12.dp),
    color = container,
    contentColor = Color.White,
    border = androidx.compose.foundation.BorderStroke(if (selected) 2.dp else 1.dp, outline),
  ) {
    Column(Modifier.padding(horizontal = 10.dp, vertical = 7.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        if (playing) {
          Box(Modifier.size(7.dp).clip(CircleShape).background(Color.White))
          Spacer(Modifier.width(6.dp))
        }
        Text(label, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
      }
      Text(
        status.creatorDisplayLabel(),
        style = MaterialTheme.typography.labelSmall,
        fontSize = 9.sp,
        color = if (status.isInvalidForCreatorDisplay()) Color(0xFFFFB6BC) else Color(0xFF9BE7C5),
      )
    }
  }
}

@Composable
private fun TimingCaptureButton(
  label: String,
  color: Color,
  enabled: Boolean,
  modifier: Modifier = Modifier,
  onCapturedTap: (Long) -> Unit,
) {
  val haptic = LocalHapticFeedback.current
  val interaction = if (enabled) {
    Modifier.pointerInput(onCapturedTap) {
      detectTapGestures(
        onPress = {
          val capturedAt = SystemClock.elapsedRealtime()
          if (tryAwaitRelease()) {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            onCapturedTap(capturedAt)
          }
        },
      )
    }
  } else Modifier
  Surface(
    shape = RoundedCornerShape(18.dp),
    color = if (enabled) color else Color.White.copy(alpha = 0.05f),
    contentColor = if (enabled) Color.White else Color.White.copy(alpha = 0.32f),
    modifier = modifier
      .heightIn(min = 72.dp)
      .then(interaction)
      .semantics {
        role = Role.Button
        onClick(label) {
          if (enabled) {
            onCapturedTap(SystemClock.elapsedRealtime())
            true
          } else false
        }
      },
  ) {
    Column(
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.Center,
      modifier = Modifier.fillMaxSize().padding(horizontal = 5.dp),
    ) {
      Text(label, fontWeight = FontWeight.ExtraBold, textAlign = TextAlign.Center)
    }
  }
}

@Composable
private fun HeadphoneDelayControl(value: Int, onChange: (Int) -> Unit) {
  var expanded by remember { mutableStateOf(false) }
  Surface(
    shape = RoundedCornerShape(14.dp),
    color = Color(0xFF1C2A3A),
    border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.16f)),
  ) {
    Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
          Text("Headphone audio delay", fontWeight = FontWeight.Bold)
          Text(
            "$value ms · taps are recorded this much earlier",
            style = MaterialTheme.typography.bodySmall,
            color = Color.White.copy(alpha = 0.76f),
          )
        }
        TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Done" else "Adjust") }
      }
      if (expanded) {
        Slider(
          value = value.toFloat(),
          onValueChange = { onChange((it / 10f).roundToInt() * 10) },
          valueRange = 0f..1500f,
          steps = 149,
        )
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
          TextButton(onClick = { onChange((value - 10).coerceAtLeast(0)) }) { Text("−10 ms") }
          TextButton(onClick = { onChange((value + 10).coerceAtMost(1500)) }) { Text("+10 ms") }
          TextButton(onClick = { onChange(0) }) { Text("Reset") }
        }
      }
    }
  }
}

@Composable
private fun CreatorTransport(
  snapshot: NowPlayingSnapshot?,
  positionMs: Long,
  onPlayPause: () -> Unit,
  onSeekRelative: (Long, Long) -> Unit,
  onSeek: (Long) -> Unit,
) {
  val duration = snapshot?.durationMs?.coerceAtLeast(1L) ?: 1L
  var dragPosition by remember(snapshot?.identity?.exactStorageKey) { mutableStateOf<Long?>(null) }
  val shown = dragPosition ?: positionMs.coerceIn(0L, duration)
  Column {
    Slider(
      value = shown.toFloat(),
      onValueChange = { dragPosition = it.toLong() },
      onValueChangeFinished = {
        dragPosition?.let(onSeek)
        dragPosition = null
      },
      valueRange = 0f..duration.toFloat(),
      enabled = snapshot != null,
    )
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
      Text(formatCreatorTime(shown), style = MaterialTheme.typography.labelSmall)
      Text(formatCreatorTime(duration), style = MaterialTheme.typography.labelSmall)
    }
    Row(
      modifier = Modifier.fillMaxWidth(),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.Center,
    ) {
      OutlinedButton(onClick = { onSeekRelative(-2_000L, SystemClock.elapsedRealtime()) }) { Text("−2s") }
      Spacer(Modifier.width(12.dp))
      FilledIconButton(onClick = onPlayPause, modifier = Modifier.size(50.dp)) {
        Icon(
          if (snapshot?.isPlaying == true) Icons.Default.Pause else Icons.Default.PlayArrow,
          if (snapshot?.isPlaying == true) "Pause" else "Play",
        )
      }
      Spacer(Modifier.width(12.dp))
      OutlinedButton(onClick = { onSeekRelative(2_000L, SystemClock.elapsedRealtime()) }) { Text("+2s") }
    }
  }
}

@Composable
private fun PreviewCreatorLyrics(
  state: LyricCreatorUiState,
  onPlayPause: () -> Unit,
  onSeekRelative: (Long, Long) -> Unit,
  onSeek: (Long) -> Unit,
  onBackToTiming: () -> Unit,
  onSaveLocal: () -> Unit,
  onExport: () -> Unit,
) {
  val project = state.project ?: return
  val position = rememberCreatorPosition(state.snapshot)
  val corrected = CreatorDelayStore.correctedTimingPositionMs(position, state.headphoneDelayMs)
  var confirmOverwrite by remember { mutableStateOf(false) }
  val previewMode = creatorPreviewMode(project)
  val canSaveLocally = state.previewDocument != null && state.validationIssues.isEmpty()
  val allTargets = remember(project) { creatorTimingTargets(project) }
  val timedParts = allTargets.count { creatorFragmentHasValidTiming(it.fragment) }

  Column(
    modifier = Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 8.dp),
    verticalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    val document = state.previewDocument
    Surface(
      shape = RoundedCornerShape(22.dp),
      color = Color(0xFF0B111A),
      border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.16f)),
      modifier = Modifier.fillMaxWidth().weight(1f),
    ) {
      if (document != null) {
        LyricsCanvas(
          document = document,
          positionMs = corrected,
          rawPositionMs = corrected,
          durationMs = state.snapshot?.durationMs,
          reveal = false,
          focusPresentation = false,
          modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp),
          onSeek = onSeek,
        )
      } else {
        CreatorDraftPreview(
          project = project,
          positionMs = corrected,
          onSeek = onSeek,
          modifier = Modifier.fillMaxSize(),
        )
      }
    }
    if (previewMode != CreatorPreviewMode.FULLY_TIMED || document == null) {
      Surface(
        shape = RoundedCornerShape(14.dp),
        color = Color(0xFF3A2519),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFFFB56B).copy(alpha = 0.55f)),
      ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 9.dp)) {
          Text("Draft preview", color = Color(0xFFFFD19A), fontWeight = FontWeight.Bold)
          Text(
            "Timed parts animate. Missing or invalid timing stays visible and static; no timing is invented. Until timing is finished, Export writes a static draft TTML.",
            style = MaterialTheme.typography.bodySmall,
            color = Color.White.copy(alpha = 0.82f),
          )
          TextButton(onClick = onBackToTiming) { Text("Back to timing") }
        }
      }
    }
    CreatorTransport(state.snapshot, position, onPlayPause, onSeekRelative, onSeek)
    Text(
      "Preview compensates for your ${state.headphoneDelayMs} ms headphone delay. Saved TTML keeps canonical song timing.",
      style = MaterialTheme.typography.bodySmall,
      color = Color.White.copy(alpha = 0.74f),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      Button(
        onClick = { if (state.localLyricsExist) confirmOverwrite = true else onSaveLocal() },
        enabled = canSaveLocally,
        modifier = Modifier.weight(1f).heightIn(min = 52.dp),
      ) {
        Icon(Icons.Default.Check, null)
        Spacer(Modifier.width(6.dp))
        Text(
          when {
            !canSaveLocally -> "Finish to save"
            state.localLyricsExist -> "Replace Local"
            else -> "Save to Local"
          },
        )
      }
      OutlinedButton(onClick = onExport, modifier = Modifier.weight(1f).heightIn(min = 52.dp)) {
        Icon(Icons.Default.Download, null)
        Spacer(Modifier.width(6.dp))
        Text(if (canSaveLocally) "Export TTML" else "Export static draft")
      }
    }
    Text(
      "${project.lines.size} lines · $timedParts/${allTargets.size} parts timed",
      style = MaterialTheme.typography.labelSmall,
      color = Color.White.copy(alpha = 0.62f),
      modifier = Modifier.fillMaxWidth(),
      textAlign = TextAlign.Center,
    )
  }

  if (confirmOverwrite) {
    AlertDialog(
      onDismissRequest = { confirmOverwrite = false },
      title = { Text("Replace saved local lyrics?") },
      text = { Text("This replaces the Local TTML currently saved for ${project.metadata.name}.") },
      confirmButton = {
        TextButton(onClick = { confirmOverwrite = false; onSaveLocal() }) { Text("Replace") }
      },
      dismissButton = {
        TextButton(onClick = { confirmOverwrite = false }) { Text("Cancel") }
      },
    )
  }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CreatorDraftPreview(
  project: CreatorProject,
  positionMs: Long,
  onSeek: (Long) -> Unit,
  modifier: Modifier = Modifier,
) {
  val playback = remember(project, positionMs) { creatorPlaybackActivity(project, positionMs) }
  LazyColumn(
    modifier = modifier,
    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 22.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    item {
      Text(
        project.metadata.name.ifBlank { "Lyric preview" },
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.Black,
      )
      project.metadata.artists.takeIf(List<String>::isNotEmpty)?.let { artists ->
        Text(
          artists.joinToString(),
          color = Color.White.copy(alpha = 0.72f),
          style = MaterialTheme.typography.bodyMedium,
        )
      }
    }
    itemsIndexed(project.lines, key = { _, line -> line.id }) { _, line ->
      val seekTime = line.tokens
        .flatMap(CreatorToken::fragments)
        .filter(::creatorFragmentHasValidTiming)
        .minOfOrNull { requireNotNull(it.startTimeMs) }
      val active = line.id in playback.lineIds
      Surface(
        onClick = { seekTime?.let(onSeek) },
        enabled = seekTime != null,
        shape = RoundedCornerShape(14.dp),
        color = if (active) Color(0xFF183F5A) else Color(0xFF152131),
        border = androidx.compose.foundation.BorderStroke(
          1.dp,
          if (active) MaterialTheme.colorScheme.primary.copy(alpha = 0.72f)
          else Color.White.copy(alpha = 0.11f),
        ),
      ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 11.dp)) {
          if (line.isBackground || line.isSecondSpeaker) {
            Text(
              buildString {
                if (line.isBackground) append("BACKGROUND")
                if (line.isBackground && line.isSecondSpeaker) append(" · ")
                if (line.isSecondSpeaker) append("SPEAKER 2")
              },
              style = MaterialTheme.typography.labelSmall,
              color = Color(0xFFB9DCFF),
              fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(5.dp))
          }
          FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
          ) {
            line.tokens.forEach { token ->
              CreatorPreviewToken(token = token, positionMs = positionMs)
            }
          }
        }
      }
    }
  }
}

@Composable
private fun CreatorPreviewToken(token: CreatorToken, positionMs: Long) {
  val annotated = buildAnnotatedString {
    token.fragments.forEach { fragment ->
      val progress = creatorFragmentPreviewProgress(fragment, positionMs)
      if (progress == null) {
        withStyle(SpanStyle(color = Color(0xFFFF9DA5))) { append(fragment.text) }
      } else {
        val playedLength = (fragment.text.length * progress).roundToInt().coerceIn(0, fragment.text.length)
        val active = isCreatorPlaybackIntervalActive(fragment.startTimeMs, fragment.endTimeMs, positionMs)
        val playedColor = if (active) Color.White else Color.White.copy(alpha = 0.78f)
        withStyle(SpanStyle(color = playedColor)) { append(fragment.text.take(playedLength)) }
        withStyle(SpanStyle(color = Color.White.copy(alpha = 0.38f))) {
          append(fragment.text.drop(playedLength))
        }
      }
    }
  }
  Text(
    text = annotated,
    style = MaterialTheme.typography.titleLarge,
    fontWeight = FontWeight.ExtraBold,
    lineHeight = 30.sp,
  )
}

@Composable
private fun CreatorField(label: String, value: String, onValue: (String) -> Unit) {
  OutlinedTextField(
    value = value,
    onValueChange = onValue,
    label = { Text(label) },
    singleLine = true,
    modifier = Modifier.fillMaxWidth(),
  )
}

@Composable
private fun ToggleLine(label: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
  Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
    Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
    Switch(checked = checked, onCheckedChange = onChecked)
  }
}

@Composable
private fun CreatorCard(
  modifier: Modifier = Modifier,
  content: @Composable ColumnScope.() -> Unit,
) {
  Surface(
    shape = RoundedCornerShape(20.dp),
    color = Color(0xFF152131),
    border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.16f)),
    modifier = modifier.fillMaxWidth(),
  ) {
    Column(
      modifier = Modifier.padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(5.dp),
      content = content,
    )
  }
}

@Composable
private fun rememberCreatorPosition(snapshot: NowPlayingSnapshot?): Long {
  var position by remember(snapshot?.identity?.exactStorageKey) {
    mutableLongStateOf(snapshot?.positionMs ?: 0L)
  }
  LaunchedEffect(snapshot) {
    while (isActive) {
      position = snapshot?.currentPositionMs(SystemClock.elapsedRealtime()) ?: 0L
      delay(if (snapshot?.isPlaying == true) 32L else 160L)
    }
  }
  return position
}

private fun formatCreatorTime(value: Long): String {
  val normalized = value.coerceAtLeast(0L)
  val minutes = normalized / 60_000L
  val seconds = (normalized % 60_000L) / 1_000L
  val millis = normalized % 1_000L
  return "%d:%02d.%03d".format(minutes, seconds, millis)
}
