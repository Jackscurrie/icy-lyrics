package com.icy.lyrics.creator

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CreatorDraftStoreTest {
  @Test
  fun draftRoundTripsAndResavePreservesCreationTime() = runTest {
    var now = 1_000L
    val files = FakeAtomicFileFactory()
    val store = store(files) { now }

    val first = store.save(TRACK, "{\"lines\":[\"first\"]}")
    now = 2_000L
    val second = store.save(TRACK, "{\"lines\":[\"second\"]}")

    assertEquals(1_000L, first.createdAtEpochMs)
    assertEquals(1_000L, second.createdAtEpochMs)
    assertEquals(2_000L, second.updatedAtEpochMs)
    assertEquals(second, store.load(TRACK))
  }

  @Test
  fun completeExactTrackKeyControlsTheDraftFile() = runTest {
    val files = FakeAtomicFileFactory()
    val store = store(files)
    val localOne = "spotify:local:Artist:Album:Title:180"
    val localTwo = "spotify:local:Artist:Album:Title:181"

    store.save(localOne, "one")
    store.save(localTwo, "two")

    assertEquals("one", store.load(localOne)?.serializedProject)
    assertEquals("two", store.load(localTwo)?.serializedProject)
    assertNotEquals(store.fileFor(localOne), store.fileFor(localTwo))
    assertEquals(64, CreatorDraftStore.draftFileStem(localOne).length)
  }

  @Test
  fun failedAtomicWriteRollsBackToPreviousCompleteDraft() = runTest {
    val files = FakeAtomicFileFactory()
    val store = store(files)
    store.save(TRACK, "safe")
    files.file(store.fileFor(TRACK)).failNextWrite = true

    assertFailsWith<IOException> { store.save(TRACK, "incomplete") }

    assertEquals("safe", store.load(TRACK)?.serializedProject)
    assertTrue(files.file(store.fileFor(TRACK)).failWriteCalled)
  }

  @Test
  fun deleteRemovesOnlyTheRequestedTrackDraft() = runTest {
    val files = FakeAtomicFileFactory()
    val store = store(files)
    store.save(TRACK, "first")
    store.save(OTHER_TRACK, "second")

    assertTrue(store.delete(TRACK))
    assertFalse(store.delete(TRACK))
    assertNull(store.load(TRACK))
    assertEquals("second", store.load(OTHER_TRACK)?.serializedProject)
  }

  @Test
  fun oversizedAndBlankDraftsAreRejectedBeforeWriting() = runTest {
    val files = FakeAtomicFileFactory()
    val store = store(files)

    assertFailsWith<IllegalArgumentException> { store.save(TRACK, "") }
    assertFailsWith<IllegalArgumentException> {
      store.save(TRACK, "x".repeat(CreatorDraftStore.MAX_SERIALIZED_PROJECT_BYTES + 1))
    }
    assertTrue(files.files.isEmpty())
  }

  private fun store(
    files: FakeAtomicFileFactory,
    clock: () -> Long = { 1_000L },
  ) = CreatorDraftStore(
    directory = File("test-creator-drafts"),
    clock = clock,
    fileFactory = files,
    ioDispatcher = Dispatchers.Unconfined,
  )

  private companion object {
    const val TRACK = "spotify:track:0123456789012345678901"
    const val OTHER_TRACK = "spotify:track:abcdefghijklmnopqrstuv"
  }
}

private class FakeAtomicFileFactory : CreatorAtomicFileFactory {
  val files = mutableMapOf<String, FakeAtomicFile>()

  override fun create(baseFile: File): CreatorAtomicFile = file(baseFile)

  fun file(baseFile: File): FakeAtomicFile =
    files.getOrPut(baseFile.path) { FakeAtomicFile() }
}

private class FakeAtomicFile : CreatorAtomicFile {
  var committed: ByteArray? = null
  var pending: ByteArrayOutputStream? = null
  var failNextWrite = false
  var failWriteCalled = false

  override fun exists(): Boolean = committed != null

  override fun openRead(): InputStream = ByteArrayInputStream(
    committed ?: throw IOException("Missing fake atomic file"),
  )

  override fun startWrite(): OutputStream {
    failWriteCalled = false
    val sink = ByteArrayOutputStream()
    pending = sink
    return if (failNextWrite) {
      failNextWrite = false
      object : OutputStream() {
        override fun write(value: Int) = throw IOException("Simulated interrupted write")
        override fun write(bytes: ByteArray, offset: Int, length: Int) =
          throw IOException("Simulated interrupted write")
      }
    } else {
      sink
    }
  }

  override fun finishWrite(output: OutputStream) {
    committed = checkNotNull(pending).toByteArray()
    pending = null
  }

  override fun failWrite(output: OutputStream) {
    failWriteCalled = true
    pending = null
  }

  override fun delete() {
    committed = null
    pending = null
  }
}
