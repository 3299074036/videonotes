package com.zola.videonotes.ui.models

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zola.videonotes.data.asr.ModelDownloadManager
import com.zola.videonotes.data.llm.LlmEngineType
import com.zola.videonotes.data.llm.LlmPresets
import com.zola.videonotes.data.llm.local.LocalLlmModels
import com.zola.videonotes.data.llm.local.LocalModelDownloadManager
import com.zola.videonotes.domain.asr.formatBytes
import com.zola.videonotes.ui.settings.LlmSettingsUiState
import com.zola.videonotes.ui.settings.LlmSettingsViewModel
import com.zola.videonotes.ui.theme.VideoNotesTokens

/**
 * 统一模型管理页（v2）：
 * 一个页面管所有模型——顶部 Tab 切「大语言模型 / 语音识别」，
 * 大语言模型 Tab 内再分「云端 API / 本地模型」。
 * 下载/删除/进度条/暂停全在这里；设置页只留一个入口。
 *
 * - LLM 逻辑复用 [LlmSettingsViewModel]（引擎二选一、云端配置、本地下载）；
 * - ASR 逻辑复用 [ModelManagementViewModel]（模型列表、下载；固定仅 Wi-Fi 下载）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelManagementScreen(
    onBackClick: () -> Unit,
    llmViewModel: LlmSettingsViewModel = hiltViewModel(),
    asrViewModel: ModelManagementViewModel = hiltViewModel(),
) {
    val llmState by llmViewModel.uiState.collectAsStateWithLifecycle()
    val localDlStates by llmViewModel.localDlStates.collectAsStateWithLifecycle()
    val asrItems by asrViewModel.items.collectAsStateWithLifecycle()

    var mainTab by remember { mutableIntStateOf(0) } // 0=大语言模型，1=语音识别
    var passwordVisible by remember { mutableStateOf(false) }

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
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            // 存储概览条
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
            SingleChoiceSegmentedButtonRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = VideoNotesTokens.Space16),
            ) {
                SegmentedButton(
                    selected = mainTab == 0,
                    onClick = { mainTab = 0 },
                    shape = SegmentedButtonDefaults.itemShape(0, 2),
                    label = { Text("大语言模型") },
                )
                SegmentedButton(
                    selected = mainTab == 1,
                    onClick = { mainTab = 1 },
                    shape = SegmentedButtonDefaults.itemShape(1, 2),
                    label = { Text("语音识别") },
                )
            }

            Spacer(modifier = Modifier.height(VideoNotesTokens.Space16))

            if (mainTab == 0) {
                LlmTabContent(
                    llmState = llmState,
                    localDlStates = localDlStates,
                    viewModel = llmViewModel,
                    passwordVisible = passwordVisible,
                    onPasswordVisibleChange = { passwordVisible = it },
                )
            } else {
                AsrTabContent(
                    items = asrItems,
                    viewModel = asrViewModel,
                )
            }

            Spacer(modifier = Modifier.height(VideoNotesTokens.Space24))
        }
    }
}

/** 大语言模型 Tab：云端/本地二选一 + 对应内容。 */
@Composable
private fun LlmTabContent(
    llmState: LlmSettingsUiState,
    localDlStates: Map<String, LocalModelDownloadManager.ModelDlState>,
    viewModel: LlmSettingsViewModel,
    passwordVisible: Boolean,
    onPasswordVisibleChange: (Boolean) -> Unit,
) {
    Column {
        SingleChoiceSegmentedButtonRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = VideoNotesTokens.Space16),
        ) {
            SegmentedButton(
                selected = llmState.engineType == LlmEngineType.CLOUD,
                onClick = { viewModel.onEngineTypeChange(LlmEngineType.CLOUD) },
                shape = SegmentedButtonDefaults.itemShape(0, 2),
                label = { Text("☁️ 云端 API") },
            )
            SegmentedButton(
                selected = llmState.engineType == LlmEngineType.LOCAL,
                onClick = { viewModel.onEngineTypeChange(LlmEngineType.LOCAL) },
                shape = SegmentedButtonDefaults.itemShape(1, 2),
                label = { Text("📱 本地模型") },
            )
        }

        Spacer(modifier = Modifier.height(VideoNotesTokens.Space16))

        if (llmState.engineType == LlmEngineType.LOCAL) {
            // 本地模式提示条
            val currentModel = LocalLlmModels.ALL
                .firstOrNull { it.id == llmState.localModelId }
            val downloaded = currentModel?.let { m ->
                localDlStates[m.id]?.status ==
                    LocalModelDownloadManager.DlStatus.COMPLETED
            } ?: false
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = VideoNotesTokens.Space16),
            ) {
                Text(
                    text = buildString {
                        if (currentModel != null) {
                            append("当前：${currentModel.displayName}")
                            append(if (downloaded) "（已下载）" else "（未下载）")
                        } else {
                            append("本地模式，断网可用")
                        }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.padding(VideoNotesTokens.Space12),
                )
            }
            Spacer(modifier = Modifier.height(VideoNotesTokens.Space12))
            LocalModelSection(
                selectedId = llmState.localModelId,
                dlStates = localDlStates,
                onSelect = viewModel::onLocalModelSelect,
                onDownload = viewModel::onLocalDownload,
                onPause = viewModel::onLocalPause,
                onDelete = viewModel::onLocalDelete,
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

/** 语音识别 Tab：模型卡片（固定仅 Wi-Fi 下载）。 */
@Composable
private fun AsrTabContent(
    items: List<ModelManagementViewModel.ModelItemUi>,
    viewModel: ModelManagementViewModel,
) {
    Column(
        modifier = Modifier.padding(horizontal = VideoNotesTokens.Space16),
        verticalArrangement = Arrangement.spacedBy(VideoNotesTokens.Space12),
    ) {
        Text(
            text = "无字幕视频会自动用端侧语音识别兜底转写，全程不联网（模型下载除外）。" +
                "模型保存在应用私有目录，卸载 App 时自动清除。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        items.forEach { item ->
            AsrModelCard(
                item = item,
                onDownload = { viewModel.download(item.model.id) },
                onPause = { viewModel.pause(item.model.id) },
                onDelete = { viewModel.delete(item.model.id) },
                onSelect = { viewModel.select(item.model.id) },
            )
        }
        Text(
            text = "模型仅支持 Wi-Fi 下载（含本地大语言模型）。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
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
            modifier = Modifier.fillMaxWidth(),
        )
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

/** 本地模型区：模型卡片（下载/暂停/删除/选中）+ 说明（自 SettingsScreen 迁移）。 */
@Composable
private fun LocalModelSection(
    selectedId: String,
    dlStates: Map<String, LocalModelDownloadManager.ModelDlState>,
    onSelect: (String) -> Unit,
    onDownload: (String) -> Unit,
    onPause: (String) -> Unit,
    onDelete: (String) -> Unit,
) {
    Column(
        modifier = Modifier.padding(horizontal = VideoNotesTokens.Space16),
        verticalArrangement = Arrangement.spacedBy(VideoNotesTokens.Space12),
    ) {
        Text(
            text = "离线生成，不上传数据。仅支持 Wi-Fi 下载。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = "注意：本地模式暂不生成知识地图（mermaid 图），以列表形式呈现。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        LocalLlmModels.ALL.forEach { model ->
            val dlState = dlStates[model.id]
                ?: LocalModelDownloadManager.ModelDlState(
                    modelId = model.id,
                    status = LocalModelDownloadManager.DlStatus.NOT_DOWNLOADED,
                    downloadedBytes = 0,
                    totalBytes = model.sizeBytes,
                )
            LocalModelCard(
                model = model,
                selected = selectedId == model.id,
                dlState = dlState,
                onSelect = { onSelect(model.id) },
                onDownload = { onDownload(model.id) },
                onPause = { onPause(model.id) },
                onDelete = { onDelete(model.id) },
            )
        }
    }
}

@Composable
private fun LocalModelCard(
    model: com.zola.videonotes.data.llm.local.LocalLlmModel,
    selected: Boolean,
    dlState: LocalModelDownloadManager.ModelDlState,
    onSelect: () -> Unit,
    onDownload: () -> Unit,
    onPause: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(VideoNotesTokens.CornerLarge),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(modifier = Modifier.padding(VideoNotesTokens.Space16)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(
                    selected = selected,
                    onClick = onSelect,
                )
                Spacer(modifier = Modifier.width(VideoNotesTokens.Space8))
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = model.displayName,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        if (dlState.status == LocalModelDownloadManager.DlStatus.COMPLETED) {
                            Spacer(modifier = Modifier.width(VideoNotesTokens.Space8))
                            Text(
                                text = "已下载 ✓",
                                style = MaterialTheme.typography.labelMedium,
                                color = ColorDownloaded,
                            )
                        }
                    }
                    Text(
                        text = "约 ${formatBytes(model.sizeBytes)}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            Spacer(modifier = Modifier.height(VideoNotesTokens.Space4))
            Text(
                text = model.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = VideoNotesTokens.Space48),
            )
            Spacer(modifier = Modifier.height(VideoNotesTokens.Space12))

            when (dlState.status) {
                LocalModelDownloadManager.DlStatus.DOWNLOADING -> {
                    LinearProgressIndicator(
                        progress = { dlState.progress },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(modifier = Modifier.height(VideoNotesTokens.Space8))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "下载中 ${(dlState.progress * 100).toInt()}% · " +
                                "${formatBytes(dlState.downloadedBytes)} / ${formatBytes(dlState.totalBytes)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        TextButton(onClick = onPause) {
                            Text("暂停")
                        }
                    }
                }
                LocalModelDownloadManager.DlStatus.PAUSED -> {
                    Text(
                        text = "已暂停 ${(dlState.progress * 100).toInt()}%，可断点续传",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(VideoNotesTokens.Space8))
                    Row(horizontalArrangement = Arrangement.spacedBy(VideoNotesTokens.Space8)) {
                        Button(onClick = onDownload) {
                            Text("继续")
                        }
                        TextButton(onClick = onDelete) {
                            Text("删除")
                        }
                    }
                }
                LocalModelDownloadManager.DlStatus.FAILED -> {
                    Text(
                        text = dlState.error ?: "下载失败",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Spacer(modifier = Modifier.height(VideoNotesTokens.Space8))
                    Row(horizontalArrangement = Arrangement.spacedBy(VideoNotesTokens.Space8)) {
                        Button(onClick = onDownload) {
                            Text("重试")
                        }
                        TextButton(onClick = onDelete) {
                            Text("删除")
                        }
                    }
                }
                LocalModelDownloadManager.DlStatus.COMPLETED -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "已下载 ${formatBytes(dlState.downloadedBytes)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        TextButton(onClick = onDelete) {
                            Text("删除")
                        }
                    }
                }
                LocalModelDownloadManager.DlStatus.NOT_DOWNLOADED -> {
                    Button(onClick = onDownload) {
                        Text("下载")
                    }
                }
            }
        }
    }
}

