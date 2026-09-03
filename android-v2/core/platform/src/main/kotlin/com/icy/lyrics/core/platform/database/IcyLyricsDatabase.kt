package com.icy.lyrics.core.platform.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
  entities = [
    LocalTtmlEntity::class,
    LyricsCacheEntity::class,
    TrackAliasEntity::class,
    DeviceTimingEntity::class,
    DiagnosticEventEntity::class,
  ],
  version = 1,
  exportSchema = false,
)
abstract class IcyLyricsDatabase : RoomDatabase() {
  abstract fun localTtmlDao(): LocalTtmlDao
  abstract fun lyricsCacheDao(): LyricsCacheDao
  abstract fun trackAliasDao(): TrackAliasDao
  abstract fun deviceTimingDao(): DeviceTimingDao
  abstract fun diagnosticEventDao(): DiagnosticEventDao

  companion object {
    const val DEFAULT_NAME = "icy_lyrics_v2.db"

    @Volatile
    private var instance: IcyLyricsDatabase? = null

    fun open(context: Context, name: String = DEFAULT_NAME): IcyLyricsDatabase {
      return instance ?: synchronized(this) {
        instance ?: Room.databaseBuilder(
          context.applicationContext,
          IcyLyricsDatabase::class.java,
          name,
        ).build().also { instance = it }
      }
    }
  }
}
