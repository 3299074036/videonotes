package com.zola.videonotes.ui.metadata

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.zola.videonotes.ui.theme.VideoNotesTokens

/**
 * 笔记生成入口卡片（M4/M5/M7a）：文稿就绪后展示。
 * - 未生成过：显示"生成笔记"（未配置 LLM 时置灰 + 引导去设置）；
 * - 生成中（M7a）：圆圈进度 + 阶段文案 + 取消，后台任务，用户可离开；
 * - 生成失败：中文原因 + 重试；
 * - 已生成过：显示"查看笔记" + "重新生成"。
 */
@Composable
fun NoteGenerateCard(
    llmConfigured: Boolean,
    existingNoteId: Long?,
    generateState: GenerateNoteUiState,
    onGenerateClick: () -> Unit,
    onGenerateRetry: () -> Unit,
    onGenerateCancel: () -> Unit,
    onViewNoteClick: (Long) -> Unit,
    onSettingsClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(VideoNotesTokens.CornerLarge),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(VideoNotesTokens.Space16),
        ) {
            Text(
                text = "课堂笔记",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(modifier = Modifier.height(VideoNotesTokens.Space4))
            Text(
                text = "根据字幕文稿生成结构化 Markdown 笔记（含小节要点、知识地图、概念卡片、自测题与时间索引）",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(VideoNotesTokens.Space12))

            when (val state = generateState) {
                is GenerateNoteUiState.Working -> {
                    // 后台任务：圆圈进度 + 阶段文案，用户可离开本页
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(VideoNotesTokens.Space12),
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(VideoNotesTokens.Space32),
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = state.stageText,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = "后台生成中，可离开本页",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        TextButton(onClick = onGenerateCancel) {
                            Text("取消")
                        }
                    }
                }
                is GenerateNoteUiState.Failed -> {
                    Text(
                        text = state.message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Spacer(modifier = Modifier.height(VideoNotesTokens.Space8))
                    Row(horizontalArrangement = Arrangement.spacedBy(VideoNotesTokens.Space8)) {
                        Button(onClick = onGenerateRetry) {
                            Text("重试")
                        }
                        if (existingNoteId != null) {
                            TextButton(onClick = { onViewNoteClick(existingNoteId) }) {
                                Text("查看旧笔记")
                            }
                        }
                    }
                }
                is GenerateNoteUiState.Success -> {
                    // 成功后直接给查看入口（ViewModel 会在跳转前复位状态）
                    Button(
                        onClick = { onViewNoteClick(state.noteId) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("查看笔记")
                    }
                }
                GenerateNoteUiState.Idle -> {
                    if (existingNoteId != null) {
                        Button(
                            onClick = { onViewNoteClick(existingNoteId) },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("查看笔记")
                        }
                        TextButton(
                            onClick = onGenerateClick,
                            enabled = llmConfigured,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("重新生成")
                        }
                    } else {
                        Button(
                            onClick = onGenerateClick,
                            enabled = llmConfigured,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("生成笔记")
                        }
                    }
                }
            }
            if (!llmConfigured && generateState is GenerateNoteUiState.Idle) {
                TextButton(
                    onClick = onSettingsClick,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("请先到设置页配置大语言模型")
                }
            }
        }
    }
}
