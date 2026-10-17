package com.zola.videonotes.ui.metadata

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zola.videonotes.data.notes.NoteRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 课堂笔记 Tab 的极简 ViewModel：按 id 取笔记 markdown 与 storageKey。 */
@HiltViewModel
class NoteTabViewModel @Inject constructor(
    private val noteRepository: NoteRepository,
) : ViewModel() {

    private val _markdown = MutableStateFlow("")
    val markdown: StateFlow<String> = _markdown.asStateFlow()

    var storageKey: String? = null
        private set

    fun load(noteId: Long) {
        viewModelScope.launch {
            val note = noteRepository.getById(noteId)
            _markdown.value = note?.markdown.orEmpty()
            storageKey = note?.videoStorageKey
        }
    }
}
