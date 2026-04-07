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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
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
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.unit.sp
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
            containerColor = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp),
            dragHandle = null // 自定义 Header
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .navigationBarsPadding()
            ) {
                // [沉浸式 Header 区域]
                Box(modifier = Modifier.fillMaxWidth().height(240.dp)) {
                    val model = if (comic.source == ComicSource.REMOTE) comic.coverCachePath else File(comic.coverCachePath ?: "")
                    
                    // 背景高斯模糊效果 (使用 Unbounded 确保铺满无白边)
                    AsyncImage(
                        model = model,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize().blur(24.dp, edgeTreatment = BlurredEdgeTreatment.Unbounded),
                        contentScale = ContentScale.Crop,
                        alpha = 0.3f
                    )
                    
                    Row(
                        modifier = Modifier.fillMaxSize().padding(24.dp).padding(top = 16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            modifier = Modifier.width(130.dp).aspectRatio(0.7f).clip(RoundedCornerShape(12.dp)),
                            shadowElevation = 12.dp
                        ) {
                            AsyncImage(model = model, contentDescription = null, contentScale = ContentScale.Crop)
                        }
                        
                        Spacer(Modifier.width(20.dp))
                        
                        Column(modifier = Modifier.weight(1f)) {
                            Text(text = comic.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            
                            if (!comic.authors.isNullOrBlank()) {
                                Text(text = comic.authors, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(vertical = 4.dp))
                            }
                            
                            if (!comic.year.isNullOrBlank()) {
                                Text(text = comic.year, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.outline)
                            }
                            
                            if (!comic.genres.isNullOrBlank()) {
                                AssistChip(
                                    onClick = {},
                                    label = { Text(comic.genres.split(",").firstOrNull()?.trim() ?: "") },
                                    modifier = Modifier.padding(top = 8.dp),
                                    border = null,
                                    colors = AssistChipDefaults.assistChipColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                                )
                            }
                        }
                    }
                }

                // [内容区域]
                Column(modifier = Modifier.padding(horizontal = 24.dp)) {
                    if (!comic.summary.isNullOrBlank()) {
                        Text(text = "剧情简介", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                        Text(
                            text = comic.summary,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 12.dp),
                            lineHeight = 22.sp
                        )
                    }
                    
                    HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp), color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
                    
                    // 快捷操作 Row
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        QuickActionItem(Icons.Default.PlayArrow, "阅读", MaterialTheme.colorScheme.primary) { onComicClick(comic); contextComic = null }
                        QuickActionItem(Icons.Default.Refresh, "重置", MaterialTheme.colorScheme.error) { viewModel.resetComicProgress(comic); contextComic = null }
                        QuickActionItem(Icons.Default.AutoAwesome, "刮削", MaterialTheme.colorScheme.secondary) { viewModel.autoScrape(comic); contextComic = null }
                        QuickActionItem(Icons.Default.LibraryAdd, "合集", MaterialTheme.colorScheme.tertiary) { showAddToCollectionDialog = comic; contextComic = null }
                        QuickActionItem(Icons.Default.Info, "详情", MaterialTheme.colorScheme.outline) { showMetadataDialog = comic; contextComic = null }
                    }

                    // 元数据深度管理区 (折叠)
                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.15f),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
                    ) {
                        Column {
                            ListItem(
                                headlineContent = { Text("元数据深度管理", style = MaterialTheme.typography.labelLarge) },
                                trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, modifier = Modifier.size(16.dp)) },
                                modifier = Modifier.clickable { showMetadataOptions = !showMetadataOptions },
                                colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                            )
                            
                            if (showMetadataOptions) {
                                Column(modifier = Modifier.padding(bottom = 8.dp)) {
                                    ListItem(
                                        headlineContent = { Text("手动编辑字段", style = MaterialTheme.typography.bodyMedium) },
                                        leadingContent = { Icon(Icons.Default.EditNote, null, modifier = Modifier.size(20.dp)) },
                                        modifier = Modifier.clickable { showEditorDialog = comic; contextComic = null },
                                        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                                    )
                                    ListItem(
                                        headlineContent = { Text("手动搜索元数据", style = MaterialTheme.typography.bodyMedium) },
                                        leadingContent = { Icon(Icons.AutoMirrored.Filled.ManageSearch, null, modifier = Modifier.size(20.dp)) },
                                        modifier = Modifier.clickable { onManualScrapeClick(comic); contextComic = null },
                                        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // 危险动作折叠区 (保留原有逻辑，但外观收敛)
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.1f),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth().clickable { showDeleteOptions = !showDeleteOptions }
                    ) {
                        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error)
                            Spacer(Modifier.width(12.dp))
                            Text(text = "高级管理选项", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.error)
                            Spacer(Modifier.weight(1f))
                            Icon(if (showDeleteOptions) Icons.Default.KeyboardArrowDown else Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.error)
                        }
                    }

                    if (showDeleteOptions) {
                        Column(modifier = Modifier.padding(top = 8.dp)) {
                            // 选项 1: 仅从书架移除 (安全操作)
                            ListItem(
                                headlineContent = { Text("仅从书架移除", color = MaterialTheme.colorScheme.error) },
                                supportingContent = { Text("保留本地文件，仅清除数据库记录", style = MaterialTheme.typography.labelSmall) },
                                leadingContent = { Icon(Icons.Default.FolderDelete, null, tint = MaterialTheme.colorScheme.error) },
                                modifier = Modifier.clickable { 
                                    viewModel.deleteComic(comic)
                                    contextComic = null
                                    showDeleteOptions = false
                                },
                                colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                            )
                            
                            // 选项 2: 彻底删除物理文件 (仅限本地漫画)
                            if (comic.source == ComicSource.LOCAL) {
                                ListItem(
                                    headlineContent = { Text("彻底删除物理文件", color = MaterialTheme.colorScheme.error) },
                                    supportingContent = { Text("警告：将从磁盘永久删除，不可恢复", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error.copy(alpha = 0.7f)) },
                                    leadingContent = { Icon(Icons.Default.Warning, null, tint = MaterialTheme.colorScheme.error) },
                                    modifier = Modifier.clickable { viewModel.deleteComicAndFile(comic); contextComic = null },
                                    colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(48.dp))
            }
        }
    }

    showMetadataDialog?.let { comic ->
        ComicMetadataDialog(comic = comic, onDismiss = { showMetadataDialog = null })
    }

    if (showApiDialog) {
        var inputUrl by remember { mutableStateOf("https://comix.sakulik.xyz/") }
        var inputToken by remember { mutableStateOf("") }
        
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
                    Spacer(Modifier.height(16.dp))
                    Text("访问令牌 (Token)：", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = inputToken,
                        onValueChange = { inputToken = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        placeholder = { Text("留空表示不鉴权") },
                        visualTransformation = PasswordVisualTransformation(),
                        leadingIcon = { Icon(Icons.Default.VpnKey, contentDescription = null) }
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (inputUrl.isNotBlank()) {
                        viewModel.saveCloudApi(inputUrl)
                        viewModel.saveCloudToken(inputToken)
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
                
                // [元数据 2.0]：来源徽标 (云端/本地)
                if (comic.source == ComicSource.REMOTE) {
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.85f),
                        shape = RoundedCornerShape(bottomEnd = 8.dp),
                        modifier = Modifier.align(Alignment.TopStart)
                    ) {
                        Icon(Icons.Default.CloudSync, null, modifier = Modifier.padding(4.dp).size(12.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                }

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
                style = MaterialTheme.typography.labelMedium,
                maxLines = 2,
                minLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 8.dp, end = 8.dp, bottom = 8.dp, top = 4.dp),
                fontWeight = FontWeight.Medium
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
                
                // [元数据 2.0]：来源徽标 (云端/本地)
                if (comic.source == ComicSource.REMOTE) {
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.85f),
                        shape = RoundedCornerShape(bottomEnd = 8.dp),
                        modifier = Modifier.align(Alignment.TopStart)
                    ) {
                        Icon(Icons.Default.CloudSync, null, modifier = Modifier.padding(4.dp).size(14.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                }

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
                modifier = Modifier.padding(start = 8.dp, end = 8.dp, bottom = 8.dp, top = 4.dp),
                fontWeight = FontWeight.Medium
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
