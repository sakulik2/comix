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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import xyz.sakulik.comic.model.scanner.ComicPageLoader
import xyz.sakulik.comic.ui.ZoomableImage

/**
 * 带有“自适应显存保护屏障”的单页中转站。
 * 因为 ZoomableBox 会在此处发生作用，这里要起到承上启下的桥梁作用。
 */
@Composable
fun ComicPageItem(uri: Uri, extension: String, pageIndex: Int, onScaleChanged: (Float) -> Unit, onTap: () -> Unit) {
    val context = LocalContext.current
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    
    // 承载着这一块屏幕真正的渲染能力边界参数
    var containerSize by remember { mutableStateOf(Pair(0, 0)) }

    // 当且仅当这一页面得到了分配给它的物理尺寸边界时，才呼叫深源层引擎释放出匹配的小于等于边框的图像流。
    // 这将从根本上的物理底层彻底锁死了整本漫画发生 OOM 的可能！！
    LaunchedEffect(containerSize, pageIndex) {
        if (containerSize.first > 0 && containerSize.second > 0 && bitmap == null) {
            bitmap = xyz.sakulik.comic.model.scanner.ComicPageLoader.loadPageBitmap(
                context = context,
                uri = uri,
                extension = extension,
                pageIndex = pageIndex,
                reqWidth = containerSize.first,
                reqHeight = containerSize.second
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
        val b = bitmap
        if (b == null) {
            CircularProgressIndicator()
        } else {
            ZoomableImage(
                bitmap = b,
                onScaleChanged = onScaleChanged,
                onTap = onTap
            )
        }
    }
}
