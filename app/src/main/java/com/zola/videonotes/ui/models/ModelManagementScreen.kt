package com.zola.videonotes.ui.models

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zola.videonotes.data.asr.ModelDownloadManager
import com.zola.videonotes.data.catalog.CatalogEntry
import com.zola.videonotes.data.catalog.CatalogKind
import com.zola.videonotes.data.catalog.CatalogStore
import com.zola.videonotes.data.catalog.UserCatalog
import com.zola.videonotes.data.llm.LlmEngineType
import com.zola.videonotes.data.llm.LlmPresets
import com.zola.videonotes.data.llm.local.LocalLlmModels
import com.zola.videonotes.data.llm.local.LocalModelDownloadManager
import com.zola.videonotes.domain.asr.formatBytes
import com.zola.videonotes.ui.components.PaperSegmentedRow
import com.zola.videonotes.ui.navigation.Routes
import com.zola.videonotes.ui.settings.LlmSettingsUiState
import com.zola.videonotes.ui.settings.LlmSettingsViewModel
import com.zola.videonotes.ui.theme.VideoNotesTokens

/**
 * 统一模型管理页（v2）：
 *
 * - 完整版（section = [Routes.SECTION_FULL]）：顶部 Tab 切「大语言模型 / 语音识别」，
 *   大语言模型 Tab 内再分「云端 API / 本地模型」，带已下载汇总条；
 * - 直达版（section = llm_local / llm_cloud / asr）：从设置页三个模型行或工序单阻塞按钮
 *   带分区进入，隐藏两排选择器与汇总条，直接呈现对应内容。
 *
 * 引擎选中态走有效引擎（[com.zola.videonotes.data.llm.LlmEngineResolver]）：
 * 存储值为云端但未配齐时回落本地；完整版点「云端 API」会存存储值并跳云端直达页补齐配置。
 *
 * - LLM 逻辑复用 [LlmSettingsViewModel]（引擎二选一、云端配置、本地下载）；
 * - ASR 逻辑复用 [ModelManagementViewModel]（模型列表、下载；移动网络大文件首次确认）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelManagementScreen(
    section: String = Routes.SECTION_FULL,
    onBackClick: () -> Unit,
    onOpenSection: (String) -> Unit = {},
    llmViewModel: LlmSettingsViewModel = hiltViewModel(),
    asrViewModel: ModelManagementViewModel = hiltViewModel(),
    localVm: LocalModelsViewModel = hiltViewModel(),
) {
    val llmState by llmViewModel.uiState.collectAsStateWithLifecycle()
    val effectiveEngine by llmViewModel.effectiveEngineType.collectAsStateWithLifecycle()
    val localDlStates by llmViewModel.localDlStates.collectAsStateWithLifecycle()
    val asrItems by asrViewModel.items.collectAsStateWithLifecycle()
    val asrBrowse by asrViewModel.browseList.collectAsStateWithLifecycle()

    // 当前生效条两行名：语言模型（本地选中）与识别模型（选中）；未选为 null，条显「未选择」
    val llmSelectedId by localVm.selectedId.collectAsStateWithLifecycle()
    val llmSelectedEntry = llmSelectedId?.let { localVm.resolveEntry(it) }
    val llmCurrentName = llmSelectedEntry?.displayName
    val llmCurrentUndownloaded = llmSelectedId != null &&
        localDlStates[llmSelectedId]?.status != LocalModelDownloadManager.DlStatus.COMPLETED
    val asrSelectedItem = asrItems.firstOrNull { it.selected }
    val asrCurrentName = asrSelectedItem?.model?.displayName
    val asrCurrentUndownloaded = asrSelectedItem != null &&
        asrSelectedItem.state.status != ModelDownloadManager.DlStatus.COMPLETED
    // 云端生效时本地模型页单选框整组禁用（直达绕过 bug 根治）
    val cloudDisabled = effectiveEngine == LlmEngineType.CLOUD

    val directMode = section != Routes.SECTION_FULL
    // 完整版的主 Tab 初值按入口分区（直达 ASR 时落在语音识别，直达模式下不渲染此 Tab）
    var mainTab by remember(section) {
        mutableIntStateOf(if (section == Routes.SECTION_ASR) 1 else 0)
    }
    var passwordVisible by remember { mutableStateOf(false) }

    // 管理态（模型配置化）：整屏一级状态，两 Tab 共用；云端配置页不显示管理入口。
    var manageMode by remember { mutableStateOf(false) }
    val showManage = when (section) {
        Routes.SECTION_LLM_CLOUD -> false
        Routes.SECTION_ASR, Routes.SECTION_LLM_LOCAL -> true
        else -> if (mainTab == 0) effectiveEngine == LlmEngineType.LOCAL else true
    }
    // 新增／编辑／移除／推荐池的覆盖层状态（两 Tab 共用，分支按条目类别走对应 ViewModel）。
    var poolKind by remember { mutableStateOf<CatalogKind?>(null) }
    var formState by remember { mutableStateOf<ModelFormState?>(null) }
    var removeTarget by remember { mutableStateOf<CatalogEntry?>(null) }
    // 表单标识唯一性：全局生效 id（两类别 + 自定义 − 已移除），编辑时不判重。
    val userVersion by UserCatalog.version.collectAsStateWithLifecycle()
    val existingIds = remember(userVersion) {
        val state = UserCatalog.snapshot()
        (CatalogStore.all().map { it.id } + state.custom.map { it.id } - state.removedIds).toSet()
    }

    // 存储概览：已下载总量 + 模型个数（LLM + ASR）
    val llmDownloaded = localDlStates.values
        .filter { it.status == LocalModelDownloadManager.DlStatus.COMPLETED }
    val asrDownloaded = asrItems
        .filter { it.state.status == ModelDownloadManager.DlStatus.COMPLETED }
    val totalBytes = llmDownloaded.sumOf { it.downloadedBytes } +
        asrDownloaded.sumOf { it.state.downloadedBytes }
    val modelCount = llmDownloaded.size + asrDownloaded.size

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("模型管理") },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回",
                        )
                    }
                },
                actions = {
                    if (showManage) {
                        TextButton(onClick = { manageMode = !manageMode }) {
                            Text(if (manageMode) "完成" else "管理")
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            if (!directMode) {
            // 存储概览条（仅完整版）
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = VideoNotesTokens.Space16),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(VideoNotesTokens.Space16),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "已下载 ${formatBytes(totalBytes)}",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                    Text(
                        text = "共 $modelCount 个模型",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }

            Spacer(modifier = Modifier.height(VideoNotesTokens.Space16))

            // 主 Tab：大语言模型 / 语音识别
            PaperSegmentedRow(
                labels = listOf("大语言模型", "语音识别"),
                selectedIndex = mainTab,
                onSelect = { mainTab = it },
                spread = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = VideoNotesTokens.Space16),
            )

            Spacer(modifier = Modifier.height(VideoNotesTokens.Space16))
            }

            when {
                // 直达：语音识别模型列表
                section == Routes.SECTION_ASR -> AsrTabContent(
                    browseList = asrBrowse,
                    viewModel = asrViewModel,
                    localVm = localVm,
                    llmCurrentName = llmCurrentName,
                    llmCurrentUndownloaded = llmCurrentUndownloaded,
                    asrCurrentName = asrCurrentName,
                    asrCurrentUndownloaded = asrCurrentUndownloaded,
                    manageMode = manageMode,
                    onEditEntry = { formState = ModelFormState.Edit(it) },
                    onRemoveEntry = { removeTarget = it },
                    onAddClick = { poolKind = CatalogKind.ASR },
                )
                // 直达：云端 API 配置表单
                section == Routes.SECTION_LLM_CLOUD -> CloudConfigSection(
                    state = llmState,
                    viewModel = llmViewModel,
                    passwordVisible = passwordVisible,
                    onPasswordVisibleChange = { passwordVisible = it },
                )
                // 直达：本地模型（一条列表，WP8）
                section == Routes.SECTION_LLM_LOCAL -> LocalModelsContent(
                    vm = localVm,
                    cloudDisabled = cloudDisabled,
                    llmCurrentName = llmCurrentName,
                    llmCurrentUndownloaded = llmCurrentUndownloaded,
                    asrCurrentName = asrCurrentName,
                    asrCurrentUndownloaded = asrCurrentUndownloaded,
                    manageMode = manageMode,
                    onEditEntry = { formState = ModelFormState.Edit(it) },
                    onRemoveEntry = { removeTarget = it },
                    onAddClick = { poolKind = CatalogKind.LLM },
                )
                // 完整版：按主 Tab 呈现
                mainTab == 0 -> LlmTabContent(
                    llmState = llmState,
                    localDlStates = localDlStates,
                    viewModel = llmViewModel,
                    localVm = localVm,
                    effectiveEngine = effectiveEngine,
                    onOpenCloudConfig = { onOpenSection(Routes.SECTION_LLM_CLOUD) },
                    passwordVisible = passwordVisible,
                    onPasswordVisibleChange = { passwordVisible = it },
                    llmCurrentName = llmCurrentName,
                    llmCurrentUndownloaded = llmCurrentUndownloaded,
                    asrCurrentName = asrCurrentName,
                    asrCurrentUndownloaded = asrCurrentUndownloaded,
                    cloudDisabled = cloudDisabled,
                    manageMode = manageMode,
                    onEditEntry = { formState = ModelFormState.Edit(it) },
                    onRemoveEntry = { removeTarget = it },
                    onAddClick = { poolKind = CatalogKind.LLM },
                )
                else -> AsrTabContent(
                    browseList = asrBrowse,
                    viewModel = asrViewModel,
                    localVm = localVm,
                    llmCurrentName = llmCurrentName,
                    llmCurrentUndownloaded = llmCurrentUndownloaded,
                    asrCurrentName = asrCurrentName,
                    asrCurrentUndownloaded = asrCurrentUndownloaded,
                    manageMode = manageMode,
                    onEditEntry = { formState = ModelFormState.Edit(it) },
                    onRemoveEntry = { removeTarget = it },
                    onAddClick = { poolKind = CatalogKind.ASR },
                )
            }

            Spacer(modifier = Modifier.height(VideoNotesTokens.Space24))
        }
    }

    // 覆盖层：推荐池／表单／移除确认（两 Tab 共用，分支按条目类别）。
    poolKind?.let { kind ->
        ModelPoolSheet(
            kind = kind,
            onDismiss = { poolKind = null },
            onAddPoolEntry = { id ->
                if (kind == CatalogKind.LLM) {
                    localVm.restorePoolEntry(id)
                } else {
                    asrViewModel.restorePoolEntry(id)
                }
                poolKind = null
            },
            onCustomFill = {
                poolKind = null
                formState = ModelFormState.Add(kind)
            },
        )
    }
    formState?.let { state ->
        ModelFormScreen(
            formState = state,
            existingIds = existingIds,
            onBack = { formState = null },
            onSave = { entry ->
                when (state) {
                    is ModelFormState.Add -> {
                        if (state.kind == CatalogKind.LLM) {
                            localVm.addCustomEntry(entry)
                        } else {
                            asrViewModel.addCustomEntry(entry)
                        }
                    }
                    is ModelFormState.Edit -> {
                        if (state.entry.kind == CatalogKind.LLM) {
                            localVm.saveEditedEntry(state.entry, entry)
                        } else {
                            asrViewModel.saveEditedEntry(state.entry, entry)
                        }
                    }
                }
                formState = null
            },
        )
    }
    removeTarget?.let { entry ->
        ModelRemoveDialog(
            entry = entry,
            onDismiss = { removeTarget = null },
            onConfirm = { deleteFiles ->
                if (entry.kind == CatalogKind.LLM) {
                    localVm.removeEntry(entry, deleteFiles)
                } else {
                    asrViewModel.removeEntry(entry, deleteFiles)
                }
                removeTarget = null
            },
        )
    }
}

/**
 * 大语言模型 Tab（完整版）：引擎分段 + 对应内容。
 * 分段顺序与设置页一致（本地在前），选中态与内容分支都走有效引擎；
 * 点「云端 API」写存储值后跳云端直达页补齐配置。
 */
