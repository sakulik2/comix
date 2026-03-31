package xyz.sakulik.comic.ui.bookshelf

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import xyz.sakulik.comic.model.db.ComicEntity
import xyz.sakulik.comic.model.db.ComicRegion
import xyz.sakulik.comic.model.db.ComicFormat
import xyz.sakulik.comic.viewmodel.AutoScrapeState
import xyz.sakulik.comic.viewmodel.BookshelfItem
import xyz.sakulik.comic.viewmodel.BookshelfViewModel
import xyz.sakulik.comic.viewmodel.SeriesGroupData
import xyz.sakulik.comic.viewmodel.SortOrder
import java.io.File
import androidx.activity.compose.BackHandler
import androidx.compose.ui.platform.LocalConfiguration

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun BookshelfScreen(
    viewModel: BookshelfViewModel,
    onComicClick: (ComicEntity) -> Unit,
    onSettingsClick: () -> Unit,
    onManualScrapeClick: (ComicEntity) -> Unit
) {
    val items by viewModel.groupedItems.collectAsState()
    val sortOrder by viewModel.sortOrder.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val scanProgress by viewModel.scanProgress.collectAsState()
    val selectedRegion by viewModel.selectedRegionFilter.collectAsState()
    val autoScrapeState by viewModel.autoScrapeState.collectAsState()

    var showSortMenu by remember { mutableStateOf(false) }
    var openedSeries by remember { mutableStateOf<SeriesGroupData?>(null) }
    var contextComic by remember { mutableStateOf<ComicEntity?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }

    // 状态反馈监听
    LaunchedEffect(autoScrapeState) {
        when (val state = autoScrapeState) {
            is AutoScrapeState.Done -> {
                snackbarHostState.showSnackbar("✅ 元数据已更新！", duration = SnackbarDuration.Short)
                viewModel.clearScrapeState()
            }
            is AutoScrapeState.Error -> {
                snackbarHostState.showSnackbar("❌ ${state.message}", duration = SnackbarDuration.Short)
                viewModel.clearScrapeState()
            }
            else -> {}
        }
    }

    val configuration = LocalConfiguration.current
    val adaptiveColumns = when {
        configuration.screenWidthDp < 600 -> 3
        configuration.screenWidthDp < 840 -> 5
        else -> 7
    }

    BackHandler(enabled = openedSeries != null) {
        openedSeries = null
    }

    Scaffold(
        topBar = {
            if (openedSeries == null) {
                Column {
                    TopAppBar(
                        title = { Text("我的漫库") },
                        actions = {
                            IconButton(onClick = onSettingsClick) {
                                Icon(Icons.Default.Settings, contentDescription = "设置")
                            }
                            IconButton(onClick = { showSortMenu = true }) {
                                Icon(Icons.AutoMirrored.Filled.List, contentDescription = "排序")
                            }
                            DropdownMenu(
                                expanded = showSortMenu,
                                onDismissRequest = { showSortMenu = false }
                            ) {
                                SortOrder.entries.forEach { order ->
                                    DropdownMenuItem(
                                        text = { Text(order.displayName) },
                                        onClick = {
                                            viewModel.onSortOrderChanged(order)
                                            showSortMenu = false
                                        }
                                    )
                                }
                            }
                        }
                    )
                    ScrollableTabRow(
                        selectedTabIndex = when(selectedRegion) {
                            null -> 0
                            ComicRegion.COMIC -> 1
                            ComicRegion.MANGA -> 2
                            ComicRegion.UNKNOWN -> 3
                        },
                        edgePadding = 16.dp,
                        containerColor = MaterialTheme.colorScheme.surface,
                        divider = {}
                    ) {
                        Tab(selected = selectedRegion == null, onClick = { viewModel.setRegionFilter(null) }, text = { Text("全部") })
                        Tab(selected = selectedRegion == ComicRegion.COMIC, onClick = { viewModel.setRegionFilter(ComicRegion.COMIC) }, text = { Text("美漫") })
                        Tab(selected = selectedRegion == ComicRegion.MANGA, onClick = { viewModel.setRegionFilter(ComicRegion.MANGA) }, text = { Text("日漫") })
                        Tab(selected = selectedRegion == ComicRegion.UNKNOWN, onClick = { viewModel.setRegionFilter(ComicRegion.UNKNOWN) }, text = { Text("未分类") })
                    }
                }
            } else {
                TopAppBar(
                    navigationIcon = {
                        IconButton(onClick = { openedSeries = null }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "后退")
                        }
                    },
                    title = { Text(openedSeries!!.seriesName, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                )
            }
        },
        floatingActionButton = {
            val launcher = androidx.activity.compose.rememberLauncherForActivityResult(
                androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree()
            ) { uri ->
                uri?.let { viewModel.addFolder(it) }
            }
            if (openedSeries == null) {
                FloatingActionButton(onClick = { launcher.launch(null) }) {
                    Icon(Icons.Default.Add, contentDescription = "扫描目录")
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            if (openedSeries == null) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = viewModel::onSearchQueryChanged,
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    placeholder = { Text("搜索您的收藏...") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    singleLine = true,
                    shape = RoundedCornerShape(100)
                )

                scanProgress?.let { progress ->
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text(text = progress, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                }

                if (items.isEmpty() && searchQuery.isEmpty()) {
                    // ======== 空状态引导 ========
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Default.Info, contentDescription = null, modifier = Modifier.size(80.dp), tint = MaterialTheme.colorScheme.outlineVariant)
                            Spacer(Modifier.height(16.dp))
                            Text("漫库目前是空的", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.outline)
                            Text("点击右下角按钮添加您的漫画文件夹", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.outline)
                        }
                    }
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(adaptiveColumns),
                        contentPadding = PaddingValues(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        items(items) { item ->
                            when(item) {
                                is BookshelfItem.SingleComic -> {
                                    ComicItem(
                                        comic = item.comic,
                                        isScrapingThis = autoScrapeState is AutoScrapeState.Loading && (autoScrapeState as AutoScrapeState.Loading).comicId == item.comic.id,
                                        onClick = { onComicClick(item.comic) },
                                        onLongClick = { contextComic = item.comic }
                                    )
                                }
                                is BookshelfItem.SeriesGroup -> {
                                    SeriesGroupItem(group = item.group, onClick = { openedSeries = item.group })
                                }
                            }
                        }
                    }
                }
            } else {
                // 系列详情展开逻辑
                val issuesList = openedSeries!!.books.filter { it.format == ComicFormat.ISSUE || it.format == ComicFormat.CHAPTER || (it.format == ComicFormat.UNKNOWN && it.volumeNumber == null) }.sortedBy { it.issueNumber ?: 9999f }
                val collectedList = openedSeries!!.books.filter { it.format == ComicFormat.HC || it.format == ComicFormat.TPB || it.format == ComicFormat.OMNIBUS || it.format == ComicFormat.TANKOBON || (it.format == ComicFormat.UNKNOWN && it.volumeNumber != null) }.sortedBy { it.volumeNumber ?: 9999f }

                LazyVerticalGrid(
                    columns = GridCells.Fixed(adaptiveColumns),
                    contentPadding = PaddingValues(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    if (collectedList.isNotEmpty()) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(8.dp)) {
                                Text("合订本 / 卷", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
                            }
                        }
                        items(collectedList) { book -> ComicSwimlaneItem(comic = book, onClick = { onComicClick(book) }) }
                    }
                    if (issuesList.isNotEmpty()) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(8.dp), modifier = Modifier.padding(top = 16.dp)) {
                                Text("单本 / 期", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
                            }
                        }
                        items(issuesList) { book -> ComicSwimlaneItem(comic = book, onClick = { onComicClick(book) }) }
                    }
                }
            }
        }
    }

    // 长按菜单
    contextComic?.let { comic ->
        ModalBottomSheet(
            onDismissRequest = { contextComic = null },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow, // 采用更轻盈的 MD3 容器底色
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp), // 标准 MD3 大圆角
            dragHandle = { BottomSheetDefaults.DragHandle() }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .navigationBarsPadding()
            ) {
                // --- 头部：标题与关闭 ---
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 8.dp, bottom = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = comic.title,
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = { contextComic = null }) {
                        Icon(Icons.Default.Close, contentDescription = "关闭", tint = MaterialTheme.colorScheme.outline)
                    }
                }

                HorizontalDivider()
                Spacer(Modifier.height(8.dp))

                // [新功能] 从头开始
                ListItem(
                    headlineContent = { Text("从头开始", style = MaterialTheme.typography.bodyLarge) },
                    supportingContent = { Text("清除当前阅读进度并返回第一页", style = MaterialTheme.typography.labelSmall) },
                    leadingContent = {
                        Surface(color = MaterialTheme.colorScheme.tertiaryContainer, shape = RoundedCornerShape(12.dp)) {
                            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.padding(8.dp).size(20.dp), tint = MaterialTheme.colorScheme.onTertiaryContainer)
                        }
                    },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .clickable { viewModel.resetComicProgress(comic); contextComic = null }
                )
                
                // 1. 自动刮削
                ListItem(
                    headlineContent = { Text("一键自动刮削", style = MaterialTheme.typography.bodyLarge) },
                    supportingContent = { Text("AI 智能匹配补全封面与详情", style = MaterialTheme.typography.labelSmall) },
                    leadingContent = {
                        val isScraping = autoScrapeState is AutoScrapeState.Loading && (autoScrapeState as AutoScrapeState.Loading).comicId == comic.id
                        if (isScraping) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.5.dp)
                        } else {
                            Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(12.dp)) {
                                Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.padding(8.dp).size(20.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
                            }
                        }
                    },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .clickable { viewModel.autoScrape(comic); contextComic = null }
                )

                // 2. 手动搜索
                ListItem(
                    headlineContent = { Text("搜索元数据", style = MaterialTheme.typography.bodyLarge) },
                    supportingContent = { Text("精确检索 ComicVine 数据库", style = MaterialTheme.typography.labelSmall) },
                    leadingContent = {
                        Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(12.dp)) {
                            Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.padding(8.dp).size(20.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
                        }
                    },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .clickable { onManualScrapeClick(comic); contextComic = null }
                )

                Spacer(modifier = Modifier.height(12.dp))

                // --- 危险功能组 ---
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.1f),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    ListItem(
                        headlineContent = { Text("从书架移除", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.error) },
                        supportingContent = { Text("仅清理记录，不会删除您的物理漫画文件", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onErrorContainer) },
                        leadingContent = {
                            Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                        },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .clickable { viewModel.deleteComic(comic); contextComic = null }
                    )
                }

                Spacer(Modifier.height(32.dp)) // 为系统导航栏留白
            }
        }
    }
}

