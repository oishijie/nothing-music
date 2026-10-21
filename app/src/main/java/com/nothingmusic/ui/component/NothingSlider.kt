package com.nothingmusic.ui.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp

/**
 * 细进度条 + 可选拖动。
 *
 * 绘制区只有 3dp 高，直接用会很难点中，所以外面套一层 24dp 的触摸区承担手势；
 * 拖动期间用本地值渲染，松手才回调 onSeek，避免 seek 与播放位置回写互相打架。
 */
@Composable
fun NothingLinearProgress(
    progress: Float,
    modifier: Modifier = Modifier,
    trackColor: Color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.15f),
    progressColor: Color = MaterialTheme.colorScheme.primary,
    height: Int = 3,
    thumbColor: Color = MaterialTheme.colorScheme.onBackground,
    showThumb: Boolean = true,
    draggable: Boolean = true,
    onSeek: (Float) -> Unit = {},
    onSeekStart: () -> Unit = {},
    onSeekEnd: () -> Unit = {},
) {
    val clamped = progress.coerceIn(0f, 1f)
    val thumbFillColor = thumbColor
    var dragFraction by remember { mutableStateOf<Float?>(null) }
    val shown = dragFraction ?: clamped

    Box(
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (draggable) {
                    Modifier
                        .height(24.dp)
                        .pointerInput(Unit) {
                            detectDragGestures(
                                onDragStart = { offset ->
                                    onSeekStart()
                                    dragFraction = (offset.x / size.width.toFloat()).coerceIn(0f, 1f)
                                },
                                onDragEnd = {
                                    dragFraction?.let(onSeek)
                                    dragFraction = null
                                    onSeekEnd()
                                },
                                onDragCancel = {
                                    dragFraction = null
                                    onSeekEnd()
                                },
                            ) { change, _ ->
                                change.consume()
                                dragFraction = (change.position.x / size.width.toFloat()).coerceIn(0f, 1f)
                            }
                        }
                        .pointerInput(Unit) {
                            detectTapGestures { offset ->
                                val fraction = (offset.x / size.width.toFloat()).coerceIn(0f, 1f)
                                onSeekStart()
                                onSeek(fraction)
                                onSeekEnd()
                            }
                        }
                } else {
                    Modifier.height(height.dp)
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(height.dp)
        ) {
            val barHeight = size.height
            drawRoundRect(
                color = trackColor,
                topLeft = Offset(0f, 0f),
                size = Size(size.width, barHeight),
                cornerRadius = CornerRadius(barHeight / 2, barHeight / 2),
            )
            val barWidth = size.width * shown
            if (barWidth > 0.01f) {
                drawRoundRect(
                    brush = Brush.horizontalGradient(
                        listOf(progressColor.copy(alpha = 0.7f), progressColor)
                    ),
                    topLeft = Offset(0f, 0f),
                    size = Size(barWidth, barHeight),
                    cornerRadius = CornerRadius(barHeight / 2, barHeight / 2),
                )
            }
            if (showThumb && barWidth > 0) {
                val thumbX = (size.width * shown).coerceAtLeast(5.dp.toPx())
                drawCircle(
                    color = thumbFillColor,
                    radius = 5.dp.toPx(),
                    center = Offset(thumbX, size.height / 2),
                )
                drawCircle(
                    color = progressColor,
                    radius = 3.dp.toPx(),
                    center = Offset(thumbX, size.height / 2),
                )
            }
        }
    }
}