@Composable
private fun LlmTabContent(
    llmState: LlmSettingsUiState,
    localDlStates: Map<String, LocalModelDownloadManager.ModelDlState>,
    viewModel: LlmSettingsViewModel,
    localVm: LocalModelsViewModel,
    effectiveEngine: LlmEngineType,
    onOpenCloudConfig: () -> Unit,
    passwordVisible: Boolean,
    onPasswordVisibleChange: (Boolean) -> Unit,
    llmCurrentName: String?,
    llmCurrentUndownloaded: Boolean,
    asrCurrentName: String?,
    asrCurrentUndownloaded: Boolean,
    cloudDisabled: Boolean,
    manageMode: Boolean = false,
    onEditEntry: (CatalogEntry) -> Unit = {},
    onRemoveEntry: (CatalogEntry) -> Unit = {},
    onAddClick: () -> Unit = {},
) {
    Column {
        PaperSegmentedRow(
            labels = listOf("📱 本地模型", "☁️ 云端 API"),
            selectedIndex = if (effectiveEngine == LlmEngineType.LOCAL) 0 else 1,
            onSelect = { index ->
                if (index == 0) {
                    viewModel.onEngineTypeChange(LlmEngineType.LOCAL)
                } else {
                    viewModel.onEngineTypeChange(LlmEngineType.CLOUD)
                    onOpenCloudConfig()
                }
            },
            spread = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = VideoNotesTokens.Space16),
        )

        Spacer(modifier = Modifier.height(VideoNotesTokens.Space16))

        if (effectiveEngine == LlmEngineType.LOCAL) {
            LocalModelsContent(
                vm = localVm,
                cloudDisabled = cloudDisabled,
                llmCurrentName = llmCurrentName,
                llmCurrentUndownloaded = llmCurrentUndownloaded,
                asrCurrentName = asrCurrentName,
                asrCurrentUndownloaded = asrCurrentUndownloaded,
                manageMode = manageMode,
                onEditEntry = onEditEntry,
                onRemoveEntry = onRemoveEntry,
                onAddClick = onAddClick,
            )
        } else {
            CloudConfigSection(
                state = llmState,
                viewModel = viewModel,
                passwordVisible = passwordVisible,
                onPasswordVisibleChange = onPasswordVisibleChange,
            )
        }
    }
}