@Composable
fun ComicSwimlaneItem(comic: ComicEntity, onClick: () -> Unit) {
    ElevatedCard(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick), shape = RoundedCornerShape(12.dp)) {
        Column {
            val model = if (comic.coverCachePath != null && File(comic.coverCachePath).exists()) File(comic.coverCachePath) else null
            Box(modifier = Modifier.aspectRatio(0.7f).fillMaxWidth()) {
                AsyncImage(model = model, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                if (comic.totalPages > 0 && comic.currentPage > 0) {
                    LinearProgressIndicator(progress = { comic.currentPage.toFloat() / comic.totalPages.toFloat() }, modifier = Modifier.fillMaxWidth().align(Alignment.BottomCenter).height(4.dp))
                }
            }
            Text(text = comic.title, style = MaterialTheme.typography.labelMedium, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(8.dp))
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun ComicItem(comic: ComicEntity, onClick: () -> Unit, onLongClick: () -> Unit, isScrapingThis: Boolean) {
    ElevatedCard(modifier = Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = onLongClick), shape = RoundedCornerShape(12.dp)) {
        Column {
            val model = if (comic.coverCachePath != null && File(comic.coverCachePath).exists()) File(comic.coverCachePath) else null
            Box(modifier = Modifier.aspectRatio(0.7f).fillMaxWidth()) {
                AsyncImage(model = model, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                if (comic.totalPages > 0 && comic.currentPage > 0) {
                    LinearProgressIndicator(progress = { comic.currentPage.toFloat() / comic.totalPages.toFloat() }, modifier = Modifier.fillMaxWidth().align(Alignment.BottomCenter).height(4.dp))
                }
                if (isScrapingThis) CircularProgressIndicator(modifier = Modifier.size(32.dp).align(Alignment.Center))
            }
            Text(text = comic.title, style = MaterialTheme.typography.labelLarge, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(8.dp))
        }
    }
}

@Composable
fun SeriesGroupItem(group: SeriesGroupData, onClick: () -> Unit) {
    ElevatedCard(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick), shape = RoundedCornerShape(12.dp)) {
        Column {
            val model = if (group.coverComic.coverCachePath != null && File(group.coverComic.coverCachePath).exists()) File(group.coverComic.coverCachePath) else null
            Box(modifier = Modifier.aspectRatio(0.7f).fillMaxWidth()) {
                Box(modifier = Modifier.fillMaxSize().padding(end = 4.dp, top = 4.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant))
                Box(modifier = Modifier.fillMaxSize().padding(start = 4.dp, bottom = 4.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
                    AsyncImage(model = model, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(topStart = 8.dp), modifier = Modifier.align(Alignment.BottomEnd)) {
                        Text(text = "${group.bookCount} 册", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                    }
                }
            }
            Text(text = group.seriesName, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(8.dp))
        }
    }
}
