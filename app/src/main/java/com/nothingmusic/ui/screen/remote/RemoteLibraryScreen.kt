package com.nothingmusic.ui.screen.remote

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.nothingmusic.R
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.mica.music.RemoteRuntime
import com.mica.music.data.remote.RemoteTrackSummary
import com.nothingmusic.domain.model.Track
import com.nothingmusic.remote.toTrack
import com.nothingmusic.ui.component.AddToPlaylistDialog
import com.nothingmusic.ui.component.CollectableTrackRow
import com.nothingmusic.ui.component.DotMatrixHeaderTitle
import com.nothingmusic.ui.component.LibrarySearchField
import com.nothingmusic.ui.theme.DotMatrixFont
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.withContext

/** 曲目连同它所属的来源，用于按来源筛选与分组。 */
private data class RemoteEntry(val track: Track, val sourceId: String)

/**
 * 远程曲库：列出已启用来源的曲目。多来源时顶部提供筛选条，
 * 「全部」视图按来源分段显示，避免不同来源的曲目混成一条长列表。
 */
@Composable
fun RemoteLibraryScreen(
    onPlayTrack: (Track, List<Track>) -> Unit,
    onOpenSources: () -> Unit,
    viewModel: RemoteLibraryViewModel = hiltViewModel(),
) {
    var entries by remember { mutableStateOf<List<RemoteEntry>>(emptyList()) }
    var sourceNames by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var loading by remember { mutableStateOf(true) }
    var searchOpen by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var selectedSource by remember { mutableStateOf<String?>(null) }
    var playlistTarget by remember { mutableStateOf<Track?>(null) }
    val favoriteIds by viewModel.favoriteIds.collectAsState()
    val playlists by viewModel.playlists.collectAsState()
    val favoriteIdSet = remember(favoriteIds) { favoriteIds.toSet() }

    LaunchedEffect(Unit) {
        RemoteRuntime.catalogRepository.observeTracksForEnabledSources()
            .collectLatest { summaries: List<RemoteTrackSummary> ->
                entries = withContext(Dispatchers.Default) {
                    summaries.map { RemoteEntry(it.toTrack(), it.ref.sourceInstanceId) }
                }
                loading = false
            }
    }

    LaunchedEffect(Unit) {
        RemoteRuntime.catalogRepository.observeSources().collectLatest { sources ->
            sourceNames = sources.associate { it.id to it.displayName }
        }
    }

    // 来源被删除后筛选条会失效，回退到「全部」。
    LaunchedEffect(sourceNames) {
        if (selectedSource != null && !sourceNames.containsKey(selectedSource)) {
            selectedSource = null
        }
    }

    val visible = remember(entries, selectedSource) {
        if (selectedSource == null) entries else entries.filter { it.sourceId == selectedSource }
    }

    // 过滤基于已缓存的远程曲库，纯本地计算，不触发网络请求。
    val results = remember(visible, query) {
        val needle = query.lowercase().trim()
        if (needle.isBlank()) {
            emptyList()
        } else {
            visible.filter {
                it.track.title.lowercase().contains(needle) ||
                    it.track.artist.lowercase().contains(needle) ||
                    it.track.album.lowercase().contains(needle)
            }
        }
    }

    val hasMultipleSources = sourceNames.size > 1

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        // 与音乐页一致的 NothingOS 头部：点阵标题 + 右侧动作
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DotMatrixHeaderTitle(
                text = stringResource(R.string.remote_title),
                modifier = Modifier,
            )
            Spacer(modifier = Modifier.weight(1f))
            AnimatedVisibility(visible = searchOpen) {
                IconButton(onClick = {
                    searchOpen = false
                    query = ""
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
            IconButton(onClick = onOpenSources) {
                Icon(
                    imageVector = Icons.Filled.Settings,
                    contentDescription = stringResource(R.string.remote_settings),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        AnimatedVisibility(visible = searchOpen) {
            LibrarySearchField(
                query = query,
                onQueryChange = { query = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 4.dp),
            )
        }

        if (hasMultipleSources) {
            SourceFilterRow(
                sourceNames = sourceNames,
                selectedSource = selectedSource,
                onSelect = { selectedSource = it },
            )
        }

        Text(
            text = "${visible.size} " + stringResource(R.string.remote_track_count_suffix),
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = DotMatrixFont),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        )

        when {
            entries.isEmpty() -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Filled.Cloud,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = if (loading) "正在读取远程曲库…" else "还没有云盘曲目，先去「来源设置」添加",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
            }

            searchOpen -> {
                when {
                    query.isBlank() -> {
                        CenteredHint(
                            text = stringResource(R.string.search_hint_cloud_size, visible.size),
                            useDotMatrix = true,
                        )
                    }

                    results.isEmpty() -> {
                        CenteredHint(text = stringResource(R.string.search_no_results))
                    }

                    else -> {
                        Text(
                            text = stringResource(R.string.search_result_count, results.size),
                            style = MaterialTheme.typography.labelMedium.copy(fontFamily = DotMatrixFont),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                        )
                        val queue = remember(results) { results.map { it.track } }
                        TrackList(
                            entries = results,
                            favoriteIds = favoriteIdSet,
                            onPlay = { onPlayTrack(it, queue) },
                            onToggleFavorite = viewModel::toggleFavorite,
                            onAddToPlaylist = { playlistTarget = it },
                        )
                    }
                }
            }

            // 选了具体来源就不再分段，直接平铺该来源的曲目。
            selectedSource != null -> {
                val queue = remember(visible) { visible.map { it.track } }
                TrackList(
                    entries = visible,
                    favoriteIds = favoriteIdSet,
                    onPlay = { onPlayTrack(it, queue) },
                    onToggleFavorite = viewModel::toggleFavorite,
                    onAddToPlaylist = { playlistTarget = it },
                )
            }

            else -> {
                val queue = remember(visible) { visible.map { it.track } }
                GroupedTrackList(
                    entries = visible,
                    sourceNames = sourceNames,
                    favoriteIds = favoriteIdSet,
                    onPlay = { onPlayTrack(it, queue) },
                    onToggleFavorite = viewModel::toggleFavorite,
                    onAddToPlaylist = { playlistTarget = it },
                )
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
private fun CenteredHint(text: String, useDotMatrix: Boolean = false) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = text,
            style = if (useDotMatrix) {
                MaterialTheme.typography.bodyMedium.copy(fontFamily = DotMatrixFont)
            } else {
                MaterialTheme.typography.bodyMedium
            },
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 24.dp),
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun TrackList(
    entries: List<RemoteEntry>,
    favoriteIds: Set<Long>,
    onPlay: (Track) -> Unit,
    onToggleFavorite: (Track) -> Unit,
    onAddToPlaylist: (Track) -> Unit,
) {
    LazyColumn(Modifier.fillMaxSize()) {
        items(entries, key = { it.track.id }) { entry ->
            CollectableTrackRow(
                track = entry.track,
                isFavorite = entry.track.id in favoriteIds,
                onClick = { onPlay(entry.track) },
                onToggleFavorite = { onToggleFavorite(entry.track) },
                onAddToPlaylist = { onAddToPlaylist(entry.track) },
            )
        }
    }
}

@Composable
private fun GroupedTrackList(
    entries: List<RemoteEntry>,
    sourceNames: Map<String, String>,
    favoriteIds: Set<Long>,
    onPlay: (Track) -> Unit,
    onToggleFavorite: (Track) -> Unit,
    onAddToPlaylist: (Track) -> Unit,
) {
    // 按来源分组，组内保持原始顺序（已经按艺术家/标题排过）。
    val groups = remember(entries, sourceNames) {
        entries.groupBy { it.sourceId }
            .toList()
            .sortedBy { (sourceId, _) -> sourceNames[sourceId].orEmpty() }
    }

    LazyColumn(Modifier.fillMaxSize()) {
        groups.forEach { (sourceId, groupEntries) ->
            item(key = "header-$sourceId") {
                SourceHeader(
                    name = sourceNames[sourceId] ?: sourceId,
                    count = groupEntries.size,
                )
            }
            items(groupEntries, key = { it.track.id }) { entry ->
                CollectableTrackRow(
                    track = entry.track,
                    isFavorite = entry.track.id in favoriteIds,
                    onClick = { onPlay(entry.track) },
                    onToggleFavorite = { onToggleFavorite(entry.track) },
                    onAddToPlaylist = { onAddToPlaylist(entry.track) },
                )
            }
        }
    }
}

@Composable
private fun SourceHeader(name: String, count: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = name,
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

/**
 * 与本地曲库页一致的分区切换：点阵标签 + 选中下划线，不用胶囊填充块。
 * 来源数量不定，标签放不下时横向滚动而不是换行挤压。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SourceFilterRow(
    sourceNames: Map<String, String>,
    selectedSource: String?,
    onSelect: (String?) -> Unit,
) {
    val entries = remember(sourceNames) { sourceNames.entries.toList() }
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        item(key = "all") {
            SourceFilterTab(
                label = "全部",
                selected = selectedSource == null,
                onClick = { onSelect(null) },
            )
        }
        items(entries, key = { it.key }) { (id, name) ->
            SourceFilterTab(
                label = name,
                selected = selectedSource == id,
                onClick = { onSelect(id) },
            )
        }
    }
}

@Composable
private fun SourceFilterTab(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(top = 14.dp, bottom = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = label.uppercase(),
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
