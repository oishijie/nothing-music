package com.nothingmusic.ui.screen.remote

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nothingmusic.domain.model.Playlist
import com.nothingmusic.domain.model.Track
import com.nothingmusic.domain.repository.MusicRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class RemoteLibraryViewModel @Inject constructor(
    private val repository: MusicRepository,
) : ViewModel() {

    val favoriteIds: StateFlow<List<Long>> = repository.favoriteIds
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val playlists: StateFlow<List<Playlist>> = repository.playlists
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun toggleFavorite(track: Track) {
        viewModelScope.launch { repository.toggleFavorite(track) }
    }

    fun addToPlaylist(playlistId: Long, track: Track) {
        viewModelScope.launch { repository.addToPlaylist(playlistId, listOf(track)) }
    }
}
