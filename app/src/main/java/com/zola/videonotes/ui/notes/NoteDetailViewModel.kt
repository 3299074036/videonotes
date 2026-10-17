package com.zola.videonotes.ui.notes

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zola.videonotes.data.notes.NoteRepository
import com.zola.videonotes.domain.model.Note
import com.zola.videonotes.ui.navigation.Routes
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 笔记详情页 UI 状态。 */
sealed interface NoteDetailUiState {
    data object Loading : NoteDetailUiState
    data class Ready(val note: Note) : NoteDetailUiState
    data class Error(val message: String) : NoteDetailUiState
}

/**
 * 笔记详情页 ViewModel（M5）：富文本阅读 + 重命名 / 删除。
 * 分享 / 导出 / 复制在 Screen 层用 Activity API 完成。
 */
@HiltViewModel
class NoteDetailViewModel @Inject constructor(
    private val noteRepository: NoteRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val noteId: Long = savedStateHandle.get<Long>(Routes.ARG_NOTE_ID) ?: -1L

    private val _uiState = MutableStateFlow<NoteDetailUiState>(NoteDetailUiState.Loading)
    val uiState: StateFlow<NoteDetailUiState> = _uiState.asStateFlow()

    init {
        reload()
    }

    fun reload() {
        viewModelScope.launch {
            val note = noteRepository.getById(noteId)
            _uiState.value = if (note != null) {
                NoteDetailUiState.Ready(note)
            } else {
                NoteDetailUiState.Error("笔记不存在或已被删除")
            }
        }
    }

    /** 重命名：空标题忽略，成功后刷新。 */
    fun rename(title: String) {
        val trimmed = title.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            runCatching { noteRepository.rename(noteId, trimmed) }
            reload()
        }
    }

    /** 删除：返回是否成功，由界面决定是否回退。 */
    suspend fun delete(): Boolean =
        runCatching { noteRepository.delete(noteId) }.isSuccess
}
