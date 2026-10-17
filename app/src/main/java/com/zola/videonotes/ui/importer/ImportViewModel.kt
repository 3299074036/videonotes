package com.zola.videonotes.ui.importer

import androidx.lifecycle.ViewModel
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

/** 视频导入页 ViewModel（M5：由原首页 HomeViewModel 搬迁改名）。 */
@HiltViewModel
class ImportViewModel @Inject constructor() : ViewModel() {

    var input by mutableStateOf("")
        private set

    fun onInputChange(value: String) {
        input = value
    }

    /** 从剪贴板粘贴后填入输入框。 */
    fun onPasted(text: String) {
        input = text
    }
}
