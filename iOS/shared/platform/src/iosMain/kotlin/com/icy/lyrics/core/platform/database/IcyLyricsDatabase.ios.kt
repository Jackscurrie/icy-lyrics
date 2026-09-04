package com.icy.lyrics.core.platform.database

import androidx.room.ConstructedBy
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.RoomDatabaseConstructor
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSUserDomainMask

@Database(
  entities = [LocalTtmlEntity::class, LyricsCacheEntity::class, TrackAliasEntity::class, DeviceTimingEntity::class, DiagnosticEventEntity::class],
  version = 1,
  exportSchema = true,
)
@ConstructedBy(IcyLyricsDatabaseConstructor::class)
abstract class IcyLyricsDatabase : RoomDatabase() {
  abstract fun localTtmlDao(): LocalTtmlDao
  abstract fun lyricsCacheDao(): LyricsCacheDao
  abstract fun trackAliasDao(): TrackAliasDao
  abstract fun deviceTimingDao(): DeviceTimingDao
  abstract fun diagnosticEventDao(): DiagnosticEventDao
}

@Suppress("KotlinNoActualForExpect")
expect object IcyLyricsDatabaseConstructor : RoomDatabaseConstructor<IcyLyricsDatabase> {
  override fun initialize(): IcyLyricsDatabase
}

@OptIn(ExperimentalForeignApi::class)
internal fun openIosDatabase(): IcyLyricsDatabase {
  val directory = NSFileManager.defaultManager.URLForDirectory(
    NSApplicationSupportDirectory, NSUserDomainMask, null, true, null,
  )?.path ?: error("Application Support is unavailable.")
  return openIosDatabaseAtPath("$directory/icy_lyrics_v2.db")
}

internal fun openIosDatabaseAtPath(path: String): IcyLyricsDatabase =
  Room.databaseBuilder<IcyLyricsDatabase>(name = path)
    .setDriver(BundledSQLiteDriver())
    .setQueryCoroutineContext(Dispatchers.IO)
    .build()
