package com.zola.videonotes.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zola.videonotes.data.llm.PromptLoader
import com.zola.videonotes.data.llm.PromptOverrides
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * 提示词编辑 ViewModel（一期只开放 system，两档各存各的）：
 * 编辑器初始文本 = 自定义（若有）否则默认资产文本；切档时未保存草稿暂存内存；
 * 保存时校验三条（非空、≤20,000 字、不含 {xxx} 占位），拦截不写入；
 * 保存文本与默认一致时直接清自定义（等价默认）；不含 [[mm:ss]] 只警告不拦截。
 */
@HiltViewModel
class PromptEditorViewModel @Inject constructor(
    private val promptOverrides: PromptOverrides,
    private val promptLoader: PromptLoader,
) : ViewModel() {

    companion object {
        const val MAX_CHARS = 20_000
        private val PLACEHOLDER = Regex("\\{[A-Za-z0-9_]+\\}")
    }

    private val _tab = MutableStateFlow(0)
    val tab: StateFlow<Int> = _tab.asStateFlow()

    private val _text = MutableStateFlow("")
    val text: StateFlow<String> = _text.asStateFlow()

    private val _isCustom = MutableStateFlow(false)
    val isCustom: StateFlow<Boolean> = _isCustom.asStateFlow()

    private val _warning = MutableStateFlow<String?>(null)
    val warning: StateFlow<String?> = _warning.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    /** 两档默认资产文本（IO 线程加载）。 */
    private var localDefault = ""
    private var cloudDefault = ""

    /** 两档自定义（null = 用默认）。 */
    private var localCustom: String? = null
    private var cloudCustom: String? = null

    /** 切档时未保存草稿（内存态）。 */
    private val drafts = mutableMapOf<Int, String>()

    init {
        viewModelScope.launch {
            val defaults = withContext(Dispatchers.IO) {
                runCatching { promptLoader.load("system_note_local.txt") }.getOrDefault("") to
                    runCatching { promptLoader.load("system_note.txt") }.getOrDefault("")
            }
            localDefault = defaults.first
            cloudDefault = defaults.second
            localCustom = promptOverrides.localSystem.first()
            cloudCustom = promptOverrides.cloudSystem.first()
            applyTab(0)
        }
    }

    private fun applyTab(tab: Int) {
        _text.value = drafts[tab] ?: currentCustomFor(tab) ?: defaultFor(tab)
        _isCustom.value = currentCustomFor(tab) != null
        refreshWarning()
    }

    private fun currentCustomFor(tab: Int): String? = if (tab == 0) localCustom else cloudCustom

    private fun defaultFor(tab: Int): String = if (tab == 0) localDefault else cloudDefault

    fun selectTab(tab: Int) {
        if (tab == _tab.value) return
        drafts[_tab.value] = _text.value
        _tab.value = tab
        applyTab(tab)
    }

    fun onTextChange(value: String) {
        _text.value = value
        refreshWarning()
    }

    private fun refreshWarning() {
        _warning.value = if (_text.value.isNotBlank() && ! _text.value.contains("[[mm:ss]]")) {
            "未含 [[mm:ss]] 时间戳标记：生成结果可能被 0 时间点门禁拦截，确认无时间戳需求再保存。"
        } else {
            null
        }
    }

    fun save() {
        val value = _text.value
        val error = when {
            value.isBlank() -> "提示词不能为空"
            value.length > MAX_CHARS -> "提示词超过 20,000 字上限"
            PLACEHOLDER.containsMatchIn(value) -> "system 提示词不含变量，请检查是否误粘了 user 模板（含 {xxx} 占位）"
            else -> null
        }
        if (error != null) {
            _message.value = error
            return
        }
        viewModelScope.launch {
            // 保存文本与默认一致时直接清自定义（等价默认，副标题回「默认」）
            val toStore = value.takeIf { it != defaultFor(_tab.value) }
            if (_tab.value == 0) {
                promptOverrides.setLocalSystem(toStore)
                localCustom = toStore
            } else {
                promptOverrides.setCloudSystem(toStore)
                cloudCustom = toStore
            }
            drafts.remove(_tab.value)
            _isCustom.value = toStore != null
            _message.value = "已保存，下次生成生效"
        }
    }

    fun resetToDefault() {
        viewModelScope.launch {
            if (_tab.value == 0) {
                promptOverrides.setLocalSystem(null)
                localCustom = null
            } else {
                promptOverrides.setCloudSystem(null)
                cloudCustom = null
            }
            drafts.remove(_tab.value)
            _text.value = defaultFor(_tab.value)
            _isCustom.value = false
            refreshWarning()
            _message.value = "已恢复默认"
        }
    }

    fun consumeMessage() {
        _message.value = null
    }
}
