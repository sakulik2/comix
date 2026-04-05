package xyz.sakulik.comic.ui.bookshelf

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
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
import xyz.sakulik.comic.model.db.ComicSource
import xyz.sakulik.comic.viewmodel.AutoScrapeState
import xyz.sakulik.comic.viewmodel.BookshelfItem
import xyz.sakulik.comic.viewmodel.BookshelfViewModel
import xyz.sakulik.comic.viewmodel.SeriesGroupData
import xyz.sakulik.comic.viewmodel.SortOrder
import java.io.File
import androidx.compose.animation.animateContentSize
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.res.painterResource
import xyz.sakulik.comic.R

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun BookshelfScreen(
    viewModel: BookshelfViewModel,
    onComicClick: (ComicEntity) -> Unit,
    onSeriesClick: (SeriesGroupData) -> Unit,
    onCollectionClick: (xyz.sakulik.comic.model.db.CollectionEntity) -> Unit,
    onSettingsClick: () -> Unit,
    onManualScrapeClick: (ComicEntity) -> Unit
) {
    val items by viewModel.groupedItems.collectAsState()
    val sortOrder by viewModel.sortOrder.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val scanProgress by viewModel.scanProgress.collectAsState()
    val selectedRegion by viewModel.selectedRegionFilter.collectAsState()
    val autoScrapeState by viewModel.autoScrapeState.collectAsState()
    val remoteEnabled by viewModel.remoteEnabled.collectAsState()
    val collections by viewModel.collections.collectAsState()

    var showSortMenu by remember { mutableStateOf(false) }
    var showAddMenu by remember { mutableStateOf(false) }
    var showApiDialog by remember { mutableStateOf(false) }
    var contextComic by remember { mutableStateOf<ComicEntity?>(null) }
    var showDeleteOptions by remember { mutableStateOf(false) }
    var showMetadataOptions by remember { mutableStateOf(false) }
    var showMetadataDialog by remember { mutableStateOf<ComicEntity?>(null) }
    var showAddToCollectionDialog by remember { mutableStateOf<ComicEntity?>(null) }
    var showCreateCollectionDialog by remember { mutableStateOf(false) }
    var showEditorDialog by remember { mutableStateOf<ComicEntity?>(null) }
    var showCollectionRenameDialog by remember { mutableStateOf<xyz.sakulik.comic.model.db.CollectionEntity?>(null) }

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

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = { Text("我的漫库") },
                    actions = {
                        IconButton(onClick = onSettingsClick) {
                            Icon(Icons.Default.Settings, contentDescription = "设置")
                        }
                        IconButton(onClick = { viewModel.scanAllFolders() }) {
                            Icon(Icons.Default.Refresh, contentDescription = "全局扫描同步")
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
        },
        floatingActionButton = {
            val launcher = androidx.activity.compose.rememberLauncherForActivityResult(
                androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree()
            ) { uri ->
                uri?.let { viewModel.addFolder(uri) }
            }
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                androidx.compose.animation.AnimatedVisibility(
                    visible = showAddMenu,
                    enter = androidx.compose.animation.expandVertically() + androidx.compose.animation.fadeIn(),
                    exit = androidx.compose.animation.shrinkVertically() + androidx.compose.animation.fadeOut()
                ) {
                    Column(
                        horizontalAlignment = Alignment.End,
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        if (remoteEnabled) {
                            ExtendedFloatingActionButton(
                                onClick = { 
                                    showAddMenu = false
                                    viewModel.syncRemoteLibrary() 
                                },
                                icon = { 
                                    val isLoading = autoScrapeState is AutoScrapeState.Loading && (autoScrapeState as AutoScrapeState.Loading).comicId == -1L
                                    if (isLoading) CircularProgressIndicator(modifier = Modifier.size(24.dp))
                                    else Icon(Icons.Default.Refresh, null) 
                                },
                                text = { 
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text("同步")
                                        Spacer(Modifier.width(8.dp))
                                        IconButton(
                                            onClick = { 
                                                showAddMenu = false
                                                showApiDialog = true 
                                            },
                                            modifier = Modifier.size(20.dp)
                                        ) {
                                            Icon(Icons.Default.Settings, null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
                                        }
                                    }
                                },
                                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        }
                        ExtendedFloatingActionButton(
                            onClick = { 
                                showAddMenu = false
                                launcher.launch(null) 
                            },
                            icon = { Icon(painterResource(R.drawable.ic_folder), null) },
                            text = { Text("本地") },
                            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                            contentColor = MaterialTheme.colorScheme.onTertiaryContainer
                        )
                    }
                }

                FloatingActionButton(
                    onClick = { showAddMenu = !showAddMenu },
                    containerColor = if (showAddMenu) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.primaryContainer
                ) {
                    Icon(
                        if (showAddMenu) Icons.Default.Close else Icons.Default.Add,
                        contentDescription = "切换添加模式"
                    )
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
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
                    // ======= 合集板块 (仅在未搜索时显示) =======
                    if (collections.isNotEmpty() && searchQuery.isEmpty()) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            Column(modifier = Modifier.padding(bottom = 8.dp)) {
                                Text(
                                    "我的合集",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(bottom = 12.dp)
                                )
                                LazyRow(
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    contentPadding = PaddingValues(horizontal = 4.dp)
                                ) {
                                    items(collections) { colWithComics ->
                                        CollectionSwimlaneItem(
                                            collection = colWithComics,
                                            onClick = { onCollectionClick(colWithComics.collection) },
                                            onLongClick = { showCollectionRenameDialog = colWithComics.collection }
                                        )
                                    }
                                }
                                HorizontalDivider(modifier = Modifier.padding(top = 16.dp, bottom = 8.dp), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f))
                            }
                        }
                    }

                    items(items) { item ->
                        when(item) {
                            is BookshelfItem.SingleComic -> {
                                ComicItem(
                                    item = item,
                                    isScrapingThis = autoScrapeState is AutoScrapeState.Loading && (autoScrapeState as AutoScrapeState.Loading).comicId == item.comic.id,
                                    onClick = { onComicClick(item.comic) },
                                    onLongClick = { contextComic = item.comic }
                                )
                            }
                            is BookshelfItem.SeriesGroup -> {
                                SeriesGroupItem(
                                    group = item.group, 
                                    onClick = { onSeriesClick(item.group) },
                                    onLongClick = { contextComic = item.group.coverComic }
                                )
                            }
                            is BookshelfItem.Collection -> {
                                // Collections are handled separately above, this should not happen
                            }
                        }
                    }
                }
            }
        }
    }

    contextComic?.let { comic ->
        ModalBottomSheet(
            onDismissRequest = { 
                contextComic = null 
                showDeleteOptions = false
            },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            dragHandle = { BottomSheetDefaults.DragHandle() }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .navigationBarsPadding()
            ) {
                // [优化：平滑颜色过渡]
                val dangerZoneColor by androidx.compose.animation.animateColorAsState(
                    targetValue = if (showDeleteOptions) 
                        MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.25f) 
                    else 
                        MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.1f),
                    label = "DangerZoneColor"
                )
                
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

                // 核心阅读操作 (恢复原样)
                ListItem(
                    headlineContent = { Text("从头开始", style = MaterialTheme.typography.bodyLarge) },
                    supportingContent = { Text("清除当前阅读进度并返回第一页", style = MaterialTheme.typography.labelSmall) },
                    leadingContent = {
                        Surface(color = MaterialTheme.colorScheme.tertiaryContainer, shape = RoundedCornerShape(12.dp)) {
                            Icon(Icons.Default.Refresh, null, modifier = Modifier.padding(8.dp).size(20.dp), tint = MaterialTheme.colorScheme.onTertiaryContainer)
                        }
                    },
                    modifier = Modifier.clip(RoundedCornerShape(16.dp)).clickable { viewModel.resetComicProgress(comic); contextComic = null },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                )
                
                ListItem(
                    headlineContent = { Text("一键自动刮削", style = MaterialTheme.typography.bodyLarge) },
                    supportingContent = { Text("AI 智能匹配补全封面与详情", style = MaterialTheme.typography.labelSmall) },
                    leadingContent = {
                        val isScraping = autoScrapeState is AutoScrapeState.Loading && (autoScrapeState as AutoScrapeState.Loading).comicId == comic.id
                        if (isScraping) CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                        else Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(12.dp)) {
                            Icon(painterResource(R.drawable.ic_auto_awesome), null, modifier = Modifier.padding(8.dp).size(20.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
                        }
                    },
                    modifier = Modifier.clip(RoundedCornerShape(16.dp)).clickable { viewModel.autoScrape(comic); contextComic = null },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                )

                ListItem(
                    headlineContent = { Text("加入合集", style = MaterialTheme.typography.bodyLarge) },
                    supportingContent = { Text("将此漫画分类到您的手动合集中", style = MaterialTheme.typography.labelSmall) },
                    leadingContent = {
                        Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(12.dp)) {
                            Icon(Icons.Default.LibraryAdd, null, modifier = Modifier.padding(8.dp).size(20.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
                        }
                    },
                    modifier = Modifier.clip(RoundedCornerShape(16.dp)).clickable { showAddToCollectionDialog = comic; contextComic = null },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                )

                Spacer(modifier = Modifier.height(8.dp))

                // [优化：折叠式元数据管理]
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.2f),
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier.fillMaxWidth().animateContentSize()
                ) {
                    Column {
                        ListItem(
                            headlineContent = { Text("元数据管理", style = MaterialTheme.typography.bodyLarge) },
                            supportingContent = { Text("查看详情、手动搜索或编辑字段", style = MaterialTheme.typography.labelSmall) },
                            leadingContent = { Icon(Icons.AutoMirrored.Filled.ManageSearch, null, tint = MaterialTheme.colorScheme.secondary) },
                            trailingContent = { Icon(if (showMetadataOptions) Icons.Default.KeyboardArrowDown else Icons.AutoMirrored.Filled.KeyboardArrowRight, null) },
                            modifier = Modifier.clickable { showMetadataOptions = !showMetadataOptions },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                        )
                        
                        if (showMetadataOptions) {
                            Column(Modifier.padding(start = 8.dp, end = 8.dp, bottom = 8.dp)) {
                                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.2f))
                                ListItem(
                                    headlineContent = { Text("查看详细元数据", style = MaterialTheme.typography.bodyMedium) },
                                    leadingContent = { Icon(Icons.Default.Info, null, modifier = Modifier.size(18.dp)) },
                                    modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable { showMetadataDialog = comic; contextComic = null },
                                    colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                                )
                                ListItem(
                                    headlineContent = { Text("搜索元数据", style = MaterialTheme.typography.bodyMedium) },
                                    leadingContent = { Icon(Icons.Default.Search, null, modifier = Modifier.size(18.dp)) },
                                    modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable { onManualScrapeClick(comic); contextComic = null },
                                    colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                                )
                                ListItem(
                                    headlineContent = { Text("手动编辑字段", style = MaterialTheme.typography.bodyMedium) },
                                    leadingContent = { Icon(Icons.Default.Edit, null, modifier = Modifier.size(18.dp)) },
                                    modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable { showEditorDialog = comic; contextComic = null },
                                    colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // [优化：优雅的折叠式危险区]
                Surface(
                    color = dangerZoneColor,
                    shape = RoundedCornerShape(24.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .animateContentSize(
                            animationSpec = androidx.compose.animation.core.spring(
                                dampingRatio = androidx.compose.animation.core.Spring.DampingRatioLowBouncy,
                                stiffness = androidx.compose.animation.core.Spring.StiffnessLow
                            )
                        )
                ) {
                    Column {
                        ListItem(
                            headlineContent = { Text(if (showDeleteOptions) "确认删除操作" else "移除与删除", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.error) },
                            supportingContent = { Text(if (showDeleteOptions) "请谨慎选择以下操作" else "点击展开高级删除选项", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onErrorContainer) },
                            leadingContent = {
                                Icon(
                                    if (showDeleteOptions) Icons.Default.KeyboardArrowDown else Icons.Default.Delete, 
                                    contentDescription = null, 
                                    tint = MaterialTheme.colorScheme.error
                                )
                            },
                            trailingContent = {
                                if (!showDeleteOptions) {
                                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.error.copy(alpha = 0.5f))
                                }
                            },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(20.dp))
                                .clickable { showDeleteOptions = !showDeleteOptions }
                        )

                        // [优化：移除冗余的 AnimatedVisibility，改用简单 if 配合 animateContentSize]
                        if (showDeleteOptions) {
                            Column(
                                modifier = Modifier
                                    .padding(horizontal = 12.dp)
                                    .padding(bottom = 12.dp)
                            ) {
                                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.error.copy(alpha = 0.2f))
                                
                                // 选项 1: 仅从书架移除
                                ListItem(
                                    headlineContent = { Text("仅从书架移除", style = MaterialTheme.typography.bodyMedium) },
                                    supportingContent = { Text("保留本地文件，仅清除索引", style = MaterialTheme.typography.labelSmall) },
                                    leadingContent = { Icon(Icons.Default.Book, null, modifier = Modifier.size(18.dp)) },
                                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(12.dp))
                                        .clickable { 
                                            viewModel.deleteComic(comic)
                                            contextComic = null
                                            showDeleteOptions = false
                                        }
                                )

                                // 选项 2: 彻底删除文件 (仅对本地文件有效)
                                if (comic.source == ComicSource.LOCAL) {
                                    ListItem(
                                        headlineContent = { Text("彻底删除物理文件", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) },
                                        supportingContent = { Text("警告：将从磁盘永久删除，不可恢复", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onErrorContainer) },
                                        leadingContent = { Icon(Icons.Default.Warning, null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error) },
                                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(12.dp))
                                            .clickable { 
                                                viewModel.deleteComicAndFile(comic)
                                                contextComic = null
                                                showDeleteOptions = false
                                            }
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(32.dp))
            }
        }
    }

    showMetadataDialog?.let { comic ->
        ComicMetadataDialog(comic = comic, onDismiss = { showMetadataDialog = null })
    }

    if (showApiDialog) {
        var inputUrl by remember { mutableStateOf("https://comix.sakulik.xyz/") }
        AlertDialog(
            onDismissRequest = { showApiDialog = false },
            title = { Text("添加远程 API 地址", style = MaterialTheme.typography.titleMedium) },
            text = {
                Column {
                    Text("请输入符合 Comix 规范的流媒体 API 基址：", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = inputUrl,
                        onValueChange = { inputUrl = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        placeholder = { Text("http://api..") },
                        leadingIcon = { Icon(painterResource(id = xyz.sakulik.comic.R.drawable.ic_link), contentDescription = null) }
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (inputUrl.isNotBlank()) {
                        viewModel.saveCloudApi(inputUrl)
                        showApiDialog = false
                    }
                }) {
                    Text("保存")
                }
            },
            dismissButton = {
                TextButton(onClick = { showApiDialog = false }) {
                    Text("取消")
                }
            },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = RoundedCornerShape(28.dp)
        )
    }

    // ======= 合集对话框调用 =======

    showAddToCollectionDialog?.let { comic ->
        AddToCollectionDialog(
            collections = collections,
            onDismiss = { showAddToCollectionDialog = null },
            onCollectionSelected = { coll ->
                viewModel.addComicToCollection(coll.id, comic.id)
                showAddToCollectionDialog = null
            },
            onCreateNewCollection = {
                showAddToCollectionDialog = null
                showCreateCollectionDialog = true
            }
        )
    }

    if (showCreateCollectionDialog) {
        CreateCollectionDialog(
            onDismiss = { showCreateCollectionDialog = false },
            onConfirm = { name ->
                viewModel.createCollection(name)
                showCreateCollectionDialog = false
            }
        )
    }

    showCollectionRenameDialog?.let { collection ->
        RenameCollectionDialog(
            currentName = collection.name,
            onDismiss = { showCollectionRenameDialog = null },
            onConfirm = { newName ->
                viewModel.renameCollection(collection.id, newName)
                showCollectionRenameDialog = null
            },
            onDelete = {
                viewModel.deleteCollection(collection)
                showCollectionRenameDialog = null
            }
        )
    }

    showEditorDialog?.let { comic ->
        ComicEditorDialog(
            comic = comic,
            onDismiss = { showEditorDialog = null },
            onSave = { updatedComic ->
                viewModel.updateComicMetadata(updatedComic)
                showEditorDialog = null
            }
        )
    }
}

@Composable
fun QuickActionItem(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, color: Color, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(8.dp)
    ) {
        Surface(
            color = color.copy(alpha = 0.15f),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.size(56.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = color, modifier = Modifier.size(24.dp))
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface)
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun ComicSwimlaneItem(
    displayTitle: String,
    displayBadge: String?,
    comic: ComicEntity, 
    onClick: () -> Unit, 
    onLongClick: () -> Unit
) {
    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            ), 
        shape = RoundedCornerShape(12.dp)
    ) {
        Column {
            val model = if (comic.source == ComicSource.REMOTE) {
                comic.coverCachePath
            } else if (comic.coverCachePath != null && File(comic.coverCachePath).exists()) {
                File(comic.coverCachePath)
            } else {
                null
            }
            Box(modifier = Modifier.aspectRatio(0.7f).fillMaxWidth()) {
                AsyncImage(model = model, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                
                if (displayBadge != null) {
                    Surface(
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.8f),
                        shape = RoundedCornerShape(bottomStart = 8.dp),
                        modifier = Modifier.align(Alignment.TopEnd)
                    ) {
                        Text(text = displayBadge, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp), color = MaterialTheme.colorScheme.onSurface)
                    }
                }

                if (comic.totalPages > 0 && comic.currentPage > 0) {
                    LinearProgressIndicator(progress = { comic.currentPage.toFloat() / comic.totalPages.toFloat() }, modifier = Modifier.fillMaxWidth().align(Alignment.BottomCenter).height(4.dp))
                }
            }
            Text(
                text = displayTitle,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 2,
                minLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(8.dp)
            )
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun ComicItem(
    item: BookshelfItem.SingleComic,
    onClick: () -> Unit, 
    onLongClick: () -> Unit, 
    isScrapingThis: Boolean
) {
    val comic = item.comic
    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick), 
        shape = RoundedCornerShape(12.dp)
    ) {
        Column {
            val model = if (comic.source == ComicSource.REMOTE) {
                comic.coverCachePath
            } else if (comic.coverCachePath != null && File(comic.coverCachePath).exists()) {
                File(comic.coverCachePath)
            } else {
                null
            }
            Box(modifier = Modifier.aspectRatio(0.7f).fillMaxWidth()) {
                AsyncImage(model = model, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                
                if (item.displayBadge != null) {
                    Surface(
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.8f),
                        shape = RoundedCornerShape(bottomStart = 8.dp),
                        modifier = Modifier.align(Alignment.TopEnd)
                    ) {
                        Text(text = item.displayBadge, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp), color = MaterialTheme.colorScheme.onSurface)
                    }
                }

                if (comic.totalPages > 0 && comic.currentPage > 0) {
                    LinearProgressIndicator(progress = { comic.currentPage.toFloat() / comic.totalPages.toFloat() }, modifier = Modifier.fillMaxWidth().align(Alignment.BottomCenter).height(4.dp))
                }
                if (isScrapingThis) CircularProgressIndicator(modifier = Modifier.size(32.dp).align(Alignment.Center))
            }
            
            Text(
                text = item.displayTitle, 
                style = MaterialTheme.typography.labelLarge, 
                maxLines = 2, 
                minLines = 2, 
                overflow = TextOverflow.Ellipsis, 
                modifier = Modifier.padding(8.dp)
            )
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun SeriesGroupItem(group: SeriesGroupData, onClick: () -> Unit, onLongClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            )
    ) {
        Card(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).aspectRatio(0.7f).offset(y = (-8).dp),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
        ) {}
        Card(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp).aspectRatio(0.7f).offset(y = (-4).dp),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {}
        
        ElevatedCard(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
            Column {
                val model = if (group.coverComic.source == ComicSource.REMOTE) {
                    group.coverComic.coverCachePath
                } else if (group.coverComic.coverCachePath != null && File(group.coverComic.coverCachePath).exists()) {
                    File(group.coverComic.coverCachePath)
                } else {
                    null
                }
                Box(modifier = Modifier.aspectRatio(0.7f).fillMaxWidth()) {
                    AsyncImage(model = model, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    
                    if (group.displayBadge != null) {
                        Surface(
                            color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.9f),
                            shape = RoundedCornerShape(bottomStart = 8.dp),
                            modifier = Modifier.align(Alignment.TopEnd)
                        ) {
                            Text(
                                text = group.displayBadge, 
                                style = MaterialTheme.typography.labelSmall, 
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp), 
                                color = MaterialTheme.colorScheme.onSecondary
                            )
                        }
                    }

                    Surface(color = MaterialTheme.colorScheme.primary, shape = RoundedCornerShape(topStart = 8.dp), modifier = Modifier.align(Alignment.BottomEnd)) {
                        Text(text = "${group.bookCount} 册", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp), color = MaterialTheme.colorScheme.onPrimary)
                    }
                }
                Text(text = group.displayTitle, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(8.dp))
            }
        }
    }
}

