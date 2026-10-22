package com.nothingmusic.ui.screen.search

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.nothingmusic.R
import com.nothingmusic.domain.model.Track
import com.nothingmusic.ui.PlayerViewModel
import com.nothingmusic.ui.component.AddToPlaylistDialog
import com.nothingmusic.ui.component.CollectableTrackRow
import com.nothingmusic.ui.component.DotMatrixHeaderTitle
import com.nothingmusic.ui.component.LibrarySearchField
import com.nothingmusic.ui.theme.DotMatrixFont

/**
 * 全局搜索：一次查询本地曲库与所有已启用的远程来源，
 * 结果按「本地 / 各来源」分段展示。
 */
@Composable
fun GlobalSearchScreen(
    playerViewModel: PlayerViewModel,
    onNavigateToNowPlaying: () -> Unit,
    viewModel: GlobalSearchViewModel = hiltViewModel(),
) {
    val query by viewModel.query.collectAsState()
    val results by viewModel.results.collectAsState()
    val sourceNames by viewModel.sourceNames.collectAsState()
    val favoriteIds by viewModel.favoriteIds.collectAsState()
    val playlists by viewModel.playlists.collectAsState()
    val favoriteIdSet = remember(favoriteIds) { favoriteIds.toSet() }
    var playlistTarget by remember { mutableStateOf<Track?>(null) }

    val remoteGroups = remember(results.remote, sourceNames) {
        results.remote.groupBy { it.sourceId }
            .toList()
            .sortedBy { (sourceId, _) -> sourceNames[sourceId].orEmpty() }
    }

    Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DotMatrixHeaderTitle(
                text = stringResource(R.string.tab_search),
                modifier = Modifier,
            )
            Spacer(modifier = Modifier.weight(1f))
            if (results.totalCount > 0) {
                Text(
                    text = stringResource(R.string.search_result_count, results.totalCount),
                    style = MaterialTheme.typography.labelMedium.copy(fontFamily = DotMatrixFont),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        LibrarySearchField(
            query = query,
            onQueryChange = viewModel::setQuery,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 4.dp),
        )

        when {
            query.isBlank() -> {
                CenteredHint(stringResource(R.string.search_hint_all_sources))
            }

            results.isEmpty -> {
                CenteredHint(stringResource(R.string.search_no_results))
            }

            else -> {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    if (results.local.isNotEmpty()) {
                        item(key = "local-header") {
                            SearchSectionHeader(
                                label = stringResource(R.string.search_section_local),
                                count = results.local.size,
                            )
                        }
                        items(results.local, key = { "local-${it.id}" }) { track ->
                            CollectableTrackRow(
                                track = track,
                                isFavorite = track.id in favoriteIdSet,
                                onClick = {
                                    playerViewModel.playTrack(track, results.local)
                                    onNavigateToNowPlaying()
                                },
                                onToggleFavorite = { viewModel.toggleFavorite(track) },
                                onAddToPlaylist = { playlistTarget = track },
                            )
                        }
                    }

                    remoteGroups.forEach { (sourceId, entries) ->
                        item(key = "cloud-$sourceId") {
                            SearchSectionHeader(
                                label = sourceNames[sourceId] ?: sourceId,
                                count = entries.size,
                            )
                        }
                        val remoteQueue = entries.map { it.track }
                        items(entries, key = { "cloud-${it.track.id}" }) { entry ->
                            CollectableTrackRow(
                                track = entry.track,
                                isFavorite = entry.track.id in favoriteIdSet,
                                onClick = {
                                    playerViewModel.playTrack(entry.track, remoteQueue)
                                    onNavigateToNowPlaying()
                                },
                                onToggleFavorite = { viewModel.toggleFavorite(entry.track) },
                                onAddToPlaylist = { playlistTarget = entry.track },
                            )
                        }
                    }
                }
            }
        }
    }

    playlistTarget?.let { target ->
        AddToPlaylistDialog(
            track = target,
            playlists = playlists,
            onSelect = { playlist ->
                viewModel.addToPlaylist(playlist.id, target)
                playlistTarget = null
            },
            onDismiss = { playlistTarget = null },
        )
    }
}

@Composable
private fun CenteredHint(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = DotMatrixFont),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 24.dp),
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun SearchSectionHeader(label: String, count: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleSmall.copy(fontFamily = DotMatrixFont),
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(modifier = Modifier.weight(1f))
        Text(
            text = "$count",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