/** 语音识别模型卡片（自旧版 ModelManagementScreen 迁移）。 */
@Composable
private fun AsrModelCard(
    item: ModelManagementViewModel.ModelItemUi,
    onDownload: () -> Unit,
    onPause: () -> Unit,
    onDelete: () -> Unit,
    onSelect: () -> Unit,
) {
    val state = item.state
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(VideoNotesTokens.CornerLarge),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(modifier = Modifier.padding(VideoNotesTokens.Space16)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(
                    selected = item.selected,
                    onClick = onSelect,
                )
                Spacer(modifier = Modifier.width(VideoNotesTokens.Space8))
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = item.model.displayName,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        if (state.status == ModelDownloadManager.DlStatus.COMPLETED) {
                            Spacer(modifier = Modifier.width(VideoNotesTokens.Space8))
                            Text(
                                text = "已下载 ✓",
                                style = MaterialTheme.typography.labelMedium,
                                color = ColorDownloaded,
                            )
                        }
                    }
                    Text(
                        text = "约 ${formatBytes(item.model.totalBytes)}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            Spacer(modifier = Modifier.height(VideoNotesTokens.Space4))
            Text(
                text = item.model.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = VideoNotesTokens.Space48),
            )
            Spacer(modifier = Modifier.height(VideoNotesTokens.Space12))

            when (state.status) {
                ModelDownloadManager.DlStatus.DOWNLOADING -> {
                    LinearProgressIndicator(
                        progress = { state.progress },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(modifier = Modifier.height(VideoNotesTokens.Space8))
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
                        TextButton(onClick = onPause) {
                            Text("暂停")
                        }
                    }
                }
                ModelDownloadManager.DlStatus.PAUSED -> {
                    Text(
                        text = "已暂停 ${(state.progress * 100).toInt()}%，可断点续传",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(VideoNotesTokens.Space8))
                    Row(horizontalArrangement = Arrangement.spacedBy(VideoNotesTokens.Space8)) {
                        Button(onClick = onDownload) {
                            Text("继续")
                        }
                        TextButton(onClick = onDelete) {
                            Text("删除")
                        }
                    }
                }
                ModelDownloadManager.DlStatus.FAILED -> {
                    Text(
                        text = state.error ?: "下载失败",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Spacer(modifier = Modifier.height(VideoNotesTokens.Space8))
                    Row(horizontalArrangement = Arrangement.spacedBy(VideoNotesTokens.Space8)) {
                        Button(onClick = onDownload) {
                            Text("重试")
                        }
                        TextButton(onClick = onDelete) {
                            Text("删除")
                        }
                    }
                }
                ModelDownloadManager.DlStatus.COMPLETED -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "已下载 ${formatBytes(state.downloadedBytes)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        TextButton(onClick = onDelete) {
                            Text("删除")
                        }
                    }
                }
                ModelDownloadManager.DlStatus.NOT_DOWNLOADED -> {
                    Button(onClick = onDownload) {
                        Text("下载")
                    }
                }
            }
        }
    }
}

private val ColorDownloaded = androidx.compose.ui.graphics.Color(0xFF1B7A3D)