/** 语音识别 Tab（合并包新人友好；第二轮修订：排序功能下架）：生效条恒显 → 用途筛选 → 单列。 */
@Composable
private fun AsrTabContent(
    browseList: ModelManagementViewModel.AsrBrowseList,
    viewModel: ModelManagementViewModel,
    localVm: LocalModelsViewModel,
    llmCurrentName: String?,
    llmCurrentUndownloaded: Boolean,
    asrCurrentName: String?,
    asrCurrentUndownloaded: Boolean,
    manageMode: Boolean = false,
    onEditEntry: (CatalogEntry) -> Unit = {},
    onRemoveEntry: (CatalogEntry) -> Unit = {},
    onAddClick: () -> Unit = {},
) {
    val context = LocalContext.current
    val useCaseFilter by viewModel.useCaseFilter.collectAsStateWithLifecycle()
    val pendingMobile by viewModel.pendingMobileConfirm.collectAsStateWithLifecycle()
    var showTermDialog by remember { mutableStateOf(false) }

    // 保存/恢复后定位：单列已无分组，直接滚动到该行，消费后清空。
    val highlightId by viewModel.highlightId.collectAsStateWithLifecycle()
    val bringIntoViewRequester = remember { BringIntoViewRequester() }
    LaunchedEffect(highlightId, browseList) {
        val target = highlightId ?: return@LaunchedEffect
        if (target in browseList.ordered.map { it.model.id }) {
            bringIntoViewRequester.bringIntoView()
            viewModel.consumeHighlight()
        }
    }

    // 一次性提示（删除回落说明）以 Toast 呈现
    LaunchedEffect(Unit) {
        viewModel.userMessage.collect { msg ->
            Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
        }
    }

    if (showTermDialog) {
        TermInfoDialog(
            title = "术语说明",
            body = TermNotes.ALL,
            onDismiss = { showTermDialog = false },
        )
    }

    Column(
        modifier = Modifier.padding(horizontal = VideoNotesTokens.Space16),
    ) {
        Text(
            text = "无字幕视频会自动用端侧语音识别兜底转写，全程不联网（模型下载除外）。" +
                "模型保存在应用私有目录，卸载 App 时自动清除。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(VideoNotesTokens.Space12))
        // 页顶「当前生效」条（恒显，单行本页模型，与本地模型页同款）
        CurrentEffectBar(
            currentName = asrCurrentName,
            currentUndownloaded = asrCurrentUndownloaded,
            cloudDisabled = false,
        )
        // 排序功能已下架（第二轮修订）：列表固定按推荐序排列，不再提供排序档切换。
        UseCaseChips(
            selected = useCaseFilter,
            onSelect = { viewModel.setUseCaseFilter(it) },
        )
        Spacer(modifier = Modifier.height(VideoNotesTokens.Space8))
        // 单列（无分组）：按推荐→已下载→目录序排好。
        browseList.ordered.forEach { item ->
            AsrModelRow(
                item = item,
                onDownload = { viewModel.download(item.model.id) },
                onPause = { viewModel.pause(item.model.id) },
                onDelete = { viewModel.delete(item.model.id) },
                onSelect = { viewModel.select(item.model.id) },
                manageMode = manageMode,
                onEdit = { onEditEntry(item.entry) },
                onRemove = { onRemoveEntry(item.entry) },
                rowModifier = if (item.model.id == highlightId) {
                    Modifier.bringIntoViewRequester(bringIntoViewRequester)
                } else {
                    Modifier
                },
            )
        }
        if (browseList.ordered.isEmpty()) {
            if (useCaseFilter == null) {
                // 空态：列表里一个模型都没有（全被移除了），去添加走新增流。
                Text(
                    text = "还没有模型",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(vertical = VideoNotesTokens.Space8),
                )
                Text(
                    text = "从推荐池添加精选模型，或自己填写一个新模型。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(VideoNotesTokens.Space12))
                Button(onClick = onAddClick) { Text("去添加") }
            } else {
                Text(
                    text = "没有符合该用途的模型",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = VideoNotesTokens.Space8),
                )
            }
        } else {
            OutlinedButton(
                onClick = onAddClick,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("＋ 新增模型")
            }
        }
        Spacer(modifier = Modifier.height(VideoNotesTokens.Space8))
        Text(
            text = "移动网络下载大文件首次会确认。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = "注：除中文主力档外，其余模型的时间戳落点尚未逐项真机验证。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(onClick = { showTermDialog = true }) {
            Text("术语说明 ⓘ")
        }
    }

    // WP4：移动网络大文件下载的一次性确认
    pendingMobile.firstOrNull()?.let { id ->
        val item = browseList.ordered
            .firstOrNull { it.model.id == id }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { viewModel.dismissMobile(id) },
            title = { Text("使用移动网络下载？") },
            text = {
                Text(
                    text = "当前不是 Wi-Fi 网络。${item?.model?.displayName ?: ""}约 " +
                        "${formatBytes(item?.model?.totalBytes ?: 0)}，确认使用移动网络下载吗？" +
                        "确认后本次启动内不再询问。",
                )
            },
            confirmButton = {
                Button(onClick = { viewModel.confirmMobile(id) }) { Text("确认下载") }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissMobile(id) }) { Text("取消") }
            },
        )
    }
}

