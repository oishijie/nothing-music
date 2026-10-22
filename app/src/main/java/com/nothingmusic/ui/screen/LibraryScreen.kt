package com.nothingmusic.ui.screen

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import com.nothingmusic.ui.animation.NothingSpring
import com.nothingmusic.ui.animation.NothingSpringOffset
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.PlaylistPlay
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.runtime.Composable
import com.nothingmusic.R
import androidx.compose.ui.res.stringResource
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.nothingmusic.domain.model.Artist
import com.nothingmusic.domain.model.Folder
import com.nothingmusic.domain.model.Playlist
import com.nothingmusic.domain.model.Track
import com.nothingmusic.ui.PlayerViewModel
import com.nothingmusic.ui.component.AlbumArtView
import com.nothingmusic.ui.component.DotMatrixBadge
import com.nothingmusic.ui.component.DotMatrixHeaderTitle
import com.nothingmusic.ui.component.DotMatrixText
import com.nothingmusic.ui.component.LibrarySearchField
import com.nothingmusic.ui.component.NothingLinearProgress
import com.nothingmusic.ui.component.TrackListItem
import com.nothingmusic.ui.theme.DotMatrixFont
import com.nothingmusic.util.FileUtils

private enum class LibraryTab(val label: String) {
    Songs("Songs"),
    Favorites("Favorites"),
    Playlists("Playlists"),
    Artists("Artists"),
    Folders("Folders"),
}

@Composable
fun LibraryScreen(
    playerViewModel: PlayerViewModel,
    onNavigateToNowPlaying: () -> Unit,
    onNavigateToPlaylist: (Long) -> Unit,
    onNavigateToQueue: () -> Unit,
    onNavigateToFolderPicker: () -> Unit = {},
    viewModel: LibraryViewModel = hiltViewModel(),
) {
    val tracks by viewModel.tracks.collectAsState()
    val playlists by viewModel.playlists.collectAsState()
    val artists by viewModel.artists.collectAsState()
    val folders by viewModel.folders.collectAsState()
    val hasScannedFolders by viewModel.hasScannedFolders.collectAsState()
    val favoriteIds by viewModel.favoriteIds.collectAsState()
    val favoriteTracks by viewModel.favoriteTracks.collectAsState()
    val currentTrack by playerViewModel.currentTrack.collectAsState()
    val isPlaying by playerViewModel.isPlaying.collectAsState()

    var selectedTab by remember { mutableIntStateOf(0) }
    val searchViewModel: SearchViewModel = hiltViewModel()
    val searchQuery by searchViewModel.query.collectAsState()
    val searchResults by searchViewModel.results.collectAsState()
    val searchLibrary by searchViewModel.allTracks.collectAsState()
    var searchOpen by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.ensureScanned()
    }

    Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
        // NothingOS-style header: dot-matrix title + red accent
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DotMatrixHeaderTitle(
                text = stringResource(R.string.music),
                modifier = Modifier,
            )
            Spacer(modifier = Modifier.weight(1f))
            AnimatedVisibility(visible = searchOpen) {
                IconButton(onClick = {
                    searchOpen = false
                    searchViewModel.setQuery("")
                }) {
                    Icon(
                        imageVector = Icons.Outlined.Close,
                        contentDescription = stringResource(R.string.clear),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            IconButton(onClick = { searchOpen = !searchOpen }) {
                Icon(
                    imageVector = Icons.Outlined.Search,
                    contentDescription = stringResource(R.string.search),
                    tint = if (searchOpen) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            IconButton(onClick = { viewModel.refresh() }) {
                Icon(
                    imageVector = Icons.Outlined.Refresh,
                    contentDescription = "Refresh",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        AnimatedVisibility(visible = searchOpen) {
            LibrarySearchField(
                query = searchQuery,
                onQueryChange = searchViewModel::setQuery,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 4.dp),
            )
        }

        LibraryTabRow(
            selectedIndex = selectedTab,
            onSelect = { selectedTab = it },
        )

        AnimatedContent(
            targetState = selectedTab,
            transitionSpec = {
                (fadeIn(NothingSpring) +
                    slideInHorizontally(NothingSpringOffset) { it / 8 })
                    .togetherWith(
                        fadeOut(NothingSpring) + slideOutHorizontally(NothingSpringOffset) { -it / 12 },
                    )
            },
            label = "tabContent",
        ) { tabIndex ->
            if (searchOpen) {
                LibrarySearchResults(
                    query = searchQuery,
                    results = searchResults,
                    librarySize = searchLibrary.size,
                    onPlay = { track, trackList ->
                        playerViewModel.playTrack(track, trackList)
                        onNavigateToNowPlaying()
                    },
                )
            } else {
                when (LibraryTab.entries[tabIndex]) {
                    LibraryTab.Songs -> SongsTab(
                        tracks = tracks,
                        favoriteIds = favoriteIds,
                        currentTrack = currentTrack,
                        isPlaying = isPlaying,
                        hasScannedFolders = hasScannedFolders,
                        onPickFolders = onNavigateToFolderPicker,
                        onPlay = { track, trackList ->
                            playerViewModel.playTrack(track, trackList)
                            onNavigateToNowPlaying()
                        },
                        onToggleFavorite = viewModel::toggleFavorite,
                    )
                    LibraryTab.Favorites -> FavoritesTab(
                        tracks = favoriteTracks,
                        favoriteIds = favoriteIds,
                        currentTrack = currentTrack,
                        isPlaying = isPlaying,
                        onPlay = { track, trackList ->
                            playerViewModel.playTrack(track, trackList)
                            onNavigateToNowPlaying()
                        },
                        onToggleFavorite = viewModel::toggleFavorite,
                    )
                    LibraryTab.Playlists -> PlaylistsTab(
                        playlists = playlists,
                        onCreatePlaylist = viewModel::createPlaylist,
                        onPlaylistClick = onNavigateToPlaylist,
                    )
                    LibraryTab.Artists -> ArtistsTab(artists = artists)
                    LibraryTab.Folders -> FoldersTab(
                        folders = folders,
                        tracks = tracks,
                        onPlayFolder = { folder ->
                            val folderTracks = tracks.filter { it.folderPath == folder.path }
                            if (folderTracks.isNotEmpty()) {
                                playerViewModel.playAll(folderTracks)
                                onNavigateToNowPlaying()
                            }
                        },
                    )
                }
            }
        }
    }
}

/** 搜索展开后的结果区：空查询显示曲库规模，有查询显示命中列表或空结果提示。 */
@Composable
private fun LibrarySearchResults(
    query: String,
    results: List<Track>,
    librarySize: Int,
    onPlay: (Track, List<Track>) -> Unit,
) {
    when {
        query.isBlank() -> {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = stringResource(R.string.search_hint_library_size, librarySize),
                    style = MaterialTheme.typography.bodyMedium.copy(fontFamily = DotMatrixFont),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 24.dp),
                    textAlign = TextAlign.Center,
                )
            }
        }

        results.isEmpty() -> {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = stringResource(R.string.search_no_results),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 24.dp),
                    textAlign = TextAlign.Center,
                )
            }
        }

        else -> {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                item {
                    Text(
                        text = stringResource(R.string.search_result_count, results.size),
                        style = MaterialTheme.typography.labelMedium.copy(fontFamily = DotMatrixFont),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                    )
                }
                items(results, key = { it.id }) { track ->
                    TrackListItem(
                        track = track,
                        onClick = { onPlay(track, results) },
                    )
                }
            }
        }
    }
}