@Composable
fun SeriesHeader(group: SeriesGroupData) {
    val sample = group.books.firstOrNull { it.summary != null } ?: group.coverComic
    
    val yearText = if (group.books.any { it.year != null }) {
        val years = group.books.mapNotNull { it.year }.distinct().sorted()
        if (years.size > 1) "${years.first()} - ${years.last()}" else years.first()
    } else null

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.Top
    ) {
        val model = if (group.coverComic.source == ComicSource.REMOTE) {
            group.coverComic.coverCachePath
        } else if (group.coverComic.coverCachePath != null && File(group.coverComic.coverCachePath).exists()) {
            File(group.coverComic.coverCachePath)
        } else {
            null
        }
        AsyncImage(
            model = model, 
            contentDescription = null, 
            modifier = Modifier
                .width(110.dp)
                .aspectRatio(0.7f)
                .clip(RoundedCornerShape(8.dp)), 
            contentScale = ContentScale.Crop
        )
        Spacer(Modifier.width(20.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = group.seriesName,
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Bold
            )
            
            val metaInfo = listOfNotNull(
                sample.publisher,
                yearText
            ).joinToString(" • ")
            
            if (metaInfo.isNotBlank()) {
                Text(
                    text = metaInfo,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(vertical = 4.dp)
                )
            }

            if (!group.disambiguation.isNullOrBlank()) {
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f),
                    shape = RoundedCornerShape(4.dp),
                    modifier = Modifier.padding(vertical = 4.dp)
                ) {
                    Text(
                        text = group.disambiguation, 
                        style = MaterialTheme.typography.labelSmall, 
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp), 
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
            }

            Spacer(Modifier.height(8.dp))
            
            Text(
                text = sample.summary ?: "暂无剧情简介", 
                style = MaterialTheme.typography.bodySmall, 
                maxLines = 10,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun ComicMetadataDialog(comic: ComicEntity, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(comic.remark ?: comic.title, style = MaterialTheme.typography.titleLarge) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (comic.remark != null) {
                    MetadataItem("备注名", comic.remark)
                }
                MetadataItem("原始标题", comic.title)
                MetadataItem("系列", comic.seriesName)
                MetadataItem("出版年份", comic.year)
                MetadataItem("出版社", comic.publisher)
                MetadataItem("创作者", comic.authors)
                MetadataItem("类型", comic.genres)
                MetadataItem("分级", comic.rating?.toString())
                MetadataItem("页数", "${comic.totalPages}P")
                MetadataItem("路径", comic.location)
                
                if (!comic.summary.isNullOrBlank()) {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                    Text("剧情简介", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    Text(comic.summary, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("确定") }
        },
        shape = RoundedCornerShape(28.dp),
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
    )
}

@Composable
fun ComicEditorDialog(
    comic: ComicEntity,
    onDismiss: () -> Unit,
    onSave: (ComicEntity) -> Unit
) {
    var remark by remember { mutableStateOf(comic.remark ?: "") }
    var title by remember { mutableStateOf(comic.title) }
    var series by remember { mutableStateOf(comic.seriesName) }
    var authors by remember { mutableStateOf(comic.authors ?: "") }
    var year by remember { mutableStateOf(comic.year ?: "") }
    var summary by remember { mutableStateOf(comic.summary ?: "") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("编辑详细信息") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(painterResource(R.drawable.ic_auto_awesome), null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                    TextButton(onClick = {
                        val cleaned = xyz.sakulik.comic.model.metadata.FilenameCleaner.clean(comic.title)
                        title = cleaned
                    }) { Text("从文件名提取标题", style = MaterialTheme.typography.labelSmall) }
                }

                OutlinedTextField(value = remark, onValueChange = { remark = it }, label = { Text("书架备注名") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("原始标题") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = series, onValueChange = { series = it }, label = { Text("所属系列") }, modifier = Modifier.fillMaxWidth())
                
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = year, onValueChange = { year = it }, label = { Text("年份") }, modifier = Modifier.weight(1f))
                    OutlinedTextField(value = authors, onValueChange = { authors = it }, label = { Text("作者") }, modifier = Modifier.weight(2f))
                }

                OutlinedTextField(value = summary, onValueChange = { summary = it }, label = { Text("剧情简介") }, modifier = Modifier.fillMaxWidth(), minLines = 3)
            }
        },
        confirmButton = {
            TextButton(onClick = { 
                onSave(comic.copy(
                    remark = remark.takeIf { it.isNotBlank() }, 
                    title = title, 
                    seriesName = series,
                    authors = authors.takeIf { it.isNotBlank() },
                    year = year.takeIf { it.isNotBlank() },
                    summary = summary.takeIf { it.isNotBlank() }
                )) 
            }) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

@Composable
private fun MetadataItem(label: String, value: String?) {
    if (value.isNullOrBlank()) return
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
        Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
    }
}

// ======= 合集管理相关组件 (Phase 3) =======

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun CollectionSwimlaneItem(
    collection: xyz.sakulik.comic.model.db.CollectionWithComics,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            )
            .width(160.dp),
        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.4f)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            val coverComic = collection.comics.firstOrNull { it.id == collection.collection.coverComicId } 
                ?: collection.comics.firstOrNull()
            
            val model = when {
                coverComic?.source == ComicSource.REMOTE -> coverComic.coverCachePath
                coverComic?.coverCachePath != null && File(coverComic.coverCachePath).exists() -> File(coverComic.coverCachePath)
                else -> null
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            ) {
                if (model != null) {
                    AsyncImage(
                        model = model,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Icon(
                        Icons.Default.CollectionsBookmark,
                        contentDescription = null,
                        modifier = Modifier.align(Alignment.Center).size(32.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    )
                }
                
                Surface(
                    color = MaterialTheme.colorScheme.primary,
                    shape = RoundedCornerShape(topStart = 8.dp),
                    modifier = Modifier.align(Alignment.BottomEnd)
                ) {
                    Text(
                        "${collection.comics.size} 册",
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                }
            }
            
            Spacer(Modifier.height(8.dp))
            Text(
                text = collection.collection.name,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@Composable
fun AddToCollectionDialog(
    collections: List<xyz.sakulik.comic.model.db.CollectionWithComics>,
    onDismiss: () -> Unit,
    onCollectionSelected: (xyz.sakulik.comic.model.db.CollectionEntity) -> Unit,
    onCreateNewCollection: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("加入合集") },
        text = {
            if (collections.isEmpty()) {
                Text("您还没有创建任何合集")
            } else {
                LazyColumn(modifier = Modifier.heightIn(max = 300.dp)) {
                    items(collections) { item ->
                        ListItem(
                            headlineContent = { Text(item.collection.name) },
                            leadingContent = { Icon(Icons.Default.Folder, null) },
                            modifier = Modifier.clickable { onCollectionSelected(item.collection) }
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onCreateNewCollection) {
                Text("新建合集")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        },
        shape = RoundedCornerShape(28.dp)
    )
}

@Composable
fun CreateCollectionDialog(
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新建合集") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("合集名称") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(
                onClick = { if (name.isNotBlank()) onConfirm(name) },
                enabled = name.isNotBlank()
            ) {
                Text("创建")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        },
        shape = RoundedCornerShape(28.dp)
    )
}

@Composable
fun RenameCollectionDialog(
    currentName: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
    onDelete: () -> Unit
) {
    var name by remember { mutableStateOf(currentName) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("删除合集", color = MaterialTheme.colorScheme.error) },
            text = { Text("确定要删除合集《$currentName》吗？这不会删除合集内的漫画文件。") },
            confirmButton = {
                TextButton(onClick = onDelete) { Text("确认删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text("取消") }
            }
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("管理合集") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("合集名称") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(
                onClick = { if (name.isNotBlank()) onConfirm(name) },
                enabled = name.isNotBlank() && name != currentName
            ) {
                Text("重命名")
            }
        },
        dismissButton = {
            TextButton(onClick = { showDeleteConfirm = true }) {
                Text("删除合集", color = MaterialTheme.colorScheme.error)
            }
        }
    )
}
