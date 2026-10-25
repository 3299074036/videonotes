package com.zola.videonotes.ui.models

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import com.zola.videonotes.data.catalog.ASR_TEMPLATES
import com.zola.videonotes.data.catalog.CatalogEntry
import com.zola.videonotes.data.catalog.CatalogKind
import com.zola.videonotes.data.catalog.EntryDraft
import com.zola.videonotes.data.catalog.EntryFileDraft
import com.zola.videonotes.data.catalog.LLM_TEMPLATES
import com.zola.videonotes.data.catalog.templateLabel
import com.zola.videonotes.data.catalog.toEntry
import com.zola.videonotes.data.catalog.validateEntryDraft
import com.zola.videonotes.ui.components.PaperSegmentedRow
import com.zola.videonotes.ui.theme.VideoNotesTokens

/** 表单打开状态：新增（按类别）或编辑（预填条目，标识与类别不可改）。 */
sealed interface ModelFormState {
    data class Add(val kind: CatalogKind) : ModelFormState
    data class Edit(val entry: CatalogEntry) : ModelFormState
}

private data class FileField(
    val name: String = "",
    val url: String = "",
    val size: String = "",
    val sha: String = "",
)

private val USE_CASE_OPTIONS = listOf(
    "zh" to "中文",
    "en" to "英文",
    "dialect" to "方言",
    "light" to "轻量",
)

