package com.nothingmusic

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.SideEffect
import androidx.core.view.WindowCompat
import com.nothingmusic.ui.PlayerViewModel
import com.nothingmusic.ui.component.MiniPlayer
import com.nothingmusic.ui.component.PermissionRationaleScreen
import com.nothingmusic.ui.navigation.NothingBottomNavBar
import com.nothingmusic.ui.navigation.NothingMusicNavGraph
import com.nothingmusic.ui.navigation.Routes
import com.nothingmusic.ui.theme.NothingMusicTheme
import com.nothingmusic.util.AppThemeMode
import com.nothingmusic.util.AppThemeModeState
import com.nothingmusic.util.rememberPermissionState
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            NothingMusicTheme {
                MainScreen()
            }
        }
    }
}

@Composable
private fun MainScreen() {
    val activity = androidx.compose.ui.platform.LocalContext.current as? android.app.Activity
    val systemDark = isSystemInDarkTheme()
    val playerViewModel: PlayerViewModel = viewModel()
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route.orEmpty()

    val currentTrack by playerViewModel.currentTrack.collectAsState()
    val isPlaying by playerViewModel.isPlaying.collectAsState()
    val position by playerViewModel.currentPosition.collectAsState()
    val duration by playerViewModel.duration.collectAsState()

    val permission = rememberPermissionState()

    // 状态栏图标随实际主题反转：白天深色图标、黑夜浅色图标。
    var themeMode by AppThemeModeState.mode
    val darkTheme = when (themeMode) {
        AppThemeMode.DARK -> true
        AppThemeMode.LIGHT -> false
        else -> systemDark
    }
    SideEffect {
        activity?.window?.let { window ->
            WindowCompat.getInsetsController(window, window.decorView).apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }

    LaunchedEffect(permission.isGranted) {
        if (!permission.isGranted) {
            permission.launchRequest()
        }
    }

    val isBottomLevel = currentRoute in setOf(
        Routes.LIBRARY, Routes.REMOTE, Routes.SETTINGS
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Column(
            modifier = Modifier.weight(1f)
        ) {
            if (permission.isGranted) {
                NothingMusicNavGraph(
                    navController = navController,
                    playerViewModel = playerViewModel,
                    activity = activity,
                )
            } else {
                PermissionRationaleScreen(
                    onRequestPermission = { permission.launchRequest() },
                )
            }
        }

        if (isBottomLevel && permission.isGranted) {
            Column(modifier = Modifier.navigationBarsPadding()) {
                MiniPlayer(
                    currentTrack = currentTrack,
                    isPlaying = isPlaying,
                    positionMs = position,
                    durationMs = duration,
                    onPlayPause = playerViewModel::playPause,
                    onNext = playerViewModel::next,
                    onClick = { navController.navigate(Routes.NOW_PLAYING) },
                    onSeek = {},
                )
                NothingBottomNavBar(
                    currentRoute = currentRoute,
                    onNavigate = { route ->
                        navController.navigate(route) {
                            popUpTo(Routes.LIBRARY) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                )
            }
        }
    }
}