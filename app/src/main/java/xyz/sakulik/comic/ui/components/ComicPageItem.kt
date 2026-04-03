package xyz.sakulik.comic.ui.components

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage
import xyz.sakulik.comic.model.loader.ComicPageLoader
import xyz.sakulik.comic.ui.ZoomableImage

/**
 * 带有“自适应显存保护屏障”的单页中转站
 * 因为 ZoomableBox 会在此处发生作用，这里要起到承上启下的桥梁作用
 */
@Composable
fun ComicPageItem(
    loader: ComicPageLoader, 
    pageIndex: Int, 
    onScaleChanged: (Float) -> Unit, 
    onTap: () -> Unit
) {
    val context = LocalContext.current
    var pageData by remember { mutableStateOf<Any?>(null) }
    
    // 承载着这一块屏幕真正的渲染能力边界参数
    var containerSize by remember { mutableStateOf(Pair(0, 0)) }

    // 核心显存回收：当这一页面销毁或准备复用时，归还 Bitmap
    androidx.compose.runtime.DisposableEffect(pageIndex) {
        onDispose {
            loader.releasePageData(pageData)
            pageData = null
        }
    }

    // 当且仅当这一页面得到了分配给它的物理尺寸边界时，才呼叫深源层引擎释放出匹配的数据流
    LaunchedEffect(containerSize, pageIndex) {
        if (containerSize.first > 0 && containerSize.second > 0 && pageData == null) {
            pageData = loader.getPageData(
                pageIndex = pageIndex,
                width = containerSize.first,
                height = containerSize.second
            )
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            // 在此拦截并下放真实的画面尺寸给流引擎
            .onGloballyPositioned { coordinates ->
                if (containerSize.first == 0) {
                    containerSize = Pair(coordinates.size.width, coordinates.size.height)
                }
            },
        contentAlignment = Alignment.Center
    ) {
        when (val data = pageData) {
            null -> {
                CircularProgressIndicator()
            }
            is Bitmap -> {
                ZoomableImage(
                    bitmap = data,
                    onScaleChanged = onScaleChanged,
                    onTap = onTap
                )
            }
            is String -> {
                // 云端模式：直接使用 Coil 加载 URL
                AsyncImage(
                    model = data,
                    contentDescription = "云端漫画页",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit
                )
            }
        }
    }
}
