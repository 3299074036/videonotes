package com.zola.videonotes.ui.notes

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zola.videonotes.data.reader.DeepLinkBuilder
import com.zola.videonotes.domain.model.Note
import com.zola.videonotes.ui.components.EmptyView
import com.zola.videonotes.ui.components.PlatformBadge
import com.zola.videonotes.ui.theme.VideoNotesTokens
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 笔记库（M5 首页）：全部笔记按创建时间倒序。
 * 空状态引导去导入页；FAB 进入视频导入。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NoteLibraryScreen(
    onImportClick: () -> Unit,
    onNoteClick: (Long) -> Unit,
    onSettingsClick: () -> Unit,
    viewModel: NoteLibraryViewModel = hiltViewModel(),
) {
    val notes by viewModel.notes.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("视记") },
                actions = {
                    IconButton(onClick = onSettingsClick) {
                        Icon(
                            imageVector = Icons.Filled.Settings,
                            contentDescription = "设置",
                        )
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onImportClick) {
                Icon(imageVector = Icons.Filled.Add, contentDescription = "导入视频")
            }
        },
    ) { padding ->
        if (notes.isEmpty()) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            ) {
                Spacer(modifier = Modifier.height(VideoNotesTokens.Space48))
                EmptyView(
                    title = "还没有笔记",
                    description = "去导入视频生成第一份笔记",
                    icon = Icons.Filled.MenuBook,
                )
                Button(onClick = onImportClick) {
                    Text("导入视频")
                }
            }
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(VideoNotesTokens.Space12),
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = VideoNotesTokens.Space16),
            ) {
                item { Spacer(modifier = Modifier.height(VideoNotesTokens.Space4)) }
                items(notes, key = { it.id }) { note ->
                    NoteLibraryItem(
                        note = note,
                        onClick = { onNoteClick(note.id) },
                    )
                }
                item { Spacer(modifier = Modifier.height(VideoNotesTokens.Space48)) }
            }
        }
    }
}

/** 笔记库列表项：标题 / 视频标题 / 平台徽标 / 时间 / 字数。 */
@Composable
private fun NoteLibraryItem(
    note: Note,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        shape = RoundedCornerShape(VideoNotesTokens.CornerLarge),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(VideoNotesTokens.Space16),
        ) {
            Text(
                text = note.title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
            )
            if (note.videoTitle.isNotBlank()) {
                Spacer(modifier = Modifier.height(VideoNotesTokens.Space4))
                Text(
                    text = note.videoTitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            Spacer(modifier = Modifier.height(VideoNotesTokens.Space8))
            Row(verticalAlignment = Alignment.CenterVertically) {
                DeepLinkBuilder.platformOf(note.videoStorageKey)?.let { platform ->
                    PlatformBadge(platform = platform)
                    Spacer(modifier = Modifier.width(VideoNotesTokens.Space8))
                }
                Text(
                    text = formatLibraryTime(note.createdAt),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.width(VideoNotesTokens.Space8))
                Text(
                    text = "${note.markdown.length} 字",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun formatLibraryTime(millis: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(millis))
