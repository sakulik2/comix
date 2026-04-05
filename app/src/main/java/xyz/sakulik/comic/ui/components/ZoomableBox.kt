package xyz.sakulik.comic.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalViewConfiguration
import kotlinx.coroutines.launch
import kotlin.math.abs

@Composable
fun ZoomableBox(
    modifier: Modifier = Modifier,
    onScaleChanged: (Float) -> Unit = {},
    onTap: () -> Unit = {},
    content: @Composable BoxScope.() -> Unit
) {
    val scope = rememberCoroutineScope()
    val scale = remember { Animatable(1f) }
    val offset = remember { Animatable(Offset.Zero, Offset.VectorConverter) }
    val viewConfig = LocalViewConfiguration.current

    var containerSize by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }

    // 用于双击检测的状态
    var lastTapTime by remember { mutableLongStateOf(0L) }

    Box(
        modifier = modifier
            .onGloballyPositioned { containerSize = it.size }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val startTime = System.currentTimeMillis()
                    var isDoubleTap = false
                    
                    // 检测双击逻辑
                    if (startTime - lastTapTime < viewConfig.doubleTapTimeoutMillis) {
                        isDoubleTap = true
                    }
                    
                    var zoomAccumulated = 1f
                    var panAccumulated = Offset.Zero
                    var hasMoved = false

                    do {
                        val event = awaitPointerEvent()
                        val zoom = event.calculateZoom()
                        val pan = event.calculatePan()
                        
                        val currentScale = scale.value
                        val newScale = (currentScale * zoom).coerceIn(1f, 5f)
                        
                        val isZooming = abs(zoom - 1f) > 0.001f
                        val isPanning = pan.getDistance() > 0.1f
                        if (isPanning) hasMoved = true

                        // --- 核心逻辑判断：是否要消费该手势 ---
                        // 1. 如果正在放大 (Scale > 1.05)，则锁死并消费所有手势
                        // 2. 如果正在进行缩放操作，也消费
                        // 3. 如果在 1.0 状态且只是左右划动，则“弃权”，让 Pager 处理
                        val shouldConsume = (currentScale > 1.05f) || isZooming || (abs(pan.y) > abs(pan.x) * 1.5f && isPanning)

                        if (shouldConsume) {
                            if (scale.isRunning) scope.launch { scale.stop(); offset.stop() }
                            
                            scope.launch {
                                if (isZooming) scale.snapTo(newScale)
                                
                                val maxX = (containerSize.width * (scale.value - 1)) / 2
                                val maxY = (containerSize.height * (scale.value - 1)) / 2
                                
                                val nextOffset = offset.value + pan
                                offset.snapTo(Offset(
                                    x = nextOffset.x.coerceIn(-maxX, maxX.coerceAtLeast(0f)),
                                    y = nextOffset.y.coerceIn(-maxY, maxY.coerceAtLeast(0f))
                                ))
                                onScaleChanged(scale.value)
                            }
                            // 消费掉所有改变
                            event.changes.forEach { it.consume() }
                        } else {
                            // 不消费水平滑动手势信号，Pager 就能划动了
                        }
                        
                        zoomAccumulated *= zoom
                        panAccumulated += pan
                    } while (event.changes.any { it.pressed })

                    // 处理抬起时的点击和双击
                    val endTime = System.currentTimeMillis()
                    if (!hasMoved && (endTime - startTime) < viewConfig.doubleTapMinTimeMillis + 200) {
                        if (isDoubleTap) {
                            // 执行双击
                            scope.launch {
                                if (scale.value > 1.05f) {
                                    launch { scale.animateTo(1f) }
                                    launch { offset.animateTo(Offset.Zero) }
                                    onScaleChanged(1f)
                                } else {
                                    launch { scale.animateTo(2.5f) }
                                    onScaleChanged(2.5f)
                                }
                            }
                            lastTapTime = 0L // 重置
                        } else {
                            // 记录这次点击，等下一次检查双击，或者在此处立即触发 Tap
                            // 为了菜单灵敏，我们直接触发 Tap，双击逻辑会覆盖它
                            onTap()
                            lastTapTime = endTime
                        }
                    }
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
