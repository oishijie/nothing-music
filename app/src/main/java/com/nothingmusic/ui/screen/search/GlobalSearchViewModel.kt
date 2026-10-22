package com.nothingmusic.ui.screen.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mica.music.RemoteRuntime
import com.nothingmusic.domain.model.Playlist
import com.nothingmusic.domain.model.Track
import com.nothingmusic.domain.repository.MusicRepository
import com.nothingmusic.remote.toTrack
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class RemoteSearchEntry(val track: Track, val sourceId: String)

data class GlobalSearchResults(
    val local: List<Track> = emptyList(),
    val remote: List<RemoteSearchEntry> = emptyList(),
) {
    val totalCount: Int get() = local.size + remote.size
    val isEmpty: Boolean get() = totalCount == 0
}

/** 命中标题、艺术家、专辑、专辑艺术家或文件名（远程曲目）之一。 */
internal fun Track.matchesSearch(query: String): Boolean {
    val needle = query.trim().lowercase()
    if (needle.isEmpty()) return false
    return title.lowercase().contains(needle) ||
        artist.lowercase().contains(needle) ||
        album.lowercase().contains(needle) ||
        albumArtist.lowercase().contains(needle) ||
        documentName.lowercase().contains(needle)
}

/**
 * 全局搜索：同时查询本地曲库（Room）与已启用来源的远程曲库缓存，
 * 远程部分只读目录缓存，不触发网络请求。
 */
@OptIn(FlowPreview::class)
@HiltViewModel
class GlobalSearchViewModel @Inject constructor(
    private val repository: MusicRepository,
) : ViewModel() {

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val remoteEntries = MutableStateFlow<List<RemoteSearchEntry>>(emptyList())
    private val _sourceNames = MutableStateFlow<Map<String, String>>(emptyMap())
    val sourceNames: StateFlow<Map<String, String>> = _sourceNames.asStateFlow()

    val results: StateFlow<GlobalSearchResults> =
        combine(
            _query.debounce(250).distinctUntilChanged(),
            repository.tracks,
            remoteEntries,
        ) { query, localTracks, remote ->
            if (query.isBlank()) {
                GlobalSearchResults()
            } else {
                GlobalSearchResults(
                    local = localTracks.filter { it.matchesSearch(query) },
                    remote = remote.filter { it.track.matchesSearch(query) },
                )
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), GlobalSearchResults())

    val favoriteIds: StateFlow<List<Long>> = repository.favoriteIds
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val playlists: StateFlow<List<Playlist>> = repository.playlists
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        viewModelScope.launch {
            RemoteRuntime.catalogRepository.observeTracksForEnabledSources()
                .collectLatest { summaries ->
                    remoteEntries.value = withContext(Dispatchers.Default) {
                        summaries.map {
                            RemoteSearchEntry(it.toTrack(), it.ref.sourceInstanceId)
                        }
                    }
                }
        }
        viewModelScope.launch {
            RemoteRuntime.catalogRepository.observeSources().collectLatest { sources ->
                _sourceNames.value = sources.associate { it.id to it.displayName }
            }
        }
    }

    fun setQuery(value: String) {
        _query.value = value
    }

    fun toggleFavorite(track: Track) {
        viewModelScope.launch { repository.toggleFavorite(track) }
    }

    fun addToPlaylist(playlistId: Long, track: Track) {
        viewModelScope.launch { repository.addToPlaylist(playlistId, listOf(track)) }
    }
}
