package xyz.sakulik.comic.ui

import android.graphics.Bitmap
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateCentroidSize
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateRotation
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import kotlinx.coroutines.launch

/**
 * 支持单指穿梭、以及突破天际双指手势缩放的多层触控拦截包裹视图组件。
 * 升级版：支持双击平滑动画。
 */
@Composable
fun ZoomableImage(
    bitmap: Bitmap,
    modifier: Modifier = Modifier,
    onScaleChanged: (Float) -> Unit = {}, // 暴露一个接口方便更高层的主翻页层截断滑动指令
    onTap: () -> Unit = {} // 点击回调，用于切换沉浸模式
) {
    // 【缩放与移动的灵魂】使用 Animatable 实现丝滑动画与即时手势同步
    val scale = remember { Animatable(1f) }
    val offset = remember { Animatable(Offset.Zero, Offset.VectorConverter) }
    val coroutineScope = rememberCoroutineScope()

    Box(
        modifier = modifier
            .fillMaxSize()
            // 1. 点击流控：负责单击切换 UI，双击【动画】缩放
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { onTap() },
                    onDoubleTap = { centroid ->
                        coroutineScope.launch {
                            if (scale.value > 1f) {
                                // 并行执行回弹动画
                                launch { scale.animateTo(1f, spring()) }
                                launch { offset.animateTo(Offset.Zero, spring()) }
                            } else {
                                // 放大到 2.5x 动画
                                launch { scale.animateTo(2.5f, spring()) }
                                // 计算点击中心平移（可选增强：暂时居中或保持原位）
                            }
                        }
                    }
                )
            }
            // 2. 变换流控：负责双指捏合与已放大的平移 (使用 snapTo 保持物理同步)
            .pointerInput(Unit) {
                awaitEachGesture {
                    var rotation = 0f
                    var zoom = 1f
                    var pan = Offset.Zero
                    var pastTouchSlop = false
                    val touchSlop = viewConfiguration.touchSlop
                    
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        val canceled = event.changes.any { it.isConsumed }
                        if (!canceled) {
                            val zoomChange = event.calculateZoom()
                            val rotationChange = event.calculateRotation()
                            val panChange = event.calculatePan()

                            if (!pastTouchSlop) {
                                zoom *= zoomChange
                                rotation += rotationChange
                                pan += panChange

                                val centroidSize = event.calculateCentroidSize(useCurrent = false)
                                val zoomMotion = kotlin.math.abs(1 - zoom) * centroidSize
                                val rotationMotion = kotlin.math.abs(rotation) * kotlin.math.PI.toFloat() * centroidSize / 180f
                                val panMotion = pan.getDistance()

                                if (zoomMotion > touchSlop || rotationMotion > touchSlop || panMotion > touchSlop) {
                                    pastTouchSlop = true
                                }
                            }

                            if (pastTouchSlop) {
                                val centroid = event.calculateCentroid(useCurrent = false)
                                if (zoomChange != 1f || rotationChange != 0f || panChange != Offset.Zero) {
                                    // 核心逻辑：计算新缩放，使用 snapTo 立即响应
                                    val newScale = (scale.value * zoomChange).coerceIn(1f, 4f)
                                    coroutineScope.launch {
                                        scale.snapTo(newScale)
                                        onScaleChanged(newScale)
                                    }

                                    val maxOffsetX = (size.width * (scale.value - 1)) / 2
                                    val maxOffsetY = (size.height * (scale.value - 1)) / 2
                                    
                                    // 【核心优化：边缘穿透】
                                    // 判断当前是否在左右边界上。
                                    // 如果用户尝试往左划(pan < 0)且已经在右边界(-max)上，或者尝试往右划且已经在左边界(max)上，
                                    // 则不消费这个事件，让外层 Pager 拿到它实现翻页。
                                    val isAtLeftEdge = offset.value.x >= maxOffsetX - 0.5f 
                                    val isAtRightEdge = offset.value.x <= -maxOffsetX + 0.5f
                                    val isPanningToNext = panChange.x < 0
                                    val isPanningToPrev = panChange.x > 0
                                    
                                    val shouldConsume = if (scale.value > 1.05f) {
                                        !( (isAtLeftEdge && isPanningToPrev) || (isAtRightEdge && isPanningToNext) )
                                    } else {
                                        zoomChange != 1f // 缩放本身还是得要消费的
                                    }

                                    if (shouldConsume) {
                                        event.changes.forEach { it.consume() }
                                    }

                                    if (scale.value > 1f) {
                                        val newOffset = Offset(
                                            x = (offset.value.x + panChange.x * scale.value).coerceIn(-maxOffsetX, maxOffsetX),
                                            y = (offset.value.y + panChange.y * scale.value).coerceIn(-maxOffsetY, maxOffsetY)
                                        )
                                        coroutineScope.launch {
                                            offset.snapTo(newOffset)
                                        }
                                    } else {
                                        coroutineScope.launch {
                                            offset.snapTo(Offset.Zero)
                                        }
                                    }
                                }
                            }
                        }
                    } while (!canceled && event.changes.any { it.pressed })
                }
            }
    ) {
        // 底层重渲染，交托了所有状态挂载
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = "阅读主页画布",
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = scale.value
                    scaleY = scale.value
                    translationX = offset.value.x
                    translationY = offset.value.y
                },
            contentScale = ContentScale.Fit
        )
    }
}
