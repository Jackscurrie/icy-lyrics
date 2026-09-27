package com.icy.lyrics.creator

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class CreatorProjectTimingTest {
  @Test
  fun rebindReplacesForeignTrackIdentityButKeepsAuthoredMetadata() {
    val project = CreatorProject(
      uri = "spotify:track:old",
      metadata = CreatorMetadata(
        name = "Old title",
        artists = listOf("Old artist"),
        songwriters = listOf("Writer"),
        albums = listOf("Old album"),
        spotifyTrackId = "old",
        appleMusicTrackId = "old-apple",
        isrc = "OLDISRC",
        language = "en",
        raw = mapOf("custom" to listOf("kept")),
      ),
    )

    val rebound = project.rebindToTrack(
      trackUri = "spotify:track:new",
      currentTrackMetadata = CreatorMetadata(
        name = "New title",
        artists = listOf("New artist"),
        albums = listOf("New album"),
        spotifyTrackId = "new",
      ),
    )

    assertEquals("spotify:track:new", rebound.uri)
    assertEquals("New title", rebound.metadata.name)
    assertEquals(listOf("New artist"), rebound.metadata.artists)
    assertEquals(listOf("New album"), rebound.metadata.albums)
    assertEquals("new", rebound.metadata.spotifyTrackId)
    assertEquals("", rebound.metadata.appleMusicTrackId)
    assertEquals("", rebound.metadata.isrc)
    assertEquals(listOf("Writer"), rebound.metadata.songwriters)
    assertEquals("en", rebound.metadata.language)
    assertEquals(mapOf("custom" to listOf("kept")), rebound.metadata.raw)
  }

  @Test
  fun plainTextUsesBackslashesAndPreservesBoundarySlots() {
    val lines = importCreatorPlainText("Hello  \\world\nSecond line")

    assertEquals(2, lines.size)
    assertEquals(listOf("Hello", "world"), lines[0].tokens.map { it.text() })
    assertEquals(listOf("  ", ""), lines[0].tokens.map(CreatorToken::boundaryAfter))
    assertEquals("Hello  world", lines[0].text())
    assertEquals(1, lines[1].tokens.size)
    assertEquals("Second line", lines[1].tokens.single().text())
    assertEquals(null, lines[0].tokens[0].fragments[0].startTimeMs)
  }

  @Test
  fun mobilePlainTextUsesWhitespaceForWordsAndBackslashesForFragments() {
    val lines = importCreatorMobilePlainText("  sing\\ing  together\t now  \nnext line")

    assertEquals(2, lines.size)
    assertEquals(listOf("  sing", "ing"), lines[0].tokens[0].fragments.map { it.text })
    assertEquals("  ", lines[0].tokens[0].boundaryAfter)
    assertEquals(listOf("together", "now"), lines[0].tokens.drop(1).map { it.text() })
    assertEquals(listOf("\t ", "  "), lines[0].tokens.drop(1).map { it.boundaryAfter })
    assertEquals("  singing  together\t now  ", lines[0].text())
  }

  @Test
  fun editorTextKeepsSyllableMarkersAndUnrelatedEditsKeepTheirTiming() {
    val syllableLine = CreatorLine(
      tokens = listOf(
        CreatorToken(
          id = "singing-token",
          fragments = listOf(
            CreatorFragment(id = "sing-fragment", text = "sing", startTimeMs = 100L, endTimeMs = 400L),
            CreatorFragment(id = "ing-fragment", text = "ing", startTimeMs = 400L, endTimeMs = 700L),
          ),
        ),
      ),
    ).synchronizeTiming()
    val otherLine = lineWithFragment(
      CreatorFragment(id = "other-fragment", text = "other", startTimeMs = 800L, endTimeMs = 1_200L),
    ).synchronizeTiming()
    val project = CreatorProject(lines = listOf(syllableLine, otherLine))

    assertEquals("sing\\ing\nother", project.editorText())
    val edited = project.reconcileMobilePlainText(project.editorText().replace("other", "changed"))
    val preserved = edited.lines.first().tokens.single().fragments

    assertEquals(listOf("sing-fragment", "ing-fragment"), preserved.map { it.id })
    assertEquals(listOf(100L, 400L), preserved.map { it.startTimeMs })
    assertEquals(listOf(400L, 700L), preserved.map { it.endTimeMs })
    assertEquals(null, edited.lines[1].tokens.single().fragments.single().startTimeMs)
  }

  @Test
  fun projectJsonRoundTripsEveryEditableField() {
    val fragment = CreatorFragment(
      id = "fragment-fixed",
      text = "hel",
      startTimeMs = 100L,
      endTimeMs = 300L,
    )
    val project = CreatorProject(
      uri = "spotify:track:0123456789ABCDEFGHIJKL",
      source = CreatorSourceProvenance(CreatorSource.ICY_DATABASE, maker = "Maker"),
      metadata = CreatorMetadata(
        name = "Song",
        artists = listOf("Artist"),
        raw = mapOf("custom" to listOf("one", "two")),
      ),
      lines = listOf(
        CreatorLine(
          id = "line-fixed",
          tokens = listOf(
            CreatorToken(
              id = "token-fixed",
              fragments = listOf(fragment),
              boundaryAfter = "  ",
            ),
          ),
          isBackground = true,
          isSecondSpeaker = true,
          attachedToLineId = "lead-fixed",
          startTimeMs = 100L,
          endTimeMs = 300L,
        ),
      ),
    )

    assertEquals(project, deserializeCreatorProjectJson(serializeCreatorProjectJson(project)))
  }

  @Test
  fun reconcileKeepsOnlyUnchangedWordTimingAcrossInsertedLines() {
    val first = CreatorLine(
      id = "first-line",
      tokens = listOf(
        CreatorToken(
          id = "hello-token",
          fragments = listOf(
            CreatorFragment(id = "hello-fragment", text = "hello", startTimeMs = 0L, endTimeMs = 500L),
          ),
          boundaryAfter = " ",
        ),
        CreatorToken(
          id = "world-token",
          fragments = listOf(
            CreatorFragment(id = "world-fragment", text = "world", startTimeMs = 500L, endTimeMs = 1_000L),
          ),
        ),
      ),
    ).synchronizeTiming()
    val original = CreatorProject(lines = listOf(first))

    val reconciled = original.reconcileMobilePlainText("new line\nhello earth")

    assertEquals("new line\nhello earth", reconciled.plainText())
    assertEquals(null, reconciled.lines[0].tokens[0].fragments[0].startTimeMs)
    assertEquals("hello-fragment", reconciled.lines[1].tokens[0].fragments[0].id)
    assertEquals(0L, reconciled.lines[1].tokens[0].fragments[0].startTimeMs)
    assertEquals(null, reconciled.lines[1].tokens[1].fragments[0].startTimeMs)
  }

  @Test
  fun movingAWordKeepsFragmentsAndSeparatorPositions() {
    val original = importCreatorPlainText("one \\two \\three").single()
    val movedId = original.tokens.first().id
    val moved = moveCreatorTokenWithinLine(original, 0, 2)

    assertEquals(listOf("two", "three", "one"), moved.tokens.map { it.text() })
    assertEquals(movedId, moved.tokens.last().id)
    assertEquals("two three one", moved.text())
    assertSame(moved, moveCreatorTokenWithinLine(moved, 2, 2))
  }

  @Test
  fun startSubtractsPositiveHeadphoneDelayAndClearsAnEarlierEnd() {
    val fragment = CreatorFragment(text = "word", endTimeMs = 900L)
    val project = projectWithFragments(fragment)

    val result = applyCreatorTimingAction(
      project = project,
      targetIndex = 0,
      action = CreatorTimingAction.START,
      playbackPositionMs = 1_250L,
      options = CreatorTimingOptions(headphoneDelayMs = 250L),
    )

    val timed = result.project.firstFragment()
    assertEquals(1_000L, timed.startTimeMs)
    assertEquals(null, timed.endTimeMs)
    assertEquals(1_000L, result.project.lines.single().startTimeMs)
  }

  @Test
  fun delayCorrectionClampsAtZeroAndNeverTreatsNegativeDelayAsAnAdvance() {
    assertEquals(0L, creatorCapturePosition(80L, 100L))
    assertEquals(80L, creatorCapturePosition(80L, -100L))
  }

  @Test
  fun endAndNextCommitsOneBoundaryAndAdvances() {
    val project = projectWithFragments(
      CreatorFragment(text = "first", startTimeMs = 0L),
      CreatorFragment(text = "next"),
    )

    val result = applyCreatorTimingAction(
      project,
      0,
      CreatorTimingAction.END_AND_NEXT,
      playbackPositionMs = 1_500L,
      options = CreatorTimingOptions(headphoneDelayMs = 250L),
    )
    val targets = creatorTimingTargets(result.project)

    assertEquals(1_250L, targets[0].fragment.endTimeMs)
    assertEquals(1_250L, targets[1].fragment.startTimeMs)
    assertEquals(1, result.targetIndex)
  }

  @Test
  fun endCommitsWithoutAdvancingAndCannotPrecedeStart() {
    val project = projectWithFragments(
      CreatorFragment(text = "word", startTimeMs = 1_000L),
      CreatorFragment(text = "next"),
    )
    val result = applyCreatorTimingAction(
      project,
      0,
      CreatorTimingAction.END,
      playbackPositionMs = 900L,
    )

    assertEquals(1_000L, result.project.firstFragment().endTimeMs)
    assertEquals(0, result.targetIndex)
  }

  @Test
  fun filteredTimingSkipsBackgroundWithoutChangingItsFragment() {
    val lead = lineWithFragment(CreatorFragment(text = "lead", startTimeMs = 0L))
    val background = lineWithFragment(CreatorFragment(text = "echo")).copy(
      isBackground = true,
      attachedToLineId = lead.id,
    )
    val nextLead = lineWithFragment(CreatorFragment(text = "next"))
    val project = CreatorProject(lines = listOf(lead, background, nextLead))

    val result = applyCreatorTimingAction(
      project,
      0,
      CreatorTimingAction.END_AND_NEXT,
      500L,
      CreatorTimingOptions(ignoreBackground = true),
    )

    assertEquals(500L, result.project.lines[0].tokens[0].fragments[0].endTimeMs)
    assertEquals(null, result.project.lines[1].tokens[0].fragments[0].startTimeMs)
    assertEquals(500L, result.project.lines[2].tokens[0].fragments[0].startTimeMs)
  }

  @Test
  fun timingStatusFindsPartialInvalidAndSameLineOverlapOnly() {
    val first = CreatorFragment(text = "one", startTimeMs = 100L, endTimeMs = 350L)
    val second = CreatorFragment(text = "two", startTimeMs = 300L, endTimeMs = 500L)
    val lead = CreatorLine(
      tokens = listOf(
        CreatorToken(fragments = listOf(first), boundaryAfter = " "),
        CreatorToken(fragments = listOf(second)),
      ),
    )
    val background = lineWithFragment(
      CreatorFragment(text = "echo", startTimeMs = 200L, endTimeMs = 400L),
    ).copy(isBackground = true, attachedToLineId = lead.id)
    val partial = lineWithFragment(CreatorFragment(text = "later", startTimeMs = 600L))
    val project = CreatorProject(lines = listOf(lead, background, partial))
    val targets = creatorTimingTargets(project)

    assertEquals(CreatorTimingTargetStatus.TIMED, classifyCreatorTimingTarget(targets, 0))
    assertEquals(CreatorTimingTargetStatus.OVERLAP, classifyCreatorTimingTarget(targets, 1))
    assertEquals(CreatorTimingTargetStatus.TIMED, classifyCreatorTimingTarget(targets, 2))
    assertEquals(CreatorTimingTargetStatus.PARTIAL, classifyCreatorTimingTarget(targets, 3))
    assertEquals(setOf(1, 3), creatorTimingErrorIndexes(project))
  }

  @Test
  fun previewKeepsIncompleteAndInvalidFragmentsStaticWhileValidTimingProgresses() {
    val missingBoth = CreatorFragment(text = "missing")
    val missingStart = CreatorFragment(text = "start", endTimeMs = 700L)
    val missingEnd = CreatorFragment(text = "end", startTimeMs = 300L)
    val backwards = CreatorFragment(text = "backwards", startTimeMs = 900L, endTimeMs = 400L)
    val valid = CreatorFragment(text = "valid", startTimeMs = 1_000L, endTimeMs = 2_000L)

    listOf(missingBoth, missingStart, missingEnd, backwards).forEach { fragment ->
      assertEquals(null, creatorFragmentPreviewProgress(fragment, 500L))
    }
    assertEquals(0f, creatorFragmentPreviewProgress(valid, 500L))
    assertEquals(0.5f, creatorFragmentPreviewProgress(valid, 1_500L))
    assertEquals(1f, creatorFragmentPreviewProgress(valid, 2_500L))

    val staticProject = projectWithFragments(missingBoth, missingStart, missingEnd, backwards)
    val hybridProject = projectWithFragments(valid, missingBoth)
    val completeProject = projectWithFragments(valid)
    assertEquals(CreatorPreviewMode.STATIC, creatorPreviewMode(staticProject))
    assertEquals(CreatorPreviewMode.HYBRID, creatorPreviewMode(hybridProject))
    assertEquals(CreatorPreviewMode.FULLY_TIMED, creatorPreviewMode(completeProject))
  }

  @Test
  fun everyMissingOrInvalidTimingStatusIsMarkedInvalidForDisplay() {
    val targets = creatorTimingTargets(
      projectWithFragments(
        CreatorFragment(text = "missing both"),
        CreatorFragment(text = "missing start", endTimeMs = 100L),
        CreatorFragment(text = "missing end", startTimeMs = 100L),
        CreatorFragment(text = "backwards", startTimeMs = 200L, endTimeMs = 100L),
      ),
    )

    assertEquals(CreatorTimingTargetStatus.UNTIMED, classifyCreatorTimingTarget(targets, 0))
    assertEquals(CreatorTimingTargetStatus.PARTIAL, classifyCreatorTimingTarget(targets, 1))
    assertEquals(CreatorTimingTargetStatus.PARTIAL, classifyCreatorTimingTarget(targets, 2))
    assertEquals(CreatorTimingTargetStatus.INVALID, classifyCreatorTimingTarget(targets, 3))
    assertTrue(targets.indices.all { classifyCreatorTimingTarget(targets, it).isInvalidForCreatorDisplay() })
  }

  @Test
  fun previewBackNavigationReturnsToTimingInsteadOfExitingCreator() {
    assertEquals(CreatorStage.TIME, CreatorStage.PREVIEW.backDestination())
    assertEquals(CreatorStage.EDIT, CreatorStage.TIME.backDestination())
    assertEquals(CreatorStage.CHOOSE, CreatorStage.EDIT.backDestination())
    assertEquals(null, CreatorStage.CHOOSE.backDestination())
  }

  @Test
  fun playbackIntervalsAreStartInclusiveAndEndExclusiveAcrossVocalLanes() {
    val lead = lineWithFragment(CreatorFragment(text = "lead", startTimeMs = 100L, endTimeMs = 500L))
    val background = lineWithFragment(
      CreatorFragment(text = "echo", startTimeMs = 200L, endTimeMs = 400L),
    ).copy(isBackground = true, attachedToLineId = lead.id)
    val project = CreatorProject(lines = listOf(lead, background))

    val activity = creatorPlaybackActivity(project, 200L)
    assertEquals(2, activity.fragmentIds.size)
    assertTrue(isCreatorPlaybackIntervalActive(100L, 200L, 100L))
    assertFalse(isCreatorPlaybackIntervalActive(100L, 200L, 200L))
    assertFalse(isCreatorPlaybackIntervalActive(100L, null, 150L))
  }

  private fun projectWithFragments(vararg fragments: CreatorFragment): CreatorProject =
    CreatorProject(
      lines = listOf(
        CreatorLine(
          tokens = fragments.mapIndexed { index, fragment ->
            CreatorToken(
              fragments = listOf(fragment),
              boundaryAfter = if (index < fragments.lastIndex) " " else "",
            )
          },
        ),
      ),
    )

  private fun lineWithFragment(fragment: CreatorFragment): CreatorLine =
    CreatorLine(tokens = listOf(CreatorToken(fragments = listOf(fragment))))

  private fun CreatorProject.firstFragment(): CreatorFragment =
    lines.single().tokens.first().fragments.single()
}
