package com.zola.videonotes.ui.models

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zola.videonotes.data.catalog.CatalogEntry
import com.zola.videonotes.data.catalog.CatalogKind
import com.zola.videonotes.data.catalog.CatalogStore
import com.zola.videonotes.data.catalog.UserCatalog
import com.zola.videonotes.domain.asr.formatBytes
import com.zola.videonotes.ui.theme.VideoNotesTokens

/**
 * 推荐池（从推荐池添加）：列出该类别的精选池原值。
 * - 未在列表：添加（回到精选原值）。
 * - 已在列表但被改过：恢复原值（丢弃改动覆盖）。
 * - 已在列表且是原值：已添加（禁用）。
 * 底部「自定义填写」走表单新增。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelPoolSheet(
    kind: CatalogKind,
    onDismiss: () -> Unit,
    onAddPoolEntry: (String) -> Unit,
    onCustomFill: () -> Unit,
) {
    val userVersion by UserCatalog.version.collectAsStateWithLifecycle()
    val snapshot = remember(userVersion) { UserCatalog.snapshot() }
    val pool = if (kind == CatalogKind.LLM) {
        CatalogStore.llmCurated()
    } else {
        CatalogStore.asrCurated()
    }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = VideoNotesTokens.Space16),
        ) {
            Text(text = "从推荐池添加", style = MaterialTheme.typography.titleMedium)
            Text(
                text = "从推荐池恢复的是精选原值；自定义模型移除后无法恢复，移除前请确认。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(VideoNotesTokens.Space8))
            pool.forEach { entry ->
                val status = when {
                    entry.id in snapshot.removedIds -> PoolEntryStatus.NOT_IN_LIST
                    UserCatalog.isEdited(entry.id) -> PoolEntryStatus.EDITED
                    else -> PoolEntryStatus.ORIGINAL
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = entry.displayName, style = MaterialTheme.typography.titleSmall)
                        val metaParts = mutableListOf<String>()
                        entry.originalName?.let { metaParts += it }
                        metaParts += formatBytes(entry.totalBytes)
                        entry.minRamGB?.let { metaParts += "需内存 ${it} GB" }
                        Text(
                            text = metaParts.joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (entry.recommended) {
                        RowMiniTag("推荐")
                    }
                    when (status) {
                        PoolEntryStatus.NOT_IN_LIST -> TextButton(
                            onClick = { onAddPoolEntry(entry.id) },
                        ) { Text("添加") }
                        PoolEntryStatus.EDITED -> TextButton(
                            onClick = { onAddPoolEntry(entry.id) },
                        ) { Text("恢复原值") }
                        PoolEntryStatus.ORIGINAL -> TextButton(
                            enabled = false,
                            onClick = {},
                        ) { Text("已添加") }
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
            Spacer(modifier = Modifier.height(VideoNotesTokens.Space12))
            OutlinedButton(
                onClick = onCustomFill,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("自定义填写")
            }
            Spacer(modifier = Modifier.height(VideoNotesTokens.Space24))
        }
    }
}

private enum class PoolEntryStatus { NOT_IN_LIST, ORIGINAL, EDITED }

/**
 * 移除确认：移除（条目离列）与删除（文件删除）是两个动作。
 * 勾选框默认勾选；不勾则文件留在磁盘，恢复添加后可找回。
 */
@Composable
fun ModelRemoveDialog(
    entry: CatalogEntry,
    onDismiss: () -> Unit,
    onConfirm: (deleteFiles: Boolean) -> Unit,
) {
    var deleteFiles by remember { mutableStateOf(true) }
    val isPool = !UserCatalog.isCustom(entry.id)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("移除「${entry.displayName}」？") },
        text = {
            Column {
                Text("将条目从列表移除。")
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { deleteFiles = !deleteFiles }
                        .padding(vertical = VideoNotesTokens.Space8),
                ) {
                    Checkbox(
                        checked = deleteFiles,
                        onCheckedChange = { deleteFiles = it },
                    )
                    Text("同时删除已下载文件（约 ${formatBytes(entry.totalBytes)}）")
                }
                Text(
                    text = if (isPool) {
                        "精选原值可随时从推荐池重新添加（回到精选原值）。"
                    } else {
                        "自定义条目移除后无法恢复，需要重新填写。"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(deleteFiles) }) {
                Text("移除", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}
