package com.zola.videonotes.ui.metadata

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.zola.videonotes.ui.theme.VideoNotesTokens

/**
 * 语音转写二次确认对话框（M3）：提示耗时 / 流量 / 电量。
 * M3 转写在前台协程执行（M6 才接入 WorkManager），需提醒用户转写期间保持本页在前台。
 */
@Composable
fun TranscribeConfirmDialog(
    info: TranscribeConfirmInfo,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("开始语音转写？") },
        text = {
            Column {
                ConfirmRow(label = "所选模型", value = info.modelName)
                Spacer(modifier = Modifier.height(VideoNotesTokens.Space8))
                ConfirmRow(label = "视频时长", value = info.durationText)
                Spacer(modifier = Modifier.height(VideoNotesTokens.Space8))
                Text(
                    text = "转写耗时与视频时长、机型和模型有关，tiny 模型在中端机上约为数倍速播放；" +
                        "需要先下载音频，转写期间请保持本页在前台。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(VideoNotesTokens.Space8))
                Text(
                    text = info.batteryText,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (info.lowBattery) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                if (info.lowBattery) {
                    Spacer(modifier = Modifier.height(VideoNotesTokens.Space4))
                    Text(
                        text = "电量较低，建议连接电源后再转写",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("开始转写")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        },
    )
}

@Composable
private fun ConfirmRow(label: String, value: String) {
    Text(
        text = "$label：$value",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurface,
    )
}
