package com.mica.music.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * 仅承载远程曲库相关表，与宿主 App 自身的曲库数据库分开，避免迁移冲突。
 * 表结构、DAO 与 Mica 保持一致，便于后续同步上游改动。
 */
@Database(
    entities = [
        RemoteSourceEntity::class,
        RemoteTrackEntity::class,
        RemoteSelectedTrackEntity::class,
        RemoteSmbScopeEntity::class,
        RemoteSmbScopeTrackEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
abstract class RemoteDatabase : RoomDatabase() {

    abstract fun remoteSourceDao(): RemoteSourceDao

    abstract fun remoteTrackDao(): RemoteTrackDao

    abstract fun remoteSelectedTrackDao(): RemoteSelectedTrackDao

    abstract fun remoteSmbScopeDao(): RemoteSmbScopeDao

    companion object {
        private const val NAME = "mica-remote.db"

        @Volatile
        private var instance: RemoteDatabase? = null

        fun get(context: Context): RemoteDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    RemoteDatabase::class.java,
                    NAME,
                ).fallbackToDestructiveMigration().build().also { instance = it }
            }
    }
}
