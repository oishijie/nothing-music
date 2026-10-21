package com.nothingmusic.ui.screen

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nothingmusic.domain.model.Lyrics
import com.nothingmusic.domain.model.Track
import com.nothingmusic.domain.repository.MusicRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

@HiltViewModel
class LyricsViewModel @Inject constructor(
    private val repository: MusicRepository,
) : ViewModel() {

    private val _lyrics = MutableStateFlow(Lyrics.Empty)
    val lyrics: StateFlow<Lyrics> = _lyrics.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private var loadedTrackId: Long = -1L

    fun loadForTrack(track: Track?) {
        val t = track ?: return
        if (t.id == loadedTrackId) return
        loadedTrackId = t.id
        _lyrics.value = Lyrics.Empty
        _loading.value = true
        viewModelScope.launch {
            // 远程歌词是网络 IO，给它一个上限，避免面板一直停在加载态。
            val result = withTimeoutOrNull(REMOTE_LYRICS_TIMEOUT_MS) {
                repository.getLyrics(t)
            }
            _lyrics.value = result ?: Lyrics.Empty
            _loading.value = false
        }
    }

    private companion object {
        // 远程链路：内嵌读取（合并后约 2-4 个往返）+ LRCLIB 在线回退，慢网下 10s 不够。
        const val REMOTE_LYRICS_TIMEOUT_MS = 20_000L
    }
}