@Composable
private fun LibraryTabRow(
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
) {
    val tabs = LibraryTab.entries
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background),
    ) {
        tabs.forEachIndexed { index, tab ->
            val selected = selectedIndex == index
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clickable { onSelect(index) }
                    .padding(top = 14.dp, bottom = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = tab.label,
                    style = MaterialTheme.typography.titleSmall.copy(fontFamily = DotMatrixFont),
                    color = if (selected) {
                        MaterialTheme.colorScheme.onBackground
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                Spacer(modifier = Modifier.height(6.dp))
                Box(
                    modifier = Modifier
                        .height(3.dp)
                        .width(24.dp)
                        .background(
                            color = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent,
                            shape = RoundedCornerShape(2.dp),
                        ),
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SongsTab(
    tracks: List<Track>,
    favoriteIds: List<Long>,
    currentTrack: Track?,
    isPlaying: Boolean,
    hasScannedFolders: Boolean,
    onPickFolders: () -> Unit,
    onPlay: (Track, List<Track>) -> Unit,
    onToggleFavorite: (Track) -> Unit,
) {
    val listState = rememberLazyListState()
    val favSet = remember(favoriteIds) { favoriteIds.toSet() }

    if (tracks.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = if (hasScannedFolders) {
                        "No tracks found in selected folders."
                    } else {
                        "No music folders selected."
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontFamily = DotMatrixFont,
                    textAlign = TextAlign.Center,
                )
                Spacer(modifier = Modifier.height(16.dp))
                Button(
                    onClick = onPickFolders,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    ),
                ) {
                    Text(
                        text = if (hasScannedFolders) "Change Folders" else "Pick Folders",
                        fontFamily = DotMatrixFont,
                    )
                }
            }
        }
        return
    }

    LazyColumn(
        state = listState,
        contentPadding = PaddingValues(bottom = 16.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        itemsIndexed(
            items = tracks,
            key = { _, track -> track.id },
        ) { index, track ->
            val isCurrent = currentTrack?.id == track.id
            TrackListItem(
                track = track,
                isCurrent = isCurrent,
                isPlaying = isPlaying,
                isFavorite = track.id in favSet,
                onClick = { onPlay(track, tracks) },
                onFavoriteClick = { onToggleFavorite(track) },
            )
        }
    }
}

@Composable
private fun FavoritesTab(
    tracks: List<Track>,
    favoriteIds: List<Long>,
    currentTrack: Track?,
    isPlaying: Boolean,
    onPlay: (Track, List<Track>) -> Unit,
    onToggleFavorite: (Track) -> Unit,
) {
    val favSet = remember(favoriteIds) { favoriteIds.toSet() }

    if (tracks.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = "No favorite tracks yet.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontFamily = DotMatrixFont,
                textAlign = TextAlign.Center,
            )
        }
        return
    }

    LazyColumn(
        contentPadding = PaddingValues(bottom = 16.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        itemsIndexed(
            items = tracks,
            key = { _, track -> track.id },
        ) { _, track ->
            TrackListItem(
                track = track,
                isCurrent = currentTrack?.id == track.id,
                isPlaying = isPlaying,
                isFavorite = track.id in favSet,
                onClick = { onPlay(track, tracks) },
                onFavoriteClick = { onToggleFavorite(track) },
            )
        }
    }
}

@Composable
private fun PlaylistsTab(
    playlists: List<Playlist>,
    onCreatePlaylist: (String) -> Unit,
    onPlaylistClick: (Long) -> Unit,
) {
    var showCreate by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Spacer(modifier = Modifier.weight(1f))
            Button(
                onClick = { showCreate = true },
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ),
            ) {
                Icon(
                    imageVector = Icons.Outlined.Add,
                    contentDescription = null,
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = stringResource(R.string.new_playlist),
                    fontFamily = DotMatrixFont,
                )
            }
        }

        if (playlists.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(R.string.no_playlists_yet),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontFamily = DotMatrixFont,
                )
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(bottom = 16.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(playlists, key = { it.id }) { playlist ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPlaylistClick(playlist.id) }
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(42.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(MaterialTheme.colorScheme.surface),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.PlaylistPlay,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                        Spacer(modifier = Modifier.width(14.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = playlist.name,
                                style = MaterialTheme.typography.titleSmall.copy(fontFamily = DotMatrixFont),
                                color = MaterialTheme.colorScheme.onBackground,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "${playlist.trackCount} tracks",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Icon(
                            imageVector = Icons.Outlined.KeyboardArrowRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }

    if (showCreate) {
        AlertDialog(
            onDismissRequest = { showCreate = false },
            title = {
                Text(
                    text = stringResource(R.string.new_playlist),
                    fontFamily = DotMatrixFont,
                )
            },
            text = {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    placeholder = { Text(stringResource(R.string.playlist_name)) },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val name = newName.trim()
                        if (name.isNotEmpty()) onCreatePlaylist(name)
                        newName = ""
                        showCreate = false
                    },
                ) {
                    Text(stringResource(R.string.create), fontFamily = DotMatrixFont)
                }
            },
            dismissButton = {
                TextButton(onClick = { newName = ""; showCreate = false }) {
                    Text(stringResource(R.string.cancel), fontFamily = DotMatrixFont)
                }
            },
        )
    }
}

@Composable
private fun ArtistsTab(artists: List<Artist>) {
    LazyColumn(
        contentPadding = PaddingValues(bottom = 16.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(artists, key = { it.name }) { artist ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(52.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center,
                ) {
                    if (artist.avatarUri.isNullOrBlank()) {
                        DotMatrixText(
                            text = artist.name.take(1).uppercase(),
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    } else {
                        AlbumArtView(
                            artUri = artist.avatarUri,
                            modifier = Modifier.fillMaxSize(),
                            cornerRadius = 100,
                            contentScale = ContentScale.Crop,
                        )
                    }
                }
                Column(modifier = Modifier.padding(start = 16.dp).weight(1f)) {
                    Text(
                        text = artist.name,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onBackground,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "${artist.albumCount} albums · ${artist.trackCount} tracks",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun FoldersTab(
    folders: List<Folder>,
    tracks: List<Track>,
    onPlayFolder: (Folder) -> Unit,
) {
    if (folders.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = stringResource(R.string.no_folders_scanned),
                    style = MaterialTheme.typography.titleSmall.copy(fontFamily = DotMatrixFont),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = stringResource(R.string.add_folders_in_settings_to_start_listening),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
        return
    }

    LazyColumn(
        contentPadding = PaddingValues(bottom = 16.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(folders, key = { it.path }) { folder ->
            val folderTracks = tracks.filter { it.folderPath == folder.path }
            val totalDuration = folderTracks.sumOf { it.durationMs }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .clickable { onPlayFolder(folder) }
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.PlaylistPlay,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 14.dp)
                ) {
                    Text(
                        text = folder.name,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onBackground,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "${folder.trackCount} tracks · ${FileUtils.readableDuration(totalDuration)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .background(MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Filled.PlayArrow,
                        contentDescription = "Play folder",
                        tint = MaterialTheme.colorScheme.background,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun MusicNoteGlyph(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(64.dp)
            .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(50)),
    )
}