package com.nothingmusic.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.nothingmusic.data.local.dao.FavoriteDao
import com.nothingmusic.data.local.dao.PlaylistDao
import com.nothingmusic.data.local.dao.ScannedFolderDao
import com.nothingmusic.data.local.dao.SearchHistoryDao
import com.nothingmusic.data.local.entity.FavoriteEntity
import com.nothingmusic.data.local.entity.PlaylistEntity
import com.nothingmusic.data.local.entity.PlaylistTrackEntity
import com.nothingmusic.data.local.entity.ScannedFolderEntity
import com.nothingmusic.data.local.entity.SearchHistoryEntity

@Database(
    entities = [
        PlaylistEntity::class,
        PlaylistTrackEntity::class,
        ScannedFolderEntity::class,
        FavoriteEntity::class,
        SearchHistoryEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun playlistDao(): PlaylistDao
    abstract fun scannedFolderDao(): ScannedFolderDao
    abstract fun favoriteDao(): FavoriteDao
    abstract fun searchHistoryDao(): SearchHistoryDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "nothing_music.db",
                ).build().also { INSTANCE = it }
            }
        }
    }
}