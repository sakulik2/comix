package xyz.sakulik.comic.ui

import android.net.Uri
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import kotlinx.coroutines.launch
import xyz.sakulik.comic.model.loader.ComicPageLoader
import xyz.sakulik.comic.ui.components.ComicPageItem

/**
 * 【重工特化】Edge-to-Edge 全面屏与智能预加载交互式面板
 */
@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun ReaderScreen(
    loader: ComicPageLoader,
    pageCount: Int,
    comicTitle: String,
    initialPage: Int,
    isRtl: Boolean,
    onPageChanged: (Int) -> Unit,
    onBack: () -> Unit,
    onScrapeClick: () -> Unit,
    onToggleRtl: () -> Unit,
    modifier: Modifier = Modifier
) {
    // 【企业级防毁灭装甲】 使用 Saver 配合 rememberSaveable，在 Android 强行因内存不足杀掉进程、或者发生横竖屏轮转时，死守最后一次页码断点！
    val savedPage = androidx.compose.runtime.saveable.rememberSaveable(
        saver = androidx.compose.runtime.saveable.Saver<androidx.compose.runtime.MutableIntState, Int>(
            save = { it.intValue },
            restore = { androidx.compose.runtime.mutableIntStateOf(it) }
        )
    ) { androidx.compose.runtime.mutableIntStateOf(initialPage) }

    val pagerState = rememberPagerState(
        initialPage = savedPage.intValue,
        pageCount = { pageCount }
    )
    
    // 【深度预加载】 将缓存视野扩展至前后各 2 页，实现极速翻页无感加载
    val beyondViewportPageCount = 2

    LaunchedEffect(pagerState.currentPage) {
        savedPage.intValue = pagerState.currentPage // 实时更新铁甲存护点
        onPageChanged(pagerState.currentPage)
    }

    // 沉浸态与边缘交互流控中心
    var isImmersiveMode by remember { mutableStateOf(false) }
    // 如果为 ture 说明内部 ZoomImage 被拉大了，必须死死锁住 Pager 的左右防线决不让其跟滑！
    var isUserInteractionBlocked by remember { mutableStateOf(false) }

    val layoutDirection = if (isRtl) LayoutDirection.Rtl else LayoutDirection.Ltr
    val coroutineScope = rememberCoroutineScope()

    Box(modifier = modifier
        .fillMaxSize()
        .background(Color.Black)) { // 画布底色深渊黑
        
        // Pager 翻转引擎源控制
        CompositionLocalProvider(LocalLayoutDirection provides layoutDirection) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
                // ==================== [核心科技：预加载注入] ==================== //
                beyondViewportPageCount = beyondViewportPageCount,
                // ==================== [核心科技：防止连带拖拽] ==================== //
                userScrollEnabled = !isUserInteractionBlocked 
            ) { page ->
                
                // 子项目剥离方向修正：无论往哪翻，画册原样不能被镜像
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    ComicPageItem(
                        loader = loader,
                        pageIndex = page,
                        // 承接深层组件冒泡上来的放大指令，稍微拉远一点 1.05 就认为是进入看图态
                        onScaleChanged = { scale -> 
                            isUserInteractionBlocked = scale > 1.05f 
                        },
                        onTap = {
                            isImmersiveMode = !isImmersiveMode
                        }
                    )
                }
            }
        }
        
        // 沉浸模式 - 顶部幽灵控制台 (带有刮削、方向与返回)
        AnimatedVisibility(
            visible = !isImmersiveMode,
            enter = slideInVertically(initialOffsetY = { -it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { -it }) + fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter)
        ) {
            TopAppBar(
                title = { Text(comicTitle, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回", tint = MaterialTheme.colorScheme.onSurface)
                    }
                },
                actions = {
                    IconButton(onClick = onScrapeClick) {
                        Icon(Icons.Default.Star, contentDescription = "刮削", tint = MaterialTheme.colorScheme.onSurface)
                    }
                    Text("RTL", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
                    Switch(
                        checked = isRtl,
                        onCheckedChange = { onToggleRtl() },
                        modifier = Modifier.padding(horizontal = 8.dp)
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f) // 毛玻璃拟态
                )
            )
        }

        // 沉浸模式 - 底部 Slider 跳转飞行舱
        AnimatedVisibility(
            visible = !isImmersiveMode,
            enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            Surface(
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "当前驻留档: ${pagerState.currentPage + 1} / $pageCount 页",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.align(Alignment.CenterHorizontally)
                    )
                    Slider(
                        value = pagerState.currentPage.toFloat(),
                        onValueChange = {
                            coroutineScope.launch {
                                pagerState.scrollToPage(it.toInt())
                            }
                        },
                        valueRange = 0f..(pageCount - 1).coerceAtLeast(1).toFloat(),
                        steps = (pageCount - 2).coerceAtLeast(0) // 防止步数运算溢出负数挂逼
                    )
                }
            }
        }
    }
}