/** 云端 API 配置区（自 SettingsScreen 迁移）。 */
@Composable
private fun CloudConfigSection(
    state: LlmSettingsUiState,
    viewModel: LlmSettingsViewModel,
    passwordVisible: Boolean,
    onPasswordVisibleChange: (Boolean) -> Unit,
) {
    Column(modifier = Modifier.padding(horizontal = VideoNotesTokens.Space16)) {
        OutlinedTextField(
            value = state.baseUrl,
            onValueChange = viewModel::onBaseUrlChange,
            label = { Text("接口地址 base_url") },
            placeholder = { Text(LlmPresets.DEFAULT_BASE_URL) },
            singleLine = true,
            isError = state.baseUrlError != null,
            modifier = Modifier.fillMaxWidth(),
        )
        state.baseUrlError?.let { err ->
            Text(
                text = err,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        Spacer(modifier = Modifier.height(VideoNotesTokens.Space8))
        Row(horizontalArrangement = Arrangement.spacedBy(VideoNotesTokens.Space8)) {
            LlmPresets.ALL.forEach { preset ->
                AssistChip(
                    onClick = { viewModel.onPresetClick(preset) },
                    label = { Text(preset.name) },
                )
            }
        }

        Spacer(modifier = Modifier.height(VideoNotesTokens.Space16))
        OutlinedTextField(
            value = state.apiKey,
            onValueChange = viewModel::onApiKeyChange,
            label = { Text("API Key") },
            placeholder = { Text("sk-…") },
            singleLine = true,
            visualTransformation = if (passwordVisible) {
                VisualTransformation.None
            } else {
                PasswordVisualTransformation()
            },
            trailingIcon = {
                androidx.compose.material3.IconButton(onClick = { onPasswordVisibleChange(!passwordVisible) }) {
                    Icon(
                        imageVector = if (passwordVisible) {
                            Icons.Filled.VisibilityOff
                        } else {
                            Icons.Filled.Visibility
                        },
                        contentDescription = if (passwordVisible) "隐藏" else "显示",
                    )
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(modifier = Modifier.height(VideoNotesTokens.Space16))
        OutlinedTextField(
            value = state.model,
            onValueChange = viewModel::onModelChange,
            label = { Text("模型") },
            placeholder = { Text(LlmPresets.MODEL_HINT) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(modifier = Modifier.height(VideoNotesTokens.Space16))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(
                onClick = viewModel::testConnection,
                enabled = !state.testing,
            ) {
                Text("测试连接")
            }
            if (state.testing) {
                Spacer(modifier = Modifier.width(VideoNotesTokens.Space12))
                CircularProgressIndicator(modifier = Modifier.size(VideoNotesTokens.Space24))
            }
            state.testResult?.let { result ->
                Spacer(modifier = Modifier.width(VideoNotesTokens.Space12))
                Text(
                    text = result,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (state.testSuccess) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                )
            }
        }

        Spacer(modifier = Modifier.height(VideoNotesTokens.Space16))
        Text(
            text = "隐私告知：笔记文稿仅在你点击生成笔记时发送至上述 API，其余数据均保存在本机。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 语音识别模型发丝线行（合并包新人友好：中文主名单行 + 标签仅推荐/已下载；单选框未下载也可勾选）。 */
@Composable
private fun AsrModelRow(
    item: ModelManagementViewModel.ModelItemUi,
    onDownload: () -> Unit,
    onPause: () -> Unit,
    onDelete: () -> Unit,
    onSelect: () -> Unit,
    manageMode: Boolean = false,
    onEdit: () -> Unit = {},
    onRemove: () -> Unit = {},
    rowModifier: Modifier = Modifier,
) {
    val state = item.state
    val completed = state.status == ModelDownloadManager.DlStatus.COMPLETED
    val catalogEntry = item.entry
    var showDetail by remember { mutableStateOf(false) }

    if (showDetail) {
        // ⓘ 弹窗正文：副行余项（体积 · 时间戳说明）＋描述；标题用英文原名
        // （无英文原名回落显示中文名），行上已不再显示副行。
        val detailParts = mutableListOf<String>()
        detailParts += formatBytes(item.model.totalBytes)
        asrTimestampNote(item.model.id)?.let { detailParts += it }
        val detailMeta = detailParts.joinToString(" · ")
        val detailBody = if (item.model.description.isNotBlank()) {
            "$detailMeta\n${item.model.description}"
        } else {
            detailMeta
        }
        val detailTitle =
            catalogEntry?.originalName?.takeIf { it.isNotBlank() } ?: item.model.displayName
        TermInfoDialog(
            title = detailTitle,
            body = detailBody,
            onDismiss = { showDetail = false },
        )
    }

    Column(modifier = rowModifier.fillMaxWidth()) {
        androidx.compose.material3.HorizontalDivider(
            color = MaterialTheme.colorScheme.outlineVariant,
        )
        Spacer(modifier = Modifier.height(VideoNotesTokens.Space12))
        Row(verticalAlignment = Alignment.CenterVertically) {
            // 行首贴左单选框：唯一的选择表达；未下载行也可勾选（合并包），选后不自动下载
            TightRadio(
                selected = item.selected,
                onClick = onSelect,
                enabled = true,
            )
            Spacer(modifier = Modifier.width(ModelRowRadioNameGap))
            // 名称 + 徽标同行：名字固定 titleMedium 单行全显，推荐 / 已下载 ✓ 紧贴名字后面；
            // 容器占剩余宽，右侧 ⓘ 与动作由此贴右。
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f),
            ) {
                Text(
                    text = item.model.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Clip,
                    modifier = Modifier.weight(1f, fill = false),
                )
                // 标签只保留「推荐」与绿色「已下载 ✓」（第二轮修订：实测徽标全部下架）
                if (catalogEntry?.recommended == true) {
                    Spacer(modifier = Modifier.width(VideoNotesTokens.Space8))
                    RowMiniTag("推荐")
                }
                if (completed) {
                    if (catalogEntry?.recommended == true) {
                        Spacer(modifier = Modifier.width(VideoNotesTokens.Space8))
                    }
                    Text(
                        text = "已下载 ✓",
                        style = MaterialTheme.typography.labelMedium,
                        color = ColorDownloaded,
                    )
                }
            }
            // 每行 ⓘ：副行余项（体积 · 时间戳说明）与描述收进弹窗。
            TermInfoIcon(onClick = { showDetail = true })
            // 管理态：右侧动作位切换为编辑/移除；正常态保持下载/删除两态。
            // 下载、删除均为 153ac3d 的裸 Text（bodyMedium），32dp 动作槽贴右。
            Box(
                modifier = Modifier.width(
                    if (manageMode) ModelRowManageActionSlotWidth else ModelRowActionSlotWidth,
                ),
                contentAlignment = Alignment.CenterEnd,
            ) {
                if (manageMode) {
                    // 换行修复方案一：裸文字（与正常态下载/删除同款），不用 TextButton
                    // 的内边距，80dp 槽（68dp 内容 + 12dp 余量）刚好；强制单行兜底，字体放大也不折行。
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            text = "编辑",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Clip,
                            modifier = Modifier.clickable { onEdit() },
                        )
                        Text(
                            text = "移除",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Clip,
                            modifier = Modifier.clickable { onRemove() },
                        )
                    }
                } else {
                    // 右侧动作位严格只有两态：未下载「下载」、已下载「删除」。
                    // 下载中/已暂停/失败时右侧留空，状态与操作留在下方次级区。
                    when {
                        completed -> Text(
                            text = "删除",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.clickable { onDelete() },
                        )
                        state.status == ModelDownloadManager.DlStatus.NOT_DOWNLOADED ->
                            Text(
                                text = "下载",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.clickable { onDownload() },
                            )
                    }
                }
            }
        }
        Column(modifier = Modifier.padding(start = ModelRowSecondaryIndent)) {
            when (state.status) {
                ModelDownloadManager.DlStatus.DOWNLOADING -> {
                    Spacer(modifier = Modifier.height(VideoNotesTokens.Space8))
                    LinearProgressIndicator(
                        progress = { state.progress },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "下载中 ${(state.progress * 100).toInt()}% · " +
                                "${formatBytes(state.downloadedBytes)} / ${formatBytes(state.totalBytes)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        // 管理态只管条目，不管下载：暂停回正常态操作。
                        if (!manageMode) {
                            TextButton(onClick = onPause) { Text("暂停") }
                        }
                    }
                }
                ModelDownloadManager.DlStatus.PAUSED -> {
                    Spacer(modifier = Modifier.height(VideoNotesTokens.Space4))
                    Text(
                        text = "已暂停 ${(state.progress * 100).toInt()}%，可断点续传",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (!manageMode) {
                        Row {
                            TextButton(onClick = onDownload) { Text("继续") }
                            TextButton(onClick = onDelete) { Text("删除") }
                        }
                    }
                }
                ModelDownloadManager.DlStatus.FAILED -> {
                    Spacer(modifier = Modifier.height(VideoNotesTokens.Space4))
                    Text(
                        text = state.error ?: "下载失败",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    if (!manageMode) {
                        Row {
                            TextButton(onClick = onDownload) { Text("重试") }
                            TextButton(onClick = onDelete) { Text("删除") }
                        }
                    }
                }
                ModelDownloadManager.DlStatus.COMPLETED -> {
                    Text(
                        text = "已下载 ${formatBytes(state.downloadedBytes)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                ModelDownloadManager.DlStatus.NOT_DOWNLOADED -> {
                    state.error?.let { err ->
                        Spacer(modifier = Modifier.height(VideoNotesTokens.Space4))
                        Text(
                            text = err,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(VideoNotesTokens.Space12))
    }
}

