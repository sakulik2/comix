package xyz.sakulik.comic.ui.components

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import kotlinx.coroutines.launch

@Composable
fun ZoomableBox(
    modifier: Modifier = Modifier,
    onScaleChanged: (Float) -> Unit = {},
    onTap: () -> Unit = {},
    content: @Composable BoxScope.() -> Unit
) {
    val coroutineScope = rememberCoroutineScope()
    val scale = remember { Animatable(1f) }
    val offset = remember { Animatable(Offset.Zero, Offset.VectorConverter) }

    var containerSize by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }

    Box(
        modifier = modifier
            .onGloballyPositioned { containerSize = it.size }
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { onTap() },
                    onDoubleTap = {
                        coroutineScope.launch {
                            if (scale.value > 1.1f) {
                                launch { scale.animateTo(1f) }
                                launch { offset.animateTo(Offset.Zero) }
                                onScaleChanged(1f)
                            } else {
                                launch { scale.animateTo(2.5f) }
                                onScaleChanged(2.5f)
                            }
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
                        
                        val currentScale = scale.value
                        val newScale = (currentScale * zoom).coerceIn(1f, 5f)
                        
                        if (newScale > 1f || currentScale > 1f) {
                            coroutineScope.launch {
                                scale.snapTo(newScale)
                                
                                val maxX = (containerSize.width * (newScale - 1)) / 2
                                val maxY = (containerSize.height * (newScale - 1)) / 2
                                
                                val nextOffset = offset.value + pan
                                offset.snapTo(Offset(
                                    x = nextOffset.x.coerceIn(-maxX, maxX),
                                    y = nextOffset.y.coerceIn(-maxY, maxY)
                                ))
                                
                                onScaleChanged(newScale)
                            }
                            event.changes.forEach { change -> change.consume() }
                        } else {
                            coroutineScope.launch {
                                scale.snapTo(1f)
                                offset.snapTo(Offset.Zero)
                                onScaleChanged(1f)
                            }
                        }
                    } while (event.changes.any { change -> change.pressed })
                }
            }
            .graphicsLayer(
                scaleX = scale.value,
                scaleY = scale.value,
                translationX = offset.value.x,
                translationY = offset.value.y
            ),
        content = content
    )
}
