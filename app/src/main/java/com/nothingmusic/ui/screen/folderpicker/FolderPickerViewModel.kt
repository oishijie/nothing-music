package com.nothingmusic.ui.screen.folderpicker

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nothingmusic.domain.model.Folder
import com.nothingmusic.domain.repository.MusicRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class FolderPickerViewModel @Inject constructor(
    private val repository: MusicRepository,
) : ViewModel() {

    val folders: StateFlow<List<Folder>> = repository.folders
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    fun addFolder(uri: Uri) {
        if (_busy.value) return
        viewModelScope.launch {
            _busy.value = true
            try {
                repository.addScannedFolder(uri.toString())
            } finally {
                _busy.value = false
            }
        }
    }

    fun removeFolder(path: String) {
        if (_busy.value) return
        viewModelScope.launch {
            _busy.value = true
            try {
                repository.removeScannedFolder(path)
            } finally {
                _busy.value = false
            }
        }
    }

    fun refresh() {
        if (_busy.value) return
        viewModelScope.launch {
            _busy.value = true
            try {
                repository.refreshLibrary(repository.getAllScannedFolderPaths())
            } finally {
                _busy.value = false
            }
        }
    }
}
