package com.nothingmusic.di

import android.content.Context
import com.nothingmusic.data.local.AppDatabase
import com.nothingmusic.data.local.dao.FavoriteDao
import com.nothingmusic.data.local.dao.PlaylistDao
import com.nothingmusic.data.local.dao.ScannedFolderDao
import com.nothingmusic.data.local.dao.SearchHistoryDao
import com.nothingmusic.data.repository.MusicRepositoryImpl
import com.nothingmusic.data.scanner.AudioScanner
import com.nothingmusic.domain.repository.MusicRepository
import com.nothingmusic.util.SettingsManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase =
        AppDatabase.getInstance(context)

    @Provides
    fun providePlaylistDao(db: AppDatabase): PlaylistDao = db.playlistDao()

    @Provides
    fun provideScannedFolderDao(db: AppDatabase): ScannedFolderDao = db.scannedFolderDao()

    @Provides
    fun provideFavoriteDao(db: AppDatabase): FavoriteDao = db.favoriteDao()

    @Provides
    fun provideSearchHistoryDao(db: AppDatabase): SearchHistoryDao = db.searchHistoryDao()

    @Provides
    @Singleton
    fun provideSettingsManager(@ApplicationContext context: Context): SettingsManager =
        SettingsManager(context)

    @Provides
    @Singleton
    fun provideAudioScanner(@ApplicationContext context: Context): AudioScanner =
        AudioScanner(context)

    @Provides
    @Singleton
    fun provideMusicRepository(
        @ApplicationContext context: Context,
        scanner: AudioScanner,
        playlistDao: PlaylistDao,
        scannedFolderDao: ScannedFolderDao,
        favoriteDao: FavoriteDao,
    ): MusicRepository = MusicRepositoryImpl(context, scanner, playlistDao, scannedFolderDao, favoriteDao)
}