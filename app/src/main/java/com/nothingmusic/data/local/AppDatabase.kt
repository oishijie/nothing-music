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
    version = 5,
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

        /**
         * v3：tracks 增加元数据版本；旧行默认 0，低于扫描器当前版本，会在下次扫描时重读以补齐封面等字段。
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE tracks ADD COLUMN metadataVersion INTEGER NOT NULL DEFAULT 0",
                )
            }
        }

        /**
         * v4：tracks 增加专辑艺术家与碟号；旧行由扫描器版本机制在下次扫描时补齐。
         */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE tracks ADD COLUMN albumArtist TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE tracks ADD COLUMN discNumber INTEGER NOT NULL DEFAULT 0")
            }
        }

        /**
         * v5：tracks 增加远程标识；非空行是远程曲目的收藏/歌单快照，扫描器只处理空串行。
         */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE tracks ADD COLUMN remoteId TEXT NOT NULL DEFAULT ''")
            }
        }

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "nothing_music.db",
                ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5).build().also { INSTANCE = it }
            }
        }
    }
}