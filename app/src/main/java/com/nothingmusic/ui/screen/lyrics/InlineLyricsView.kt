package com.nothingmusic.ui.screen.lyrics

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.nothingmusic.domain.model.Track
import com.nothingmusic.ui.screen.LyricsViewModel
import com.nothingmusic.ui.theme.DotMatrixFont

/**
 * 播放页内嵌歌词视图：滚动条随当前行居中，非当前行降低存在感。
 * 与棉花一致的地方是完全展开显示，而不是弹层。
 */
@Composable
fun InlineLyricsView(
    viewModel: LyricsViewModel,
    track: Track?,
    positionMs: Long,
    modifier: Modifier = Modifier,
) {
    val lyrics by viewModel.lyrics.collectAsState()
    val loading by viewModel.loading.collectAsState()

    LaunchedEffect(track?.id) {
        viewModel.loadForTrack(track)
    }

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        when {
            loading && lyrics.lines.isEmpty() -> {
                Text(
                    text = "读取歌词…",
                    style = MaterialTheme.typography.labelMedium.copy(fontFamily = DotMatrixFont),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            lyrics.lines.isEmpty() -> {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = "无歌词",
                        style = MaterialTheme.typography.titleSmall.copy(fontFamily = DotMatrixFont),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = "点击唱片返回封面",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            }
            else -> {
                val activeIndex = remember(positionMs, lyrics.lines.size) {
                    lyrics.lines.indexOfLast { it.timestampMs <= positionMs }.coerceAtLeast(0)
                }
                val listState = rememberLazyListState()

                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 120.dp),
                ) {
                    itemsIndexed(lyrics.lines) { index, line ->
                        val isCurrent = index == activeIndex
                        Text(
                            text = if (line.text.isBlank()) "\u00A0" else line.text,
                            style = if (isCurrent) {
                                MaterialTheme.typography.titleMedium.copy(fontFamily = DotMatrixFont)
                            } else {
                                MaterialTheme.typography.bodyMedium
                            },
                            color = if (isCurrent) {
                                MaterialTheme.colorScheme.onBackground
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
                            },
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp),
                        )
                    }
                }

                // 让当前行大致居中：滚动到目标行并留出上方偏移。
                LaunchedEffect(activeIndex) {
                    if (activeIndex >= 0) {
                        listState.animateScrollToItem(activeIndex, scrollOffset = -220)
                    }
                }
            }
        }
    }
}
