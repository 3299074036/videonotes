package com.zola.videonotes.ui.models

import android.widget.Toast
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zola.videonotes.data.catalog.CatalogEntry
import com.zola.videonotes.data.catalog.CatalogSource
import com.zola.videonotes.data.catalog.TemplateProbeState
import com.zola.videonotes.data.llm.local.LocalModelDownloadManager
import com.zola.videonotes.domain.asr.formatBytes
import com.zola.videonotes.ui.theme.VideoNotesTokens

/**
 * 本地模型页内容（国内名单定版，2026-10-24 §17 改判；2026-10-24 合并包新人友好；
 * 2026-10-24 第二轮修订：排序功能下架、实测功能下架、C7 下架）：
 * 当前生效条（恒显，单行本页模型）→ 用途筛选 →
 * 占用汇总 → 单列（无分组，按推荐→已下载→目录序排）。
 * 搜索框已下架（注记）；HF 动态层已下线，本页不再有联网加载区；
 * 已下载的存量动态条目仍以普通行呈现（可选用/删除）。行形态为纸面发丝线行，无圆角卡片。
 * 行只显示名称（单行，超长截断）；副行与描述收进每行 ⓘ 弹窗（标题为英文原名，
 * 无英文原名回落显示中文名），原第三方量化解释弹窗已删。
 *
 * 选择机制（合并包，推翻 §20 先下后选）：行首单选框是唯一的选择表达（单页单选），
 * 未下载行也可勾选（选后不自动下载，探针门顺延至下载完成时）；
 * 右侧动作位严格只有两态——未下载「下载」、已下载「删除」；不再有「设为使用」
 * 「切换使用」「使用中」等文字态。云端生效时（[cloudDisabled]）单选框整组禁用。
 * 行标签只保留「推荐」与绿色「已下载 ✓」；列表固定按推荐序排列。
 */
