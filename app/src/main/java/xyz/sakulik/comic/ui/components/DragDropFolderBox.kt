package xyz.sakulik.comic.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import xyz.sakulik.comic.viewmodel.BookshelfItem

class DragDropState {
    var isDragging by mutableStateOf(false)
    var draggedItem by mutableStateOf<BookshelfItem?>(null)
    var dragStartPosition by mutableStateOf(Offset.Zero)
    var dragCurrentOffset by mutableStateOf(Offset.Zero)
    var hoverTargetKey by mutableStateOf<String?>(null)

    val itemBoundsMap = mutableMapOf<String, Rect>()
    val itemMap = mutableMapOf<String, BookshelfItem>()

    fun getDraggedCenter(): Offset {
        return dragStartPosition + dragCurrentOffset
    }
}

val LocalDragDropState = staticCompositionLocalOf { DragDropState() }

@Composable
fun DragDropFolderBox(
    onCombine: (BookshelfItem, BookshelfItem) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable (DragDropState) -> Unit
) {
    val state = remember { DragDropState() }
    var parentPositionInRoot by remember { mutableStateOf(Offset.Zero) }
    val density = LocalDensity.current

    CompositionLocalProvider(LocalDragDropState provides state) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .onGloballyPositioned { coordinates ->
                    parentPositionInRoot = coordinates.positionInRoot()
                }
                .pointerInput(Unit) {
                    detectDragGesturesAfterLongPress(
                        onDragStart = { localOffset ->
                            val rootOffset = parentPositionInRoot + localOffset
                            val hitKey = state.itemBoundsMap.entries.firstOrNull { (_, rect) ->
                                rect.contains(rootOffset)
                            }?.key

                            if (hitKey != null && state.itemMap.containsKey(hitKey)) {
                                state.draggedItem = state.itemMap[hitKey]
                                state.dragStartPosition = state.itemBoundsMap[hitKey]?.center ?: rootOffset
                                state.dragCurrentOffset = Offset.Zero
                                state.isDragging = true
                            }
                        },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            if (state.isDragging) {
                                state.dragCurrentOffset += dragAmount
                                val rootCenter = state.getDraggedCenter()

                                val currentKey = state.draggedItem?.let { getItemKey(it) }
                                val hoverEntry = state.itemBoundsMap.entries.firstOrNull { (key, rect) ->
                                    key != currentKey && rect.contains(rootCenter)
                                }

                                state.hoverTargetKey = hoverEntry?.key
                            }
                        },
                        onDragEnd = {
                            if (state.isDragging) {
                                val dragged = state.draggedItem
                                val targetKey = state.hoverTargetKey
                                val target = targetKey?.let { state.itemMap[it] }

                                if (dragged != null && target != null) {
                                    onCombine(dragged, target)
                                }
                            }
                            state.isDragging = false
                            state.draggedItem = null
                            state.hoverTargetKey = null
                        },
                        onDragCancel = {
                            state.isDragging = false
                            state.draggedItem = null
                            state.hoverTargetKey = null
                        }
                    )
                }
        ) {
            content(state)

            if (state.isDragging && state.draggedItem != null) {
                val rootCenter = state.getDraggedCenter()
                Box(
                    modifier = Modifier
                        .offset {
                            with(density) {
                                IntOffset(
                                    x = (rootCenter.x - parentPositionInRoot.x - 60.dp.toPx()).toInt(),
                                    y = (rootCenter.y - parentPositionInRoot.y - 80.dp.toPx()).toInt()
                                )
                            }
                        }
                        .size(120.dp, 160.dp)
                        .zIndex(100f)
                        .graphicsLayer {
                            scaleX = 1.08f
                            scaleY = 1.08f
                            shadowElevation = 16f
                        }
                ) {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.9f),
                        tonalElevation = 8.dp
                    ) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "拖拽归组...",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun Modifier.dragAndDropTarget(
    item: BookshelfItem,
    key: String = getItemKey(item)
): Modifier {
    val state = LocalDragDropState.current
    val isHovered = state.isDragging && state.hoverTargetKey == key
    val isBeingDragged = state.isDragging && getItemKey(state.draggedItem) == key

    val scale by animateFloatAsState(if (isHovered) 1.06f else 1.0f, label = "hoverScale")

    return this
        .onGloballyPositioned { coordinates ->
            state.itemBoundsMap[key] = coordinates.boundsInRoot()
            state.itemMap[key] = item
        }
        .scale(scale)
        .graphicsLayer {
            alpha = if (isBeingDragged) 0.4f else 1.0f
        }
        .then(
            if (isHovered) {
                Modifier.border(
                    width = 2.5.dp,
                    color = MaterialTheme.colorScheme.primary,
                    shape = RoundedCornerShape(16.dp)
                )
            } else Modifier
        )
}

fun getItemKey(item: BookshelfItem?): String {
    return when (item) {
        is BookshelfItem.SingleComic -> "comic_${item.comic.id}"
        is BookshelfItem.SeriesGroup -> "series_${item.group.seriesName}"
        is BookshelfItem.Collection -> "collection_${item.collection.collection.id}"
        null -> ""
    }
}
