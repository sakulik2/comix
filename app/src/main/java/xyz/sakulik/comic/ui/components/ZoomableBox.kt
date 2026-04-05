package xyz.sakulik.comic.ui.components

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned

@Composable
fun ZoomableBox(
    modifier: Modifier = Modifier,
    onScaleChanged: (Float) -> Unit = {},
    onTap: () -> Unit = {},
    content: @Composable BoxScope.() -> Unit
) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    // 记录容器尺寸以计算边界
    var containerSize by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }

    Box(
        modifier = modifier
            .onGloballyPositioned { containerSize = it.size }
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { onTap() },
                    onDoubleTap = {
                        if (scale > 1.1f) {
                            scale = 1f
                            offset = Offset.Zero
                            onScaleChanged(1f)
                        } else {
                            scale = 2.5f
                            // 暂时居中放大，以后可以优化为点击位置放大
                            onScaleChanged(2.5f)
                        }
                    }
                )
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown()
                    do {
                        val event = awaitPointerEvent()
                        val zoom = event.calculateZoom()
                        val pan = event.calculatePan()
                        
                        val newScale = (scale * zoom).coerceIn(1f, 5f)
                        
                        if (newScale > 1f || scale > 1f) {
                            scale = newScale
                            
                            // 计算当前缩放下的最大允许偏移量
                            val maxX = (containerSize.width * (scale - 1)) / 2
                            val maxY = (containerSize.height * (scale - 1)) / 2
                            
                            val nextOffset = offset + pan
                            
                            // 限制在边界内
                            offset = Offset(
                                x = nextOffset.x.coerceIn(-maxX, maxX),
                                y = nextOffset.y.coerceIn(-maxY, maxY)
                            )
                            
                            onScaleChanged(scale)
                            event.changes.forEach { change -> change.consume() }
                        } else {
                            scale = 1f
                            offset = Offset.Zero
                            onScaleChanged(1f)
                        }
                    } while (event.changes.any { change -> change.pressed })
                }
            }
            .graphicsLayer(
                scaleX = scale,
                scaleY = scale,
                translationX = offset.x,
                translationY = offset.y
            ),
        content = content
    )
}
