package com.zola.videonotes.ui.notes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zola.videonotes.data.notes.NoteRepository
import com.zola.videonotes.domain.model.Note
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/** 笔记库 ViewModel（M5）：观察全部笔记，创建时间倒序。 */
@HiltViewModel
class NoteLibraryViewModel @Inject constructor(
    noteRepository: NoteRepository,
) : ViewModel() {

    val notes: StateFlow<List<Note>> = noteRepository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
}
