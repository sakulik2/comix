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
import xyz.sakulik.comic.model.loader.ReaderLayoutManager
import xyz.sakulik.comic.model.loader.RenderBlock
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
    onSetAsCover: (Int) -> Unit,
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

    // 辅助状态：布局管理器
    val layoutManager = remember(loader, pageCount, readerMode) { ReaderLayoutManager(loader) }
    var isLayoutReady by remember { mutableStateOf(false) }

    // 计算布局
    LaunchedEffect(loader, pageCount, effectiveReaderMode) {
        isLayoutReady = false
        layoutManager.computeLayout(pageCount, effectiveReaderMode)
        isLayoutReady = true
    }

    // 页码断存
    val savedPage = androidx.compose.runtime.saveable.rememberSaveable(
        saver = androidx.compose.runtime.saveable.Saver(
            save = { it.intValue },
            restore = { mutableIntStateOf(it) }
        )
    ) { mutableIntStateOf(initialPage) }

    val pagerPageCount = if (isLayoutReady) layoutManager.getBlockCount() else 0

    val pagerState = if (effectiveReaderMode != ReaderMode.WEBTOON) {
        rememberPagerState(
            initialPage = 0, // 初始先设为0，通过下面的 LaunchedEffect 跳转
            pageCount = { pagerPageCount }
        )
    } else null

    // 初始化跳转到断存点所在的 Block
    if (pagerState != null) {
        LaunchedEffect(isLayoutReady) {
            if (isLayoutReady) {
                val targetBlock = layoutManager.getBlockIndexForPage(savedPage.intValue)
                pagerState.scrollToPage(targetBlock)
            }
        }

        // 同步 Pager 进度到断存点与外部
        LaunchedEffect(pagerState.currentPage, isLayoutReady, effectiveReaderMode) {
            if (!isLayoutReady) return@LaunchedEffect
            val targetPage = layoutManager.getFirstPageForBlock(pagerState.currentPage)

            if (savedPage.intValue != targetPage) {
                savedPage.intValue = targetPage
                onPageChanged(targetPage)
            }
        }
    }

    // 如果为 ture 说明内部 ZoomImage 被拉大了，拦截翻页
    var isUserInteractionBlocked by remember { mutableStateOf(false) }

    // Webtoon 模式下由进度条/跳页对话框触发的滚动目标
    var webtoonScrollTarget by remember { mutableStateOf<Int?>(null) }

    val layoutDirection = if (isRtl) LayoutDirection.Rtl else LayoutDirection.Ltr

    val snackbarHostState = remember { SnackbarHostState() }

    Box(modifier = modifier
        .fillMaxSize()
        .background(Color.Black)) { 
        
        if (!isLayoutReady) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            }
        } else if (readerMode == ReaderMode.WEBTOON) {
            WebtoonReader(
                loader = loader,
                pageCount = pageCount,
                initialPage = savedPage.intValue,
                onPageChanged = { page ->
                    savedPage.intValue = page
                    onPageChanged(page)
                },
                onTap = { onToggleImmersive(!isImmersive) },
                scrollToPage = webtoonScrollTarget
            )
        } else {
            CompositionLocalProvider(LocalLayoutDirection provides layoutDirection) {
                HorizontalPager(
                    state = pagerState!!,
                    modifier = Modifier.fillMaxSize(),
                    beyondViewportPageCount = 1,
                    userScrollEnabled = !isUserInteractionBlocked
                ) { blockIndex ->
                    val block = layoutManager.getBlocks().getOrNull(blockIndex) ?: return@HorizontalPager
                    
                    // 统一手势容器：支持单双页作为一个整体进行缩放
                    xyz.sakulik.comic.ui.components.ZoomableBox(
                        modifier = Modifier.fillMaxSize(),
                        onScaleChanged = { isUserInteractionBlocked = it > 1.05f },
                        onTap = { onToggleImmersive(!isImmersive) }
                    ) {
                        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                            when (block) {
                                is RenderBlock.Single -> {
                                    ComicPageItem(
                                        loader = loader,
                                        pageIndex = block.pageIndex,
                                        onScaleChanged = {}, // 缩放由外层 Box 接管
                                        onTap = {},
                                        enableZoom = false // 禁用单页内部缩放
                                    )
                                }
                                is RenderBlock.Pair -> {
                                    Row(modifier = Modifier.fillMaxSize()) {
                                        Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                                            ComicPageItem(
                                                loader = loader,
                                                pageIndex = block.leftIndex,
                                                onScaleChanged = {},
                                                onTap = {},
                                                enableZoom = false
                                            )
                                        }
                                        Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                                            ComicPageItem(
                                                loader = loader,
                                                pageIndex = block.rightIndex,
                                                onScaleChanged = {},
                                                onTap = {},
                                                enableZoom = false
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
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
                    IconButton(onClick = onToggleReaderMode) {
                        Icon(
                            painter = if (readerMode == ReaderMode.PAGER) painterResource(R.drawable.ic_vertical_distribute) else painterResource(R.drawable.ic_view_carousel), 
                            contentDescription = "切换模式"
                        )
                    }

                    var showMenu by remember { mutableStateOf(false) }
                    Box {
                        IconButton(onClick = { showMenu = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "更多选项")
                        }
                        DropdownMenu(
                            expanded = showMenu,
                            onDismissRequest = { showMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("设为封面") },
                                leadingIcon = { Icon(Icons.Default.PhotoCamera, null) },
                                onClick = {
                                    showMenu = false
                                    onSetAsCover(savedPage.intValue)
                                    coroutineScope.launch {
                                        snackbarHostState.showSnackbar("封面已更新并同步元数据")
                                    }
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(if (isSharpenEnabled) "关闭画质增强" else "开启画质增强") },
                                leadingIcon = { Icon(painterResource(R.drawable.ic_auto_awesome), null, tint = if (isSharpenEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface) },
                                onClick = {
                                    showMenu = false
                                    onToggleSharpen()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("手动搜索重刮削") },
                                leadingIcon = { Icon(Icons.Default.Sync, null) },
                                onClick = {
                                    showMenu = false
                                    onScrapeClick()
                                }
                            )
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text("从右向左翻页 (RTL)") },
                                trailingIcon = {
                                    Switch(
                                        checked = isRtl,
                                        onCheckedChange = { onToggleRtl() },
                                        modifier = Modifier.scale(0.8f)
                                    )
                                },
                                onClick = { onToggleRtl() }
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.90f)
                )
            )
        }

        // 反馈提示
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 80.dp)
        )

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
                            if (effectiveReaderMode == ReaderMode.WEBTOON) {
                                webtoonScrollTarget = target
                            } else {
                                val blockIndex = layoutManager.getBlockIndexForPage(target)
                                pagerState?.let { coroutineScope.launch { it.animateScrollToPage(blockIndex) } }
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
                            if (effectiveReaderMode == ReaderMode.WEBTOON) {
                                webtoonScrollTarget = savedPage.intValue
                            } else {
                                val blockIndex = layoutManager.getBlockIndexForPage(savedPage.intValue)
                                pagerState?.let { coroutineScope.launch { it.animateScrollToPage(blockIndex) } }
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
