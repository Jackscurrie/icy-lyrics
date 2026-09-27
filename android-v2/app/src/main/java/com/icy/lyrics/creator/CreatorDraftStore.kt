package com.icy.lyrics.creator

import android.content.Context
import android.util.AtomicFile
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * One recoverable Lyric Creator draft bound to a track's exact storage key.
 *
 * [serializedProject] deliberately remains an opaque string. The Creator model can evolve without
 * coupling file durability to a particular UI model or duplicating its serializer here.
 */
data class CreatorDraftRecord(
  val exactTrackKey: String,
  val serializedProject: String,
  val createdAtEpochMs: Long,
  val updatedAtEpochMs: Long,
)

/** A malformed or misaddressed draft is surfaced instead of silently replacing user work. */
class CreatorDraftReadException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * Crash-safe single-draft-per-track persistence for the Lyric Creator.
 *
 * Callers may autosave freely: Android's [AtomicFile] retains the previous complete draft if the
 * process dies during a write. Track keys are never normalized or placed in filenames; their full
 * value is stored in the envelope and a SHA-256 digest selects the file.
 */
class CreatorDraftStore internal constructor(
  private val directory: File,
  private val clock: () -> Long,
  private val fileFactory: CreatorAtomicFileFactory,
  private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
  constructor(context: Context) : this(
    directory = File(context.applicationContext.filesDir, DIRECTORY_NAME),
    clock = System::currentTimeMillis,
    fileFactory = CreatorAtomicFileFactory(::AndroidCreatorAtomicFile),
    ioDispatcher = Dispatchers.IO,
  )

  private val mutex = Mutex()
  private val json = Json {
    encodeDefaults = true
    ignoreUnknownKeys = true
  }

  suspend fun load(exactTrackKey: String): CreatorDraftRecord? = withContext(ioDispatcher) {
    mutex.withLock { loadLocked(requireExactTrackKey(exactTrackKey)) }
  }

  suspend fun save(exactTrackKey: String, serializedProject: String): CreatorDraftRecord =
    withContext(ioDispatcher) {
      val key = requireExactTrackKey(exactTrackKey)
      val projectBytes = serializedProject.toByteArray(Charsets.UTF_8)
      require(projectBytes.isNotEmpty()) { "A Lyric Creator draft cannot be empty." }
      require(projectBytes.size <= MAX_SERIALIZED_PROJECT_BYTES) {
        "The Lyric Creator draft is larger than the 2 MB limit."
      }

      mutex.withLock {
        val now = clock().coerceAtLeast(0L)
        val existing = loadLocked(key)
        val createdAt = existing?.createdAtEpochMs ?: now
        val record = CreatorDraftRecord(
          exactTrackKey = key,
          serializedProject = serializedProject,
          createdAtEpochMs = createdAt,
          updatedAtEpochMs = maxOf(now, createdAt),
        )
        writeLocked(record)
        record
      }
    }

  suspend fun delete(exactTrackKey: String): Boolean = withContext(ioDispatcher) {
    mutex.withLock {
      val file = fileFactory.create(fileFor(requireExactTrackKey(exactTrackKey)))
      val existed = file.exists()
      file.delete()
      existed
    }
  }

  internal fun fileFor(exactTrackKey: String): File = File(
    directory,
    "${draftFileStem(requireExactTrackKey(exactTrackKey))}.json",
  )

  private fun loadLocked(exactTrackKey: String): CreatorDraftRecord? {
    val atomicFile = fileFactory.create(fileFor(exactTrackKey))
    if (!atomicFile.exists()) return null

    val bytes = try {
      atomicFile.openRead().use { it.readBytesLimited(MAX_DRAFT_FILE_BYTES) }
    } catch (error: CreatorDraftReadException) {
      throw error
    } catch (error: IOException) {
      throw CreatorDraftReadException("The saved Lyric Creator draft could not be read.", error)
    }
    val envelope = try {
      json.decodeFromString<CreatorDraftEnvelope>(bytes.toString(Charsets.UTF_8))
    } catch (error: SerializationException) {
      throw CreatorDraftReadException("The saved Lyric Creator draft is damaged.", error)
    } catch (error: IllegalArgumentException) {
      throw CreatorDraftReadException("The saved Lyric Creator draft is damaged.", error)
    }

    if (envelope.schemaVersion != CURRENT_SCHEMA_VERSION) {
      throw CreatorDraftReadException(
        "This Lyric Creator draft uses unsupported format ${envelope.schemaVersion}.",
      )
    }
    if (envelope.exactTrackKey != exactTrackKey) {
      throw CreatorDraftReadException("The saved Lyric Creator draft belongs to another track.")
    }
    if (envelope.serializedProject.toByteArray(Charsets.UTF_8).size > MAX_SERIALIZED_PROJECT_BYTES) {
      throw CreatorDraftReadException("The saved Lyric Creator draft exceeds the 2 MB limit.")
    }
    if (envelope.serializedProject.isEmpty()) {
      throw CreatorDraftReadException("The saved Lyric Creator draft is empty.")
    }
    return envelope.toRecord()
  }

  private fun writeLocked(record: CreatorDraftRecord) {
    directory.mkdirs()
    if (!directory.isDirectory) {
      throw IOException("The Lyric Creator draft folder could not be created.")
    }

    val bytes = json.encodeToString(CreatorDraftEnvelope.from(record)).toByteArray(Charsets.UTF_8)
    if (bytes.size > MAX_DRAFT_FILE_BYTES) {
      throw IOException("The Lyric Creator draft is too large to save safely.")
    }

    val atomicFile = fileFactory.create(fileFor(record.exactTrackKey))
    val output = atomicFile.startWrite()
    try {
      output.write(bytes)
      output.flush()
      atomicFile.finishWrite(output)
    } catch (error: Throwable) {
      try {
        atomicFile.failWrite(output)
      } catch (rollbackError: Throwable) {
        error.addSuppressed(rollbackError)
      }
      throw error
    }
  }

  private fun InputStream.readBytesLimited(maxBytes: Int): ByteArray {
    val result = ByteArrayOutputStream(minOf(maxBytes, DEFAULT_BUFFER_SIZE))
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    while (true) {
      val count = read(buffer)
      if (count < 0) return result.toByteArray()
      if (result.size() + count > maxBytes) {
        throw CreatorDraftReadException("The saved Lyric Creator draft exceeds the safe size limit.")
      }
      result.write(buffer, 0, count)
    }
  }

  private fun requireExactTrackKey(value: String): String {
    require(value.isNotBlank()) { "An exact track key is required to save a Lyric Creator draft." }
    return value
  }

  companion object {
    // Keep this legacy path so existing Personal installs retain their drafts.
    internal const val DIRECTORY_NAME = "personal-lyric-creator/drafts"
    internal const val CURRENT_SCHEMA_VERSION = 1
    internal const val MAX_SERIALIZED_PROJECT_BYTES = 2_000_000
    internal const val MAX_DRAFT_FILE_BYTES = MAX_SERIALIZED_PROJECT_BYTES + 64 * 1_024

    internal fun draftFileStem(exactTrackKey: String): String {
      require(exactTrackKey.isNotBlank()) { "An exact track key is required." }
      val digest = MessageDigest.getInstance("SHA-256")
        .digest(exactTrackKey.toByteArray(Charsets.UTF_8))
      return digest.joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }
  }
}

