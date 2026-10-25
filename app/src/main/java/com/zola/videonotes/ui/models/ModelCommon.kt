package com.zola.videonotes.ui.models

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.dp
import com.zola.videonotes.domain.asr.formatBytes
import com.zola.videonotes.ui.theme.VideoNotesTokens

/**
 * 模型页共享件（2026-10-24 合并包：新人友好）：
 * 推荐组合配置（引导弹层一键下载用）、用途筛选 chips、术语说明、行副行文案。
 */

/** 推荐组合：用途 → 语言模型 + 识别模型（只定默认勾选，不锁死）。 */
data class ModelCombo(
    val key: String,
    val label: String,
    val llmId: String,
    val asrId: String,
)

object ModelCombos {
    val ALL = listOf(
        ModelCombo("zh", "中文课程", "qwen3-4b", "zipformer-ctc-zh-int8"),
        ModelCombo("en", "英文课程", "qwen3-4b", "whisper-small-int8"),
        ModelCombo("yue", "粤语", "qwen3-4b", "sensevoice-yue-int8"),
        ModelCombo("sichuan", "四川话", "qwen3-4b", "paraformer-sichuan-int8"),
        ModelCombo("dialect", "方言", "qwen3-4b", "dolphin-small-ctc-int8"),
        ModelCombo("light", "轻量手机", "qwen3-1_7b", "paraformer-zh-small-int8"),
    )
    val DEFAULT = ALL[0]
}

/** 用途筛选 chips：null = 全部。 */
val USE_CASE_CHIPS = listOf(
    null to "全部",
    "zh" to "中文",
    "en" to "英文",
    "dialect" to "方言",
    "light" to "轻量",
)

/** 语言模型量化中文名：Q4_K 系 → 4 位量化；F16/BF16 → 16 位量化；其余不展示。 */
fun quantText(fileName: String): String? {
    val upper = fileName.uppercase()
    return when {
        upper.contains("Q4_K") || upper.contains("IQ4") -> "4 位量化"
        upper.contains("F16") || upper.contains("BF16") -> "16 位量化"
        upper.contains("F32") -> "32 位"
        else -> null
    }
}

/** 识别模型时间戳说明（仅有实证的两档写进副行，其余靠页脚总注）。 */
fun asrTimestampNote(modelId: String): String? = when (modelId) {
    "zipformer-ctc-zh-int8" -> "时间戳：句级"
    "sensevoice-yue-int8" -> "时间戳：分段兜底"
    else -> null
}

/** 术语说明文案（ⓘ 对话框与页脚术语卡共用）。 */
object TermNotes {
    const val INT8 = "8 位整数量化，体积小、速度快，精度略降。"
    const val Q4K = "4 位量化，比 8 位更小，语言模型常用。"
    const val CER = "字错率：官方中文测试集上的错字比例，越低越好。"
    const val CONTEXT = "上下文：一次能看的最长文稿，超了会分段。"
    const val ALL = "int8：8 位整数量化，体积小、速度快，精度略降。\n" +
        "4 位量化（Q4_K）：比 8 位更小，语言模型常用。\n" +
        "字错率：官方中文测试集上的错字比例，越低越好。\n" +
        "上下文：一次能看的最长文稿，超了会分段。"
}

/** 通用 ⓘ 说明对话框。L-30：可选的 dismissButton 槽（默认无，旧调用点外观不变），
 * 本地模型行用它放“重新校验”，不改行布局。 */
@Composable
fun TermInfoDialog(
    title: String,
    body: String,
    onDismiss: () -> Unit,
    dismissButton: @Composable (() -> Unit)? = null,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(body) },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("知道了") }
        },
        dismissButton = dismissButton,
    )
}

/** 行尾 ⓘ 小图标（点开说明对话框）。 */
@Composable
fun TermInfoIcon(onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.padding(0.dp)) {
        Icon(
            imageVector = Icons.Outlined.Info,
            contentDescription = "说明",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 已下载绿色标签（两页共用，与识别页旧值一致）。 */
val ColorDownloaded = androidx.compose.ui.graphics.Color(0xFF1B7A3D)

/**
 * 模型行右侧固定列宽（两页共用）：动作槽（下载 / 删除右对齐）与管理态动作槽
 * （编辑 + 移除两个按钮，故更宽），让所有行的 ⓘ 与动作落在同一竖列上。
 * 宽度按内容实测收紧：动作槽 32dp 容纳两字裸文字（28dp）贴右，旧 52dp 左对齐
 * 会在下载右侧留 24dp 空隙；管理态 80dp 容纳编辑 + 移除两段裸文字（28+12+28=68dp，
 * 余 12dp）：旧 108dp 是 TextButton 内边距时代的宽度，裸文字化后多出的 28dp
 * 会挤名字栏（“中文增强”被截成“中文增”）。
 * 徽标（推荐 / 已下载）不再占固定槽，改为跟在名字后面 inline 排列。
 * 名称固定 titleMedium 单行全显，不再逐档缩放。
 */
val ModelRowActionSlotWidth = 32.dp
val ModelRowManageActionSlotWidth = 80.dp

/** 模型行首贴左单选框（2026-10-25）：24dp 盒子画 20dp 圆点，替代 Material3
 * RadioButton 自带的 48dp 触摸盒（圆点两侧各 12dp 透明边，左边看起来空一截）。
 * 行本身就是选择入口，24dp 点按区足够；禁用态降透明度、不可点。 */
@Composable
fun TightRadio(selected: Boolean, onClick: () -> Unit, enabled: Boolean = true) {
    val alpha = if (enabled) 1f else 0.38f
    val ring = if (selected) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        modifier = Modifier.size(ModelRowRadioSize)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier.size(20.dp)
                .border(2.dp, ring.copy(alpha = alpha), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) {
                Box(
                    modifier = Modifier.size(10.dp)
                        .background(
                            MaterialTheme.colorScheme.primary.copy(alpha = alpha),
                            CircleShape,
                        ),
                )
            }
        }
    }
}

/** 单选框盒子宽（24dp）与名间距（10dp）；副行缩进 = 两者之和，与名字左对齐。 */
val ModelRowRadioSize = 24.dp
val ModelRowRadioNameGap = 10.dp
val ModelRowSecondaryIndent = ModelRowRadioSize + ModelRowRadioNameGap

/** 用途筛选 chips 行（纸面 AssistChip，选中态品牌蓝；第二轮修订：单行横向滚动，不得换行）。 */
@Composable
fun UseCaseChips(
    selected: String?,
    onSelect: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(VideoNotesTokens.Space8),
    ) {
        USE_CASE_CHIPS.forEach { (value, label) ->
            FilterChip(
                selected = selected == value,
                onClick = { onSelect(value) },
                label = { Text(label, maxLines = 1) },
            )
        }
    }
}

/** 行标题旁的徽标（第二轮修订：仅保留「推荐」一轴；实测徽标全部下架）。 */

@Composable
fun RowMiniTag(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .border(1.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(4.dp))
            .padding(horizontal = 5.dp, vertical = 1.dp),
    )
}
