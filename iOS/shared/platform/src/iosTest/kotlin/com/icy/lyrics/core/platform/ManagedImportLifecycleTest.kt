@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.icy.lyrics.core.platform

import com.icy.lyrics.core.lyrics.model.TrackIdentity
import com.icy.lyrics.core.platform.database.IcyLyricsDatabase
import com.icy.lyrics.core.platform.database.LocalTtmlDao
import com.icy.lyrics.core.platform.database.LocalTtmlEntity
import com.icy.lyrics.core.platform.database.openIosDatabaseAtPath
import com.icy.lyrics.core.platform.ios.IosLocalTtmlImports
import com.icy.lyrics.core.platform.ios.IosManagedTtmlFiles
import com.icy.lyrics.core.platform.storage.LocalTtmlRepository
import kotlinx.coroutines.runBlocking
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import okio.buffer
import okio.use
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.NSUUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Real native SQLite plus filesystem tests; original selected files are never cleanup targets. */
class ManagedImportLifecycleTest {
  @Test fun malformedImportRemovesOnlyTheNewManagedCopy() = fixture { test ->
    val copy = test.copy(BAD)
    assertFailsWith<Exception> { test.imports.import(TRACK, BAD, uri(copy)) }
    assertFalse(FS.exists(copy))
    assertEquals(0, test.db.localTtmlDao().count())
  }

  @Test fun explicitLibraryDeletionRemovesManagedCopyAndPreservesSelectedOriginal() = fixture { test ->
    val original = test.root / "selected-in-files.ttml"
    write(original, RAW)
    val copy = test.copy()
    test.imports.import(TRACK, RAW, uri(copy))
    assertTrue(test.imports.delete(TRACK.uri))
    assertFalse(FS.exists(copy))
    assertTrue(FS.exists(original))
    assertNull(test.db.localTtmlDao().get(TRACK.uri))
  }

  @Test fun failedReplacementPreservesPreviousDocumentAndCopy() = fixture { test ->
    val old = test.copy()
    test.imports.import(TRACK, RAW, uri(old))
    val failed = test.copy(BAD)
    assertFailsWith<Exception> { test.imports.import(TRACK, BAD, uri(failed)) }
    assertTrue(FS.exists(old))
    assertFalse(FS.exists(failed))
    val stored = assertNotNull(LocalTtmlRepository(test.db.localTtmlDao()).get(TRACK))
    assertEquals(RAW, stored.rawTtml)
    assertEquals(uri(old), stored.sourceUri)
  }

  @Test fun failedDatabaseSavePreservesPreviousDocumentAndCleansUnsavedCopy() = fixture { test ->
    val old = test.copy()
    test.imports.import(TRACK, RAW, uri(old))
    val rejectingDao = object : LocalTtmlDao by test.db.localTtmlDao() {
      override suspend fun upsert(entity: LocalTtmlEntity) { error("Simulated durable save failure") }
    }
    val replacement = test.copy(NEW)
    assertFailsWith<IllegalStateException> {
      IosLocalTtmlImports(rejectingDao, test.files).import(TRACK, NEW, uri(replacement))
    }
    assertTrue(FS.exists(old))
    assertFalse(FS.exists(replacement))
    assertEquals(RAW, assertNotNull(test.db.localTtmlDao().get(TRACK.uri)).rawTtml)
  }

  @Test fun successfulReplacementDeletesPreviousCopyOnlyAfterNewRowIsSaved() = fixture { test ->
    val old = test.copy()
    test.imports.import(TRACK, RAW, uri(old))
    val replacement = test.copy(NEW)
    test.imports.import(TRACK, NEW, uri(replacement))
    assertFalse(FS.exists(old))
    assertTrue(FS.exists(replacement))
    val stored = assertNotNull(test.db.localTtmlDao().get(TRACK.uri))
    assertEquals(NEW, stored.rawTtml)
    assertEquals(uri(replacement), stored.sourceUri)
  }

