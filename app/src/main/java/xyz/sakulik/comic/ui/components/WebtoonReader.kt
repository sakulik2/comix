package xyz.sakulik.comic.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import xyz.sakulik.comic.model.loader.ComicPageLoader

/**
 * 纵向卷轴（Webtoon）阅读引擎
 * 针对大图流优化的 LazyColumn 实现
 */
@Composable
fun WebtoonReader(
    loader: ComicPageLoader,
    pageCount: Int,
    initialPage: Int,
    onPageChanged: (Int) -> Unit,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
    scrollToPage: Int? = null
) {
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = initialPage)

    // 响应外部滚动指令（来自进度条/跳页对话框）
    LaunchedEffect(scrollToPage) {
        if (scrollToPage != null) {
            listState.scrollToItem(scrollToPage.coerceIn(0, (pageCount - 1).coerceAtLeast(0)))
        }
    }

    // 同步进度到外部
    LaunchedEffect(listState.firstVisibleItemIndex) {
        onPageChanged(listState.firstVisibleItemIndex)
    }

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize()
    ) {
        items(
            count = pageCount,
            key = { it }
        ) { index ->
            ComicPageItem(
                loader = loader,
                pageIndex = index,
                onScaleChanged = {}, // Webtoon 模式下通常不需要 Pager 的缩放拦截
                onTap = onTap
            )
            // 页面间隔，增加阅读感
            Spacer(modifier = Modifier.height(2.dp))
        }
    }
}
