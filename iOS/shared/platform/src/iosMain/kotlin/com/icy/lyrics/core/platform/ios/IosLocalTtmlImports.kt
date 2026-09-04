@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.icy.lyrics.core.platform.ios

import com.icy.lyrics.core.lyrics.model.TrackIdentity
import com.icy.lyrics.core.platform.database.LocalTtmlDao
import com.icy.lyrics.core.platform.provider.LocalTtmlProvider
import com.icy.lyrics.core.platform.storage.LocalTtmlRepository
import com.icy.lyrics.core.platform.storage.TrackKeys
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSURL
import platform.Foundation.NSUserDomainMask

// One process-wide lock also covers briefly overlapping native service instances.
private val importMutationMutex = Mutex()

/** The DB remains authoritative; cleanup never removes a file still named by any saved row. */
internal class IosLocalTtmlImports(
  private val dao: LocalTtmlDao,
  private val files: IosManagedTtmlFiles = IosManagedTtmlFiles(),
  private val provider: LocalTtmlProvider = LocalTtmlProvider(LocalTtmlRepository(dao)),
) {
  suspend fun import(track: TrackIdentity, rawTtml: String, sourceUri: String?) {
    var previous: String? = null
    mutateAndClean({ listOf(sourceUri, previous) }) {
      previous = dao.get(TrackKeys.exact(track))?.sourceUri
      provider.import(track, rawTtml, sourceUri = sourceUri)
    }
  }

  suspend fun delete(trackKey: String): Boolean {
    var previous: String? = null
    return mutateAndClean({ listOf(previous) }) {
      previous = dao.get(trackKey)?.sourceUri
      dao.delete(trackKey) > 0
    }
  }

  private suspend fun <T> mutateAndClean(candidates: () -> List<String?>, mutation: suspend () -> T): T {
    var failure: Throwable? = null
    try {
      return importMutationMutex.withLock { mutation() }
    } catch (error: Throwable) {
      failure = error
      throw error
    } finally {
      try {
        // Cancellation after SQLite commits must still preserve the new file,
        // and cancellation before a save must not strand the handed-off copy.
        withContext(NonCancellable) {
          importMutationMutex.withLock {
            // Read raw rows: even a currently undecodable document protects its file.
            val references = dao.all().mapNotNull { it.sourceUri }
            candidates().filterNotNull().distinct().forEach { files.deleteIfUnreferenced(it, references) }
          }
        }
      } catch (cleanup: Throwable) {
        if (failure != null) failure.addSuppressed(cleanup) else throw cleanup
      }
    }
  }
}

/** Deletes only regular UUID-named copies in the one app-owned import directory. */
internal class IosManagedTtmlFiles(
  private val applicationSupport: Path = applicationSupportDirectory(),
  private val fs: FileSystem = FileSystem.SYSTEM,
) {
  private val imports = applicationSupport / "IcyLyrics" / "Imports"
  private val filename = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\\.ttml")

  fun deleteIfUnreferenced(sourceUri: String, references: List<String>): Boolean {
    val candidate = ownedFile(sourceUri) ?: return false
    if (references.any { ownedFile(it) == candidate }) return false
    fs.delete(candidate, mustExist = false)
    return true
  }

  private fun ownedFile(sourceUri: String): Path? {
    val url = NSURL.URLWithString(sourceUri) ?: return null
    if (url.scheme != "file" || url.host.orEmpty() !in setOf("", "localhost") ||
      url.user != null || url.password != null || url.port != null || url.query != null || url.fragment != null) return null
    val path = url.path?.toPath() ?: return null
    if (!path.isAbsolute || !filename.matches(path.name)) return null
    return runCatching {
      val support = fs.canonicalize(applicationSupport)
      val root = fs.canonicalize(imports)
      // Refuse an Imports directory redirected anywhere by a symlink. System
      // aliases such as /var -> /private/var remain valid through canonicalization.
      if (root != support / "IcyLyrics" / "Imports") return null
      val metadata = fs.metadataOrNull(path) ?: return null
      if (!metadata.isRegularFile || metadata.symlinkTarget != null) return null
      val canonical = fs.canonicalize(path)
      canonical.takeIf { it.parent == root && filename.matches(it.name) }
    }.getOrNull()
  }
}

private fun applicationSupportDirectory(): Path =
  requireNotNull(NSFileManager.defaultManager.URLForDirectory(
    NSApplicationSupportDirectory, NSUserDomainMask, null, true, null,
  )?.path) { "Application Support is unavailable." }.toPath()
