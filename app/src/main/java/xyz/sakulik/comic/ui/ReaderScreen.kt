package xyz.sakulik.comic.ui

import android.app.Activity
import android.view.WindowManager
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import androidx.compose.ui.res.painterResource
import xyz.sakulik.comic.R
import xyz.sakulik.comic.model.loader.ComicPageLoader
import xyz.sakulik.comic.ui.components.ComicPageItem
import xyz.sakulik.comic.ui.components.WebtoonReader
import xyz.sakulik.comic.viewmodel.ReaderMode

/**
 * 漫画阅读器主界面
 * 支持全屏沉浸模式与多种阅读模式（单页、双页、长图）
 */
@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun ReaderScreen(
    loader: ComicPageLoader,
    readerMode: ReaderMode,
    pageCount: Int,
    comicTitle: String,
    initialPage: Int,
    isRtl: Boolean,
    isImmersive: Boolean,
    onPageChanged: (Int) -> Unit,
    onBack: () -> Unit,
    onScrapeClick: () -> Unit,
    onToggleRtl: () -> Unit,
    onToggleSharpen: () -> Unit,
    onToggleReaderMode: () -> Unit,
    onToggleImmersive: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    isSharpenEnabled: Boolean = false,
) {
    val context = LocalContext.current
    val window = (context as? Activity)?.window
    val coroutineScope = rememberCoroutineScope()

    // 设置屏幕常亮，防止阅读时自动关闭屏幕
    DisposableEffect(Unit) {
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    val config = androidx.compose.ui.platform.LocalConfiguration.current
    val isLandscape = config.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE

    // 横屏模式下，如果当前是翻页模式，默认采用双页逻辑以利用屏幕宽度
    // 为了不干扰用户手动选择，这里仅作逻辑映射建议
    val effectiveReaderMode = if (isLandscape && readerMode == ReaderMode.PAGER) {
        ReaderMode.DUAL_PAGE
    } else {
        readerMode
    }

    // 页码断存
    val savedPage = androidx.compose.runtime.saveable.rememberSaveable(
        saver = androidx.compose.runtime.saveable.Saver(
            save = { it.intValue },
            restore = { mutableIntStateOf(it) }
        )
    ) { mutableIntStateOf(initialPage) }

    val pagerPageCount = when (effectiveReaderMode) {
        ReaderMode.DUAL_PAGE -> (pageCount + 1) / 2
        else -> pageCount
    }

    val pagerState = rememberPagerState(
        initialPage = when (effectiveReaderMode) {
            ReaderMode.DUAL_PAGE -> savedPage.intValue / 2
            else -> savedPage.intValue
        },
        pageCount = { pagerPageCount }
    )
    
    // 同步 Pager 进度到断存点与外部
    LaunchedEffect(pagerState.currentPage, effectiveReaderMode) {
        val targetPage = when (effectiveReaderMode) {
            ReaderMode.DUAL_PAGE -> (pagerState.currentPage * 2).coerceAtMost(pageCount - 1)
            else -> pagerState.currentPage
        }
        if (savedPage.intValue != targetPage) {
            savedPage.intValue = targetPage
            onPageChanged(targetPage)
        }
    }

    // 如果为 ture 说明内部 ZoomImage 被拉大了，拦截翻页
    var isUserInteractionBlocked by remember { mutableStateOf(false) }

    val layoutDirection = if (isRtl) LayoutDirection.Rtl else LayoutDirection.Ltr

    Box(modifier = modifier
        .fillMaxSize()
        .background(Color.Black)) { 
        
        when (readerMode) {
            ReaderMode.PAGER -> {
                CompositionLocalProvider(LocalLayoutDirection provides layoutDirection) {
                    HorizontalPager(
                        state = pagerState,
                        modifier = Modifier.fillMaxSize(),
                        beyondViewportPageCount = 2,
                        userScrollEnabled = !isUserInteractionBlocked 
                    ) { page ->
                        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                            ComicPageItem(
                                loader = loader,
                                pageIndex = page,
                                onScaleChanged = { scale -> 
                                    isUserInteractionBlocked = scale > 1.05f 
                                },
                                onTap = { onToggleImmersive(!isImmersive) }
                            )
                        }
                    }
                }
            }
            ReaderMode.DUAL_PAGE -> {
                CompositionLocalProvider(LocalLayoutDirection provides layoutDirection) {
                    HorizontalPager(
                        state = pagerState,
                        modifier = Modifier.fillMaxSize(),
                        beyondViewportPageCount = 1,
                        userScrollEnabled = !isUserInteractionBlocked
                    ) { page ->
                        Row(modifier = Modifier.fillMaxSize()) {
                            val leftIdx = page * 2
                            val rightIdx = page * 2 + 1
                            
                            // RTL 逻辑：物理上的左边应该是逻辑上的后一页，右边是前一页
                            // 但 Compose HorizontalPager 已经反转了 Row 的顺序（通过 layoutDirection）
                            // 所以这里依然按顺序布局即可，Pager 会替我们排版
                            
                            Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                                ComicPageItem(
                                    loader = loader,
                                    pageIndex = leftIdx,
                                    onScaleChanged = { isUserInteractionBlocked = it > 1.05f },
                                    onTap = { onToggleImmersive(!isImmersive) }
                                )
                            }
                            if (rightIdx < pageCount) {
                                Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                                    ComicPageItem(
                                        loader = loader,
                                        pageIndex = rightIdx,
                                        onScaleChanged = { isUserInteractionBlocked = it > 1.05f },
                                        onTap = { onToggleImmersive(!isImmersive) }
                                    )
                                }
                            } else {
                                // 最后一页单数补白
                                Spacer(modifier = Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
            ReaderMode.WEBTOON -> {
                WebtoonReader(
                    loader = loader,
                    pageCount = pageCount,
                    initialPage = savedPage.intValue,
                    onPageChanged = { page ->
                        savedPage.intValue = page
                        onPageChanged(page)
                    },
                    onTap = { onToggleImmersive(!isImmersive) }
                )
            }
        }
        
        // 沉浸模式 - 顶部幽灵控制台
        AnimatedVisibility(
            visible = !isImmersive,
            enter = slideInVertically(initialOffsetY = { -it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { -it }) + fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter)
        ) {
            TopAppBar(
                title = { Text(comicTitle, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = onToggleSharpen) {
                        Icon(
                            painter = painterResource(R.drawable.ic_auto_awesome), 
                            contentDescription = if (isSharpenEnabled) "关闭增强" else "开启增强",
                            tint = if (isSharpenEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                        )
                    }
                    IconButton(onClick = onToggleReaderMode) {
                        Icon(
                            painter = if (readerMode == ReaderMode.PAGER) painterResource(R.drawable.ic_vertical_distribute) else painterResource(R.drawable.ic_view_carousel), 
                            contentDescription = "切换模式"
                        )
                    }
                    IconButton(onClick = onScrapeClick) {
                        Icon(Icons.Default.Star, contentDescription = "刮削")
                    }
                    Text("RTL", style = MaterialTheme.typography.labelSmall)
                    Switch(
                        checked = isRtl,
                        onCheckedChange = { onToggleRtl() },
                        modifier = Modifier.scale(0.8f).padding(horizontal = 4.dp)
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.90f)
                )
            )
        }

        // 页面跳转对话框
        var showJumpDialog by remember { mutableStateOf(false) }
        var jumpInput by remember { mutableStateOf("") }
        
        if (showJumpDialog) {
            AlertDialog(
                onDismissRequest = { showJumpDialog = false },
                title = { Text("跳转到页码") },
                text = {
                    OutlinedTextField(
                        value = jumpInput,
                        onValueChange = { if (it.all { char -> char.isDigit() }) jumpInput = it },
                        label = { Text("输入页码 (1 - $pageCount)") },
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                            keyboardType = androidx.compose.ui.text.input.KeyboardType.Number
                        )
                    )
                },
                confirmButton = {
                    Button(onClick = {
                        val target = jumpInput.toIntOrNull()?.minus(1)
                        if (target != null && target in 0 until pageCount) {
                            savedPage.intValue = target
                            if (readerMode == ReaderMode.PAGER) {
                                coroutineScope.launch { pagerState.animateScrollToPage(target) }
                            }
                            showJumpDialog = false
                        }
                    }) { Text("确定") }
                },
                dismissButton = {
                    TextButton(onClick = { showJumpDialog = false }) { Text("取消") }
                }
            )
        }

        // 沉浸模式 - 底部悬浮进度控制台 (Slim & Dynamic Design)
        AnimatedVisibility(
            visible = !isImmersive,
            enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(horizontal = 16.dp, vertical = 24.dp) 
        ) {
            Surface(
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                shape = androidx.compose.foundation.shape.CircleShape, // 圆润长条
                tonalElevation = 8.dp,
                shadowElevation = 12.dp,
                border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.15f)),
                modifier = Modifier
                    .fillMaxWidth(0.7f) // 宽度随屏幕自适应，最高 70%
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "${savedPage.intValue + 1} / $pageCount",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .clickable { 
                                jumpInput = (savedPage.intValue + 1).toString()
                                showJumpDialog = true 
                            }
                            .padding(end = 12.dp)
                    )
                    
                    Slider(
                        value = savedPage.intValue.toFloat(),
                        onValueChange = {
                            savedPage.intValue = it.toInt()
                        },
                        onValueChangeFinished = {
                            if (readerMode == ReaderMode.PAGER) {
                                coroutineScope.launch { pagerState.animateScrollToPage(savedPage.intValue) }
                            }
                        },
                        valueRange = 0f..(pageCount - 1).coerceAtLeast(1).toFloat(),
                        steps = (pageCount - 2).coerceAtLeast(0),
                        modifier = Modifier.weight(1f), // 进度条拉长
                        colors = SliderDefaults.colors(
                            thumbColor = MaterialTheme.colorScheme.primary,
                            activeTrackColor = MaterialTheme.colorScheme.primary,
                            inactiveTrackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                        )
                    )
                }
            }
        }
    }
}