  @Test fun anyRemainingRawDatabaseReferenceProtectsTheCopy() = fixture { test ->
    val copy = test.copy()
    test.imports.import(TRACK, RAW, uri(copy))
    val other = TRACK.copy(uri = "spotify:local:Artist:Album:Other:123", title = "Other")
    test.imports.import(other, RAW, uri(copy))
    // A future or corrupt serialized document must still protect its source file.
    val row = assertNotNull(test.db.localTtmlDao().get(other.uri))
    test.db.localTtmlDao().upsert(row.copy(documentJson = "not decodable"))
    assertTrue(test.imports.delete(TRACK.uri))
    assertTrue(FS.exists(copy))
    assertTrue(test.imports.delete(other.uri))
    assertFalse(FS.exists(copy))
  }

  @Test fun cleanupRefusesExternalFilesNonUuidNamesAndSymlinkTargets() = fixture { test ->
    val outside = test.root / (NSUUID().UUIDString + ".ttml")
    write(outside, RAW)
    val named = test.folder / "my-original.ttml"
    write(named, RAW)
    val nested = test.folder / "nested" / (NSUUID().UUIDString + ".ttml")
    FS.createDirectories(nested.parent!!)
    write(nested, RAW)
    val link = test.folder / (NSUUID().UUIDString + ".ttml")
    FS.createSymlink(link, outside)
    for (file in listOf(outside, named, nested, link)) {
      assertFalse(test.files.deleteIfUnreferenced(uri(file), emptyList()))
      assertTrue(FS.exists(file))
    }
    test.imports.import(TRACK, RAW, uri(outside))
    test.imports.delete(TRACK.uri)
    assertTrue(FS.exists(outside))
  }

  @Test fun cleanupRefusesAnImportsDirectoryRedirectedOutsideApplicationSupport() = fixture { test ->
    val outside = test.root / "outside-imports"
    FS.createDirectories(outside)
    val original = outside / (NSUUID().UUIDString + ".ttml")
    write(original, RAW)
    FS.delete(test.folder)
    FS.createSymlink(test.folder, outside)
    assertFalse(test.files.deleteIfUnreferenced(uri(test.folder / original.name), emptyList()))
    assertTrue(FS.exists(original))
  }

  private class State(val root: Path, val db: IcyLyricsDatabase) {
    val folder = root / "IcyLyrics" / "Imports"
    val files = IosManagedTtmlFiles(root)
    val imports = IosLocalTtmlImports(db.localTtmlDao(), files)
    fun copy(raw: String = RAW): Path = (folder / (NSUUID().UUIDString + ".ttml")).also { write(it, raw) }
  }

  private fun fixture(action: suspend (State) -> Unit) = runBlocking {
    val temporary = NSTemporaryDirectory().toPath()
    val root = temporary / ("icy-managed-import-test-" + NSUUID().UUIDString)
    FS.createDirectories(root / "IcyLyrics" / "Imports")
    val db = openIosDatabaseAtPath((root / "test.db").toString())
    try { action(State(root, db)) }
    finally {
      db.close()
      check(root.parent == temporary && root.name.startsWith("icy-managed-import-test-"))
      FS.deleteRecursively(root)
    }
  }

  companion object {
    private val FS = FileSystem.SYSTEM
    private val TRACK = TrackIdentity(uri = "spotify:local:Artist:Album:Title:123", title = "Title", artists = listOf("Artist"))
    private const val RAW = "<tt><body><div><p begin=\"1s\" end=\"2s\">Saved line</p></div></body></tt>"
    private const val NEW = "<tt><body><div><p begin=\"1s\" end=\"2s\">Replacement line</p></div></body></tt>"
    private const val BAD = "<tt><body><p begin=\"oops\">Invalid timing</p></body></tt>"
    private fun uri(file: Path): String = NSURL.fileURLWithPath(file.toString()).absoluteString!!
    private fun write(file: Path, raw: String) { FS.sink(file).buffer().use { it.writeUtf8(raw) } }
  }
}