/**
 * 新增／编辑表单（独立页；校验只在表单内报错，不动列表）。
 * 保存成功后把条目交给 [onSave]（新增走 addCustom，编辑走 saveEditedEntry，分支在调用方）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelFormScreen(
    formState: ModelFormState,
    existingIds: Set<String>,
    onBack: () -> Unit,
    onSave: (CatalogEntry) -> Unit,
) {
    val isNew = formState is ModelFormState.Add
    val editEntry = (formState as? ModelFormState.Edit)?.entry

    var displayName by remember { mutableStateOf(editEntry?.displayName ?: "") }
    var id by remember { mutableStateOf(editEntry?.id ?: "") }
    var originalName by remember { mutableStateOf(editEntry?.originalName ?: "") }
    var kind by remember {
        mutableStateOf(
            when (formState) {
                is ModelFormState.Add -> formState.kind
                is ModelFormState.Edit -> formState.entry.kind
            },
        )
    }
    var templateId by remember { mutableStateOf(editEntry?.templateId ?: "") }
    var templateExpanded by remember { mutableStateOf(false) }
    var useCases by remember { mutableStateOf(editEntry?.useCases?.toSet() ?: emptySet()) }
    var minRamInput by remember {
        mutableStateOf(
            editEntry?.minRamGB?.let { v ->
                if (v == v.toLong().toDouble()) v.toLong().toString() else v.toString()
            } ?: "",
        )
    }
    var description by remember { mutableStateOf(editEntry?.description ?: "") }
    // 是否推荐：新增默认否，编辑预填当前值；保存时直接决定条目的推荐标签与组内排序。
    var recommended by remember { mutableStateOf(editEntry?.recommended ?: false) }
    val files = remember {
        mutableStateListOf<FileField>().apply {
            val initial = editEntry?.files?.map {
                FileField(
                    name = it.name,
                    url = it.url,
                    size = it.sizeBytes.toString(),
                    sha = it.sha256 ?: "",
                )
            } ?: listOf(FileField())
            addAll(initial)
        }
    }
    var errors by remember { mutableStateOf<Map<String, String>>(emptyMap()) }

    fun clearError(field: String) {
        if (field in errors) errors = errors - field
    }

    fun save() {
        val minRam: Double?
        if (minRamInput.isBlank()) {
            minRam = null
        } else {
            minRam = minRamInput.toDoubleOrNull()
            if (minRam == null) {
                errors = mapOf("minRamGB" to "最低内存须为数字")
                return
            }
        }
        val draft = EntryDraft(
            displayName = displayName,
            id = id,
            originalName = originalName,
            kind = kind,
            templateId = templateId,
            useCases = useCases.toList(),
            minRamGB = minRam,
            description = description,
            files = files.map {
                EntryFileDraft(
                    name = it.name,
                    url = it.url,
                    sizeBytes = it.size.toLongOrNull(),
                    sha256 = it.sha,
                )
            },
        )
        val found = validateEntryDraft(draft, existingIds, isNew)
        if (found.isNotEmpty()) {
            errors = found.associate { it.field to it.message }
            return
        }
        // 推荐位由表单「是否推荐」字段决定（新增默认否，编辑预填当前值）。
        onSave(draft.toEntry(recommended = recommended, source = editEntry?.source
            ?: com.zola.videonotes.data.catalog.CatalogSource.CURATED))
    }

    val templates = if (kind == CatalogKind.LLM) LLM_TEMPLATES else ASR_TEMPLATES

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (isNew) "新增模型" else "编辑模型") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回",
                        )
                    }
                },
            )
        },
        bottomBar = {
            // 保存钉底：不用滑到底才能点；校验文案放按钮上方。
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = VideoNotesTokens.Space16)
                    .padding(bottom = VideoNotesTokens.Space16),
            ) {
                Text(
                    text = "校验：名称、标识、模板、至少一个文件必填；文件须有文件名、链接、大小。" +
                        "语言模型在下载完成时走模板探针，探针不通过不可生成。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = VideoNotesTokens.Space8),
                )
                Button(
                    onClick = ::save,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("保存")
                }
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = VideoNotesTokens.Space16),
        ) {
            Text(
                text = "保存后即出现在列表里，与其他模型一样可选用、可下载、可改可删。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(VideoNotesTokens.Space16))

            FormSection("基本信息")
            FormLabel("名称")
            OutlinedTextField(
                value = displayName,
                onValueChange = { displayName = it; clearError("displayName") },
                placeholder = { Text("如：通义千问 4B 自定义") },
                singleLine = true,
                isError = "displayName" in errors,
                modifier = Modifier.fillMaxWidth(),
            )
            FormError(errors["displayName"])

            FormLabel("标识 · 列表内唯一，保存后不可改")
            OutlinedTextField(
                value = id,
                onValueChange = { id = it; clearError("id") },
                placeholder = { Text("小写字母、数字、下划线或连字符") },
                singleLine = true,
                enabled = isNew,
                isError = "id" in errors,
                modifier = Modifier.fillMaxWidth(),
            )
            FormHelp("如 qwen3-4b-chat；不可与现有模型的标识重复。")
            FormError(errors["id"])

            FormLabel("英文原名 · 可选，显示在副行")
            OutlinedTextField(
                value = originalName,
                onValueChange = { originalName = it },
                placeholder = { Text("如 Qwen3-4B-Instruct-2507") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            FormLabel("类别")
            if (isNew) {
                PaperSegmentedRow(
                    labels = listOf("语言大模型", "识别模型"),
                    selectedIndex = if (kind == CatalogKind.LLM) 0 else 1,
                    onSelect = { index ->
                        kind = if (index == 0) CatalogKind.LLM else CatalogKind.ASR
                        templateId = ""
                        clearError("templateId")
                    },
                    spread = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                Text(
                    text = if (kind == CatalogKind.LLM) "语言大模型" else "识别模型",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            FormSection("模板与用途")
            FormLabel("对话模板")
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { templateExpanded = !templateExpanded }
                    .padding(vertical = VideoNotesTokens.Space8),
            ) {
                Text(
                    text = if (templateId.isBlank()) "请选择模板" else templateLabel(templateId),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                Text(text = if (templateExpanded) "︿" else "﹀")
            }
            if (templateExpanded) {
                templates.forEach { template ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                templateId = template
                                templateExpanded = false
                                clearError("templateId")
                            },
                    ) {
                        RadioButton(
                            selected = templateId == template,
                            onClick = {
                                templateId = template
                                templateExpanded = false
                                clearError("templateId")
                            },
                        )
                        Text(text = templateLabel(template))
                    }
                }
            }
            FormHelp("按类别过滤；语言大模型显示 Qwen3 / Llama3 / 内嵌 / 命名模板。")
            FormError(errors["templateId"])

            FormLabel("用途 · 可多选，决定筛选 chips")
            Row {
                USE_CASE_OPTIONS.forEach { (value, label) ->
                    val selected = value in useCases
                    androidx.compose.material3.FilterChip(
                        selected = selected,
                        onClick = {
                            useCases = if (selected) useCases - value else useCases + value
                        },
                        label = { Text(label) },
                        modifier = Modifier.padding(end = VideoNotesTokens.Space8),
                    )
                }
            }

            FormSection("推荐与内存")
            FormLabel("是否推荐 · 单选，选「是」显示推荐标签")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clickable { recommended = true },
                ) {
                    RadioButton(selected = recommended, onClick = { recommended = true })
                    Text(text = "是")
                }
                Spacer(modifier = Modifier.padding(horizontal = VideoNotesTokens.Space12))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clickable { recommended = false },
                ) {
                    RadioButton(selected = !recommended, onClick = { recommended = false })
                    Text(text = "否")
                }
            }

            FormLabel("最低内存 · 可选，低于此内存的设备禁用下载")
            OutlinedTextField(
                value = minRamInput,
                onValueChange = { minRamInput = it; clearError("minRamGB") },
                placeholder = { Text("如 6（单位 GB）") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                isError = "minRamGB" in errors,
                modifier = Modifier.fillMaxWidth(),
            )
            FormError(errors["minRamGB"])

            FormLabel("描述 · 可选")
            OutlinedTextField(
                value = description,
                onValueChange = { description = it },
                placeholder = { Text("一句话说明适合什么场景") },
                minLines = 2,
                modifier = Modifier.fillMaxWidth(),
            )

            FormSection("文件")
            files.forEachIndexed { index, file ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = VideoNotesTokens.Space8)
                        .border(
                            1.dp,
                            MaterialTheme.colorScheme.outlineVariant,
                            RoundedCornerShape(12.dp),
                        )
                        .padding(VideoNotesTokens.Space12),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "文件 ${index + 1}",
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.weight(1f),
                        )
                        // 行数大于 1 时可删（语言模型切到识别模型多添了行、再切回来时也能剪回单文件）。
                        if (files.size > 1) {
                            TextButton(onClick = {
                                files.removeAt(index)
                                errors = errors.filterKeys {
                                    !it.startsWith("file_${index}_")
                                }
                            }) { Text("删除") }
                        }
                    }
                    OutlinedTextField(
                        value = file.name,
                        onValueChange = {
                            files[index] = file.copy(name = it)
                            clearError("file_${index}_name")
                        },
                        placeholder = { Text("文件名") },
                        singleLine = true,
                        isError = "file_${index}_name" in errors,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    FormError(errors["file_${index}_name"])
                    OutlinedTextField(
                        value = file.url,
                        onValueChange = {
                            files[index] = file.copy(url = it)
                            clearError("file_${index}_url")
                        },
                        placeholder = { Text("下载链接（http(s)://）") },
                        singleLine = true,
                        isError = "file_${index}_url" in errors,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    FormError(errors["file_${index}_url"])
                    OutlinedTextField(
                        value = file.size,
                        onValueChange = {
                            files[index] = file.copy(size = it)
                            clearError("file_${index}_size")
                        },
                        placeholder = { Text("大小（字节）") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        isError = "file_${index}_size" in errors,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    FormError(errors["file_${index}_size"])
                    OutlinedTextField(
                        value = file.sha,
                        onValueChange = {
                            files[index] = file.copy(sha = it)
                            clearError("file_${index}_sha")
                        },
                        placeholder = { Text("SHA-256（可选，不填则下载后不校验）") },
                        singleLine = true,
                        isError = "file_${index}_sha" in errors,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    FormError(errors["file_${index}_sha"])
                }
            }
            if (kind == CatalogKind.ASR) {
                TextButton(onClick = { files.add(FileField()) }) { Text("＋ 添加文件") }
                FormHelp("识别模型可点「＋ 添加文件」增加多行；保存时会按所选模板校验必需文件是否齐全。")
            }
            FormError(errors["files"])
            Spacer(modifier = Modifier.height(VideoNotesTokens.Space16))
        }
    }
}

@Composable
private fun FormSection(text: String) {
    Spacer(modifier = Modifier.height(VideoNotesTokens.Space20))
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
private fun FormLabel(text: String) {
    Spacer(modifier = Modifier.height(VideoNotesTokens.Space16))
    Text(text = text, style = MaterialTheme.typography.titleSmall)
    Spacer(modifier = Modifier.height(VideoNotesTokens.Space4))
}

@Composable
private fun FormHelp(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = VideoNotesTokens.Space4),
    )
}

@Composable
private fun FormError(message: String?) {
    if (message != null) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(top = VideoNotesTokens.Space4),
        )
    }
}
