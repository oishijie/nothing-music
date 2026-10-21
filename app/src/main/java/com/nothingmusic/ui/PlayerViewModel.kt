package com.nothingmusic.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nothingmusic.domain.model.Track
import com.nothingmusic.service.PlayerController
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class PlayerViewModel(application: Application) : AndroidViewModel(application) {

    val controller = PlayerController(application.applicationContext)

    val isConnected: StateFlow<Boolean> = controller.isConnected
    val isPlaying: StateFlow<Boolean> = controller.isPlaying
    val isBuffering: StateFlow<Boolean> = controller.isBuffering
    val currentTrack: StateFlow<Track?> = controller.currentTrack
    val queue: StateFlow<List<Track>> = controller.queue
    val currentIndex: StateFlow<Int> = controller.currentTrackIndex
    val currentPosition: StateFlow<Long> = controller.currentPositionMs
    val duration: StateFlow<Long> = controller.durationMs
    val repeatMode: StateFlow<Int> = controller.repeatMode
    val isShuffled: StateFlow<Boolean> = controller.isShuffled
    val sleepTimerActive: StateFlow<Boolean> = controller.sleepTimerActive
    val sleepTimerRemainingMs: StateFlow<Long> = controller.sleepTimerRemainingMs

    private var positionTicker: Job? = null

    init {
        viewModelScope.launch {
            controller.connect()
            controller.initSleepTimerState()
            positionTicker = viewModelScope.launch {
                while (true) {
                    if (controller.isPlaying.value) {
                        controller.forceSyncPosition()
                    }
                    delay(500)
                }
            }
        }
    }

    fun playPause() = controller.playPause()
    fun play() = controller.play()
    fun pause() = controller.pause()
    fun next() = controller.next()
    fun previous() = controller.previous()
    fun seekTo(positionMs: Long) = controller.seekTo(positionMs)
    fun toggleRepeat() = controller.toggleRepeat()
    fun toggleShuffle() = controller.toggleShuffle()
    fun playTrack(track: Track, tracks: List<Track>) = controller.playTrack(track, tracks)
    fun playAll(tracks: List<Track>, startIndex: Int = 0) = controller.playAll(tracks, startIndex)
    fun addToQueue(tracks: List<Track>) = controller.addToQueue(tracks)
    fun playNext(track: Track) = controller.playNext(track)
    fun moveQueueItem(from: Int, to: Int) = controller.moveQueueItem(from, to)
    fun removeQueueItem(index: Int) = controller.removeQueueItem(index)
    fun clearQueue() = controller.clearQueue()
    fun jumpToQueueIndex(index: Int) = controller.jumpToQueueIndex(index)
    fun setSleepTimer(durationMs: Long, fadeOut: Boolean) =
        controller.setSleepTimer(durationMs, fadeOut)
    fun cancelSleepTimer() = controller.cancelSleepTimer()

    override fun onCleared() {
        positionTicker?.cancel()
        controller.release()
        super.onCleared()
    }
}