@Serializable
private data class CreatorDraftEnvelope(
  val schemaVersion: Int,
  val exactTrackKey: String,
  val serializedProject: String,
  val createdAtEpochMs: Long,
  val updatedAtEpochMs: Long,
) {
  fun toRecord() = CreatorDraftRecord(
    exactTrackKey = exactTrackKey,
    serializedProject = serializedProject,
    createdAtEpochMs = createdAtEpochMs.coerceAtLeast(0L),
    updatedAtEpochMs = updatedAtEpochMs.coerceAtLeast(createdAtEpochMs.coerceAtLeast(0L)),
  )

  companion object {
    fun from(record: CreatorDraftRecord) = CreatorDraftEnvelope(
      schemaVersion = CreatorDraftStore.CURRENT_SCHEMA_VERSION,
      exactTrackKey = record.exactTrackKey,
      serializedProject = record.serializedProject,
      createdAtEpochMs = record.createdAtEpochMs,
      updatedAtEpochMs = record.updatedAtEpochMs,
    )
  }
}

internal fun interface CreatorAtomicFileFactory {
  fun create(baseFile: File): CreatorAtomicFile
}

internal interface CreatorAtomicFile {
  fun exists(): Boolean
  fun openRead(): InputStream
  fun startWrite(): OutputStream
  fun finishWrite(output: OutputStream)
  fun failWrite(output: OutputStream)
  fun delete()
}

private class AndroidCreatorAtomicFile(baseFile: File) : CreatorAtomicFile {
  private val delegate = AtomicFile(baseFile)

  override fun exists(): Boolean =
    delegate.baseFile.exists() ||
      File("${delegate.baseFile.path}.bak").exists() ||
      File("${delegate.baseFile.path}.new").exists()
  override fun openRead(): InputStream = delegate.openRead()
  override fun startWrite(): OutputStream = delegate.startWrite()
  override fun finishWrite(output: OutputStream) = delegate.finishWrite(output.asFileOutputStream())
  override fun failWrite(output: OutputStream) = delegate.failWrite(output.asFileOutputStream())
  override fun delete() = delegate.delete()

  private fun OutputStream.asFileOutputStream(): FileOutputStream = this as? FileOutputStream
    ?: error("AtomicFile returned an unexpected output stream.")
}
