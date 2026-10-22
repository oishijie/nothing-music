package com.nothingmusic.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.nothingmusic.ui.PlayerViewModel
import com.nothingmusic.ui.animation.NothingOSEnter
import com.nothingmusic.ui.animation.NothingOSExit
import com.nothingmusic.ui.animation.NothingOSPopEnter
import com.nothingmusic.ui.animation.NothingOSPopExit
import com.nothingmusic.ui.screen.LibraryScreen
import com.nothingmusic.ui.screen.NowPlayingScreen
import com.nothingmusic.ui.screen.PlaylistDetailScreen
import com.nothingmusic.ui.screen.SettingsScreen
import com.nothingmusic.ui.screen.queue.QueueScreen
import com.nothingmusic.ui.screen.search.GlobalSearchScreen
import com.nothingmusic.ui.screen.remote.RemoteLibraryScreen
import com.nothingmusic.ui.screen.remote.RemoteSourcesScreen

object Routes {
    const val LIBRARY = "library"
    const val SEARCH = "search"
    const val SETTINGS = "settings"
    const val REMOTE = "remote"
    const val REMOTE_SOURCES = "remotesources"
    const val NOW_PLAYING = "nowplaying"
    const val QUEUE = "queue?startIndex={startIndex}"
    const val EQ = "equalizer"
    const val FOLDER_PICKER = "folderpicker"
    const val ALBUM_DETAIL = "album/{albumName}/{albumArtist}"
    const val PLAYLIST = "playlist/{playlistId}"

    fun albumDetail(albumName: String, albumArtist: String) =
        "album/${android.net.Uri.encode(albumName)}/${android.net.Uri.encode(albumArtist)}"

    fun playlist(playlistId: Long) = "playlist/$playlistId"
}

@Composable
fun NothingMusicNavGraph(
    navController: NavHostController,
    playerViewModel: PlayerViewModel,
    activity: android.app.Activity? = null,
) {
    NavHost(
        navController = navController,
        startDestination = Routes.LIBRARY,
        enterTransition = NothingOSEnter,
        exitTransition = NothingOSExit,
        popEnterTransition = NothingOSPopEnter,
        popExitTransition = NothingOSPopExit,
    ) {
        composable(Routes.LIBRARY) {
            LibraryScreen(
                playerViewModel = playerViewModel,
                onNavigateToNowPlaying = { navController.navigate(Routes.NOW_PLAYING) },
                onNavigateToPlaylist = { id -> navController.navigate(Routes.playlist(id)) },
                onNavigateToQueue = { navController.navigate(Routes.QUEUE) },
                onNavigateToFolderPicker = { navController.navigate(Routes.FOLDER_PICKER) },
            )
        }
        composable(Routes.SEARCH) {
            GlobalSearchScreen(
                playerViewModel = playerViewModel,
                onNavigateToNowPlaying = { navController.navigate(Routes.NOW_PLAYING) },
            )
        }
        composable(Routes.REMOTE) {
            RemoteLibraryScreen(
                onPlayTrack = { track, tracks ->
                    playerViewModel.playTrack(track, tracks)
                    navController.navigate(Routes.NOW_PLAYING)
                },
                onOpenSources = { navController.navigate(Routes.REMOTE_SOURCES) },
            )
        }
        composable(Routes.REMOTE_SOURCES) {
            RemoteSourcesScreen()
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(
                onNavigateToEqualizer = { navController.navigate(Routes.EQ) },
                onNavigateToFolderPicker = { navController.navigate(Routes.FOLDER_PICKER) },
                onLanguageChanged = {
                    // 系统语言变更需要重建 Activity 才会对已有界面生效。
                    activity?.recreate()
                },
            )
        }
        composable(Routes.NOW_PLAYING) {
            NowPlayingScreen(
                playerViewModel = playerViewModel,
                onBack = { navController.popBackStack() },
                onNavigateToQueue = { navController.navigate(Routes.QUEUE) },
            )
        }
        composable(
            route = Routes.QUEUE,
            arguments = listOf(
                navArgument("startIndex") {
                    type = NavType.IntType
                    defaultValue = -1
                },
            ),
        ) {
            QueueScreen(
                playerViewModel = playerViewModel,
                onBack = { navController.popBackStack() },
            )
        }
        composable(Routes.EQ) {
            com.nothingmusic.ui.screen.equalizer.EqualizerScreen(
                onBack = { navController.popBackStack() },
            )
        }
        composable(Routes.FOLDER_PICKER) {
            com.nothingmusic.ui.screen.folderpicker.FolderPickerScreen(
                onDone = { navController.popBackStack() },
            )
        }
        composable(
            route = Routes.ALBUM_DETAIL,
            arguments = listOf(
                navArgument("albumName") { type = NavType.StringType },
                navArgument("albumArtist") { type = NavType.StringType },
            ),
        ) {
            com.nothingmusic.ui.screen.AlbumDetailScreen(
                albumName = android.net.Uri.decode(it.arguments?.getString("albumName") ?: ""),
                albumArtist = android.net.Uri.decode(it.arguments?.getString("albumArtist") ?: ""),
                playerViewModel = playerViewModel,
                onBack = { navController.popBackStack() },
            )
        }
        composable(
            route = Routes.PLAYLIST,
            arguments = listOf(
                navArgument("playlistId") { type = NavType.LongType },
            ),
        ) {
            val playlistId = it.arguments?.getLong("playlistId") ?: 0L
            PlaylistDetailScreen(
                playlistId = playlistId,
                playerViewModel = playerViewModel,
                onBack = { navController.popBackStack() },
                onNavigateToNowPlaying = { navController.navigate(Routes.NOW_PLAYING) },
            )
        }
    }
}