@Composable
fun LocalModelsContent(
    vm: LocalModelsViewModel,
    cloudDisabled: Boolean = false,
    llmCurrentName: String? = null,
    llmCurrentUndownloaded: Boolean = false,
    asrCurrentName: String? = null,
    asrCurrentUndownloaded: Boolean = false,
    /** 管理态：行右侧动作位切换为编辑/移除（下载/删除文件回正常态）。 */
    manageMode: Boolean = false,
    onEditEntry: (CatalogEntry) -> Unit = {},
    onRemoveEntry: (CatalogEntry) -> Unit = {},
    onAddClick: () -> Unit = {},
) {
    val context = LocalContext.current
    val browse by vm.browseList.collectAsStateWithLifecycle()
    val dlStates by vm.dlStates.collectAsStateWithLifecycle()
    val selectedId by vm.selectedId.collectAsStateWithLifecycle()
    val useCaseFilter by vm.useCaseFilter.collectAsStateWithLifecycle()
    val pendingMobile by vm.pendingMobileConfirm.collectAsStateWithLifecycle()
    var showTermDialog by remember { mutableStateOf(false) }

    // 保存/恢复后定位：单列已无分组，直接滚动到该行，消费后清空。
    val highlightId by vm.highlightId.collectAsStateWithLifecycle()
    val bringIntoViewRequester = remember { BringIntoViewRequester() }
    LaunchedEffect(highlightId, browse) {
        val target = highlightId ?: return@LaunchedEffect
        if (target in browse.ordered.map { it.id }) {
            bringIntoViewRequester.bringIntoView()
            vm.consumeHighlight()
        }
    }

    // 一次性提示（探针拦截原因、删除回落说明）以 Toast 呈现
    LaunchedEffect(Unit) {
        vm.userMessage.collect { msg ->
            Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
        }
    }

    if (showTermDialog) {
        TermInfoDialog(title = "术语说明", body = TermNotes.ALL) { showTermDialog = false }
    }

    Column(modifier = Modifier.padding(horizontal = VideoNotesTokens.Space16)) {
        // 页顶「当前生效」条（恒显，单行本页模型，未选显「未选择」）
        CurrentEffectBar(
            currentName = llmCurrentName,
            currentUndownloaded = llmCurrentUndownloaded,
            cloudDisabled = cloudDisabled,
        )

        // 排序功能已下架（第二轮修订）：列表固定按推荐序排列，不再提供排序档切换。
        // 用途筛选 chips（独立筛选维）
        UseCaseChips(
            selected = useCaseFilter,
            onSelect = { vm.setUseCaseFilter(it) },
        )
        // 占用汇总行
        Text(
            text = "已下载 ${browse.downloadedCount} 个 · 共占用 约 ${formatBytes(browse.downloadedBytes)}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = VideoNotesTokens.Space8),
        )
        // 单列（无分组）：按推荐→已下载→目录序排好。
        browse.ordered.forEach { entry ->
            CatalogEntryRow(
                vm, entry, dlStates, selectedId, cloudDisabled,
                manageMode = manageMode,
                onEdit = { onEditEntry(entry) },
                onRemove = { onRemoveEntry(entry) },
                rowModifier = if (entry.id == highlightId) {
                    Modifier.bringIntoViewRequester(bringIntoViewRequester)
                } else {
                    Modifier
                },
            )
        }
        if (browse.ordered.isEmpty()) {
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
        TextButton(onClick = { showTermDialog = true }) {
            Text("术语说明 ⓘ")
        }
    }

    // C7 已下架（第二轮修订：实测功能整体移除）：选未验证条目不再弹「使用未实测模型？」确认；
    // 模板探针门保留，仍在下载完成时执行（见 ViewModel 顺延探针门）。

    // WP4：移动网络大文件下载的一次性确认
    pendingMobile.firstOrNull()?.let { id ->
        val entry = vm.resolveEntry(id)
        AlertDialog(
            onDismissRequest = { vm.dismissMobile(id) },
            title = { Text("使用移动网络下载？") },
            text = {
                Text(
                    text = "当前不是 Wi-Fi 网络。${entry?.displayName ?: ""}约 " +
                        "${formatBytes(entry?.totalBytes ?: 0)}，确认使用移动网络下载吗？" +
                        "确认后本次启动内不再询问。",
                )
            },
            confirmButton = {
                Button(onClick = { vm.confirmMobile(id) }) { Text("确认下载") }
            },
            dismissButton = {
                TextButton(onClick = { vm.dismissMobile(id) }) { Text("取消") }
            },
        )
    }
}

/** 一条目录条目的发丝线行（精选/影子/已下载动态共用；合并包单选框选择机制）。 */
@Composable
private fun CatalogEntryRow(
    vm: LocalModelsViewModel,
    entry: CatalogEntry,
    dlStates: Map<String, LocalModelDownloadManager.ModelDlState>,
    selectedId: String?,
    cloudDisabled: Boolean = false,
    manageMode: Boolean = false,
    onEdit: () -> Unit = {},
    onRemove: () -> Unit = {},
    rowModifier: Modifier = Modifier,
) {
    val state = dlStates[entry.id] ?: LocalModelDownloadManager.ModelDlState(
        modelId = entry.id,
        status = LocalModelDownloadManager.DlStatus.NOT_DOWNLOADED,
        downloadedBytes = 0,
        totalBytes = entry.totalBytes,
    )
    val completed = state.status == LocalModelDownloadManager.DlStatus.COMPLETED
    val selected = selectedId == entry.id
    val probe = vm.probeStateOf(entry)
    val ramBlocked = !completed && vm.isRamBlocked(entry)
    var showDetail by remember { mutableStateOf(false) }

    if (showDetail) {
        // ⓘ 弹窗标题用英文原名（无英文原名回落显示中文名）。
        val detailTitle = entry.originalName?.takeIf { it.isNotBlank() } ?: entry.displayName
        TermInfoDialog(title = detailTitle, body = entryDetailText(entry)) {
            showDetail = false
        }
    }

    Column(modifier = rowModifier.fillMaxWidth()) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Spacer(modifier = Modifier.height(VideoNotesTokens.Space12))
        Row(verticalAlignment = Alignment.CenterVertically) {
            // 行首单选框：唯一的选择表达；未下载行也可勾选（合并包），云端生效时整组禁用
            RadioButton(
                selected = selected,
                onClick = { vm.requestSelect(entry.id) },
                enabled = !cloudDisabled,
            )
            // 名称独占剩余宽＋字号自适应：单行完整显示，不打省略号；右侧由此贴右。
            AutoFitName(
                text = entry.displayName,
                modifier = Modifier.weight(1f),
            )
            // 标签只保留「推荐」与绿色「已下载 ✓」（第二轮修订：实测徽标全部下架）
            if (entry.source == CatalogSource.CURATED && entry.recommended) {
                Spacer(modifier = Modifier.width(VideoNotesTokens.Space8))
                RowMiniTag("推荐")
            }
            if (completed) {
                Spacer(modifier = Modifier.width(VideoNotesTokens.Space8))
                Text(
                    text = "已下载 ✓",
                    style = MaterialTheme.typography.labelMedium,
                    color = ColorDownloaded,
                )
            }
            // 每行 ⓘ：副行余项（来源档位 · 量化 · 体积 · 内存需求）与描述收进弹窗。
            TermInfoIcon(onClick = { showDetail = true })
            // 管理态：右侧动作位切换为编辑/移除；正常态保持下载/删除两态。
            if (manageMode) {
                TextButton(onClick = onEdit) { Text("编辑") }
                TextButton(onClick = onRemove) {
                    Text("移除", color = MaterialTheme.colorScheme.error)
                }
            } else {
                // 右侧动作位严格只有两态：未下载「下载」、已下载「删除」。
                // 下载中/已暂停/失败时右侧留空，状态与操作留在下方次级区。
                when {
                    completed -> TextButton(onClick = { vm.delete(entry.id) }) { Text("删除") }
                    state.status == LocalModelDownloadManager.DlStatus.NOT_DOWNLOADED && !ramBlocked ->
                        Text(
                            text = "下载",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.clickable { vm.download(entry.id) },
                        )
                    state.status == LocalModelDownloadManager.DlStatus.NOT_DOWNLOADED && ramBlocked ->
                        Text(
                            text = "下载",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                }
            }
        }
        // 探针结论（四态）：动态条目与设探针门的新精选条目
        if (completed && (entry.source == CatalogSource.DYNAMIC || entry.requiresProbe)) {
            ProbeVerdict(probe)
        }
        // 下载状态区
        when (state.status) {
            LocalModelDownloadManager.DlStatus.DOWNLOADING -> {
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
                        TextButton(onClick = { vm.pause(entry.id) }) { Text("暂停") }
                    }
                }
            }
            LocalModelDownloadManager.DlStatus.PAUSED -> {
                Spacer(modifier = Modifier.height(VideoNotesTokens.Space4))
                Text(
                    text = "已暂停 ${(state.progress * 100).toInt()}%，可断点续传",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (!manageMode) {
                    Row {
                        TextButton(onClick = { vm.download(entry.id) }) { Text("继续") }
                        TextButton(onClick = { vm.delete(entry.id) }) { Text("删除") }
                    }
                }
            }
            LocalModelDownloadManager.DlStatus.FAILED -> {
                Spacer(modifier = Modifier.height(VideoNotesTokens.Space4))
                Text(
                    text = state.error ?: "下载失败",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                if (!manageMode) {
                    Row {
                        TextButton(onClick = { vm.download(entry.id) }) { Text("重试") }
                        TextButton(onClick = { vm.delete(entry.id) }) { Text("删除") }
                    }
                }
            }
            LocalModelDownloadManager.DlStatus.COMPLETED -> {
                Text(
                    text = "已下载 ${formatBytes(state.downloadedBytes)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            LocalModelDownloadManager.DlStatus.NOT_DOWNLOADED -> {
                state.error?.let { err ->
                    Spacer(modifier = Modifier.height(VideoNotesTokens.Space4))
                    Text(
                        text = err,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                if (ramBlocked) {
                    Text(
                        text = "该模型约需 ${entry.minRamGB} GB 内存，本机共 ${"%.0f".format(vm.deviceRamGB)} GB，已禁用下载",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(VideoNotesTokens.Space12))
    }
}

/** 探针四态文案：不可用结论如实说明原因，文件保留不自动删。 */
@Composable
private fun ProbeVerdict(probe: TemplateProbeState?) {
    when (probe) {
        TemplateProbeState.OK -> Text(
            text = "模板校验通过，可正常生成",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TemplateProbeState.NO_TEMPLATE -> Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                MiniTag("不可用", MaterialTheme.colorScheme.error)
            }
            Text(
                text = "无对话模板：该文件不含内嵌对话模板，无法用于对话生成。文件已保留，可手动删除。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        TemplateProbeState.UNSUPPORTED -> Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                MiniTag("不可用", MaterialTheme.colorScheme.error)
            }
            Text(
                text = "模板不受支持：该模型的对话模板不受当前运行时支持——" +
                    "不是文件损坏，也不是你的操作问题。文件已保留，可手动删除。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        TemplateProbeState.LOAD_FAILED -> Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                MiniTag("不可用", MaterialTheme.colorScheme.error)
            }
            Text(
                text = "加载失败：当前运行时无法加载该文件（架构或量化不受支持）。文件已保留，可手动删除。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        null -> Text(
            text = "模板校验待完成",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun MiniTag(text: String, color: Color) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = color,
        modifier = Modifier
            .border(1.dp, color, RoundedCornerShape(4.dp))
            .padding(horizontal = 5.dp, vertical = 1.dp),
    )
}

/**
 * 页顶「当前生效」条（恒显，第二轮修订：单行本页模型）：
 * 「当前生效：<模型标题名称>」，未选显「当前生效：未选择」，
 * 选中未下载显「（未下载）」后缀；云端生效时附注。
 */
@Composable
fun CurrentEffectBar(
    currentName: String?,
    currentUndownloaded: Boolean = false,
    cloudDisabled: Boolean,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "当前生效：" + (currentName ?: "未选择") +
                if (currentName != null && currentUndownloaded) "（未下载）" else "",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        if (cloudDisabled) {
            Spacer(modifier = Modifier.height(VideoNotesTokens.Space4))
            Text(
                text = "当前走云端，本地选择暂不生效",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(modifier = Modifier.height(VideoNotesTokens.Space12))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Spacer(modifier = Modifier.height(VideoNotesTokens.Space12))
    }
}

/** 条目副行各段：英文原名 · 来源档位 · 量化 · 体积 · 内存需求；影子条目沿用旧口径。 */
private fun entryMetaParts(entry: CatalogEntry): List<String> {
    val parts = mutableListOf<String>()
    when (entry.source) {
        CatalogSource.CURATED -> {
            entry.originalName?.let { parts += it }
            when (entry.origin) {
                "official" -> parts += "官方发布"
                "third-party" -> parts += "第三方量化"
            }
            quantText(entry.files.first().name)?.let { parts += it }
            parts += formatBytes(entry.totalBytes)
            entry.minRamGB?.let { parts += "需内存 ${it} GB" }
        }
        CatalogSource.SHADOW -> {
            quantText(entry.files.first().name)?.let { parts += it }
            parts += formatBytes(entry.totalBytes)
            parts += "已下架"
            parts += "本地已有"
            parts += "可删除"
        }
        CatalogSource.DYNAMIC -> {
            entry.hfRepo?.let { parts += it }
            parts += formatBytes(entry.totalBytes)
        }
    }
    return parts
}

/** ⓘ 弹窗正文：副行余项（去掉已作标题的英文原名）＋描述（精选条目）。 */
private fun entryDetailText(entry: CatalogEntry): String {
    val meta = entryMetaParts(entry)
        .filterNot { it == entry.originalName }
        .joinToString(" · ")
    return if (entry.source == CatalogSource.CURATED && entry.description.isNotBlank()) {
        if (meta.isBlank()) entry.description else "$meta\n${entry.description}"
    } else {
        meta
    }
}
