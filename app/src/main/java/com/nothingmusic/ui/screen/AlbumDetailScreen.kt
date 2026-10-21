package com.nothingmusic.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBackIosNew
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import com.nothingmusic.R
import androidx.compose.ui.res.stringResource
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.nothingmusic.domain.model.Album
import com.nothingmusic.domain.model.Track
import com.nothingmusic.ui.PlayerViewModel
import com.nothingmusic.ui.component.AlbumArtView
import com.nothingmusic.ui.component.TrackListItem
import com.nothingmusic.ui.theme.DotMatrixFont
import com.nothingmusic.util.FileUtils

@Composable
fun AlbumDetailScreen(
    albumName: String,
    albumArtist: String,
    playerViewModel: PlayerViewModel,
    onBack: () -> Unit,
    viewModel: LibraryViewModel = hiltViewModel(),
) {
    val tracks by viewModel.tracks.collectAsState()
    val favoriteIds by viewModel.favoriteIds.collectAsState()
    val currentTrack by playerViewModel.currentTrack.collectAsState()
    val isPlaying by playerViewModel.isPlaying.collectAsState()

    val albumTracks = tracks.filter { it.album == albumName && it.resolvedAlbumArtist == albumArtist }
    val album = Album(
        name = albumName,
        artist = albumArtist,
        albumArtUri = albumTracks.firstNotNullOfOrNull { it.albumArtUri?.toString() },
        trackCount = albumTracks.size,
        durationMs = albumTracks.sumOf { it.durationMs },
    )
    val favSet = favoriteIds.toSet()

    Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.Outlined.ArrowBackIosNew,
                    contentDescription = "Back",
                    tint = MaterialTheme.colorScheme.onBackground,
                )
            }
            Text(
                text = stringResource(R.string.album),
                style = MaterialTheme.typography.titleMedium.copy(fontFamily = DotMatrixFont),
                color = MaterialTheme.colorScheme.onBackground,
            )
        }

        LazyColumn(
            contentPadding = PaddingValues(bottom = 24.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    AlbumArtView(
                        artUri = album.albumArtUri,
                        modifier = Modifier
                            .size(220.dp),
                        cornerRadius = 16,
                    )
                    Spacer(modifier = Modifier.height(20.dp))
                    Text(
                        text = album.name,
                        style = MaterialTheme.typography.headlineSmall.copy(fontFamily = DotMatrixFont),
                        color = MaterialTheme.colorScheme.onBackground,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = album.artist,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "${album.trackCount} tracks · ${FileUtils.readableDuration(album.durationMs)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    // Play all button (Nothing glyph style)
                    Row(
                        modifier = Modifier
                            .padding(vertical = 16.dp)
                            .clickable {
                                if (albumTracks.isNotEmpty()) {
                                    playerViewModel.playAll(albumTracks)
                                }
                            }
                            .clip(MaterialTheme.shapes.medium)
                            .background(MaterialTheme.colorScheme.primary)
                            .padding(horizontal = 28.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.PlayArrow,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimary,
                        )
                        Text(
                            text = stringResource(R.string.play_all),
                            style = MaterialTheme.typography.labelMedium.copy(fontFamily = DotMatrixFont),
                            color = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.padding(start = 6.dp),
                        )
                    }
                }
            }

            items(albumTracks, key = { it.id }) { track ->
                TrackListItem(
                    track = track,
                    isCurrent = currentTrack?.id == track.id,
                    isPlaying = isPlaying,
                    isFavorite = track.id in favSet,
                    onClick = {
                        playerViewModel.playTrack(track, albumTracks)
                    },
                    onFavoriteClick = { viewModel.toggleFavorite(track) },
                )
            }
        }
    }
}