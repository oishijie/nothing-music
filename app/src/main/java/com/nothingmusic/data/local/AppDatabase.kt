package com.nothingmusic.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.nothingmusic.data.local.dao.FavoriteDao
import com.nothingmusic.data.local.dao.PlaylistDao
import com.nothingmusic.data.local.dao.ScannedFolderDao
import com.nothingmusic.data.local.dao.SearchHistoryDao
import com.nothingmusic.data.local.dao.TrackDao
import com.nothingmusic.data.local.entity.FavoriteEntity
import com.nothingmusic.data.local.entity.PlaylistEntity
import com.nothingmusic.data.local.entity.PlaylistTrackEntity
import com.nothingmusic.data.local.entity.ScannedFolderEntity
import com.nothingmusic.data.local.entity.SearchHistoryEntity
import com.nothingmusic.data.local.entity.TrackEntity

@Database(
    entities = [
        PlaylistEntity::class,
        PlaylistTrackEntity::class,
        ScannedFolderEntity::class,
        FavoriteEntity::class,
        SearchHistoryEntity::class,
        TrackEntity::class,
    ],
    version = 2,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun playlistDao(): PlaylistDao
    abstract fun scannedFolderDao(): ScannedFolderDao
    abstract fun favoriteDao(): FavoriteDao
    abstract fun searchHistoryDao(): SearchHistoryDao
    abstract fun trackDao(): TrackDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        /**
         * v2：新增 tracks 缓存表；scanned_folders 记录树 URI 的展示名。
         * 旧版裸文件路径在 scoped storage 下已不可读，迁移时一并清掉，由用户重新选择文件夹。
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE scanned_folders ADD COLUMN displayName TEXT NOT NULL DEFAULT ''",
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `tracks` (
                        `id` INTEGER NOT NULL,
                        `uri` TEXT NOT NULL,
                        `documentName` TEXT NOT NULL,
                        `title` TEXT NOT NULL,
                        `artist` TEXT NOT NULL,
                        `album` TEXT NOT NULL,
                        `durationMs` INTEGER NOT NULL,
                        `folderUri` TEXT NOT NULL,
                        `dateAdded` INTEGER NOT NULL,
                        `trackNumber` INTEGER NOT NULL,
                        `year` INTEGER NOT NULL,
                        `mimeType` TEXT,
                        `sizeBytes` INTEGER NOT NULL,
                        `lastModified` INTEGER NOT NULL,
                        `sampleRate` INTEGER NOT NULL,
                        `bitrate` INTEGER NOT NULL,
                        `artworkUri` TEXT,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent(),
                )
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_tracks_uri` ON `tracks` (`uri`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_tracks_folderUri` ON `tracks` (`folderUri`)")
                db.execSQL("DELETE FROM scanned_folders WHERE path NOT LIKE 'content://%'")
                // 旧 id 来自 MediaStore/路径哈希，与新的文档 URI 哈希 id 不可能对应，清掉避免歌单计数虚高。
                db.execSQL("DELETE FROM playlist_tracks")
                db.execSQL("DELETE FROM favorites")
            }
        }

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "nothing_music.db",
                ).addMigrations(MIGRATION_1_2).build().also { INSTANCE = it }
            }
        }
    }
}