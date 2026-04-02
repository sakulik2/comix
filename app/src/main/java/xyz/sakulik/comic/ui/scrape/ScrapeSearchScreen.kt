package xyz.sakulik.comic.ui.scrape

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import xyz.sakulik.comic.model.metadata.ScrapeSource
import xyz.sakulik.comic.model.metadata.ScrapeStrategy
import xyz.sakulik.comic.viewmodel.ScrapeStep
import xyz.sakulik.comic.viewmodel.ScrapeViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScrapeSearchScreen(
    comicId: Long,
    viewModel: ScrapeViewModel,
    onBack: () -> Unit
) {
    val searchQuery by viewModel.searchQuery.collectAsState()
    val isSearching by viewModel.isSearching.collectAsState()
    val searchResults by viewModel.searchResults.collectAsState()
    val strategy by viewModel.selectedStrategy.collectAsState()
    val currentStep by viewModel.currentStep.collectAsState()
    val selectedVolume by viewModel.selectedVolume.collectAsState()

    // 初始加载：拉取这条漫画的原始身世
    LaunchedEffect(comicId) {
        viewModel.loadAndInit(comicId)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { 
                    Text(
                        text = if (currentStep == ScrapeStep.VOLUME) "搜索系列" else (selectedVolume?.title ?: "选取分期"),
                        style = MaterialTheme.typography.titleLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    IconButton(onClick = {
                        if (currentStep == ScrapeStep.ISSUE) {
                            viewModel.goBackToVolumeSearch()
                        } else {
                            onBack()
                        }
                    }) {
                        Icon(
                            imageVector = if (currentStep == ScrapeStep.ISSUE) Icons.AutoMirrored.Filled.ArrowBack else Icons.Default.Close,
                            contentDescription = "返回"
                        )
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
        ) {
            // 只有在系列搜索阶段才显示搜索框和策略
            if (currentStep == ScrapeStep.VOLUME) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = viewModel::onQueryChanged,
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = { Text("输入关键词搜素系列...") },
                            trailingIcon = {
                                if (isSearching) {
                                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                                } else {
                                    IconButton(onClick = { viewModel.search() }) {
                                        Icon(Icons.Default.Search, contentDescription = "搜索")
                                    }
                                }
                            },
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp)
                        )
                        
                        Spacer(Modifier.height(12.dp))
                        
                        Text("搜索策略", style = MaterialTheme.typography.labelMedium)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            ScrapeStrategy.entries.forEach { s ->
                                FilterChip(
                                    selected = strategy == s,
                                    onClick = { viewModel.onStrategyChanged(s) },
                                    label = { Text(s.displayName) }
                                )
                            }
                        }
                    }
                }
            } else {
                // 如果在期号选择阶段，显示一个简单的提示栏
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.secondaryContainer
                ) {
                    Text(
                        text = "正在浏览系列「${selectedVolume?.title}」下的全部分期",
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
            }

            if (searchResults.isEmpty() && !isSearching) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        if (currentStep == ScrapeStep.VOLUME) "没有搜到系列，请尝试更换关键词" else "该系列下暂无匹配分期",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(if (currentStep == ScrapeStep.VOLUME) 150.dp else 120.dp),
                    contentPadding = PaddingValues(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(searchResults) { result ->
                        ElevatedCard(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    if (currentStep == ScrapeStep.VOLUME) {
                                        viewModel.selectVolume(result)
                                    } else {
                                        viewModel.applyMetadataBySelection(result) {
                                            onBack()
                                        }
                                    }
                                },
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Column {
                                Box(modifier = Modifier.aspectRatio(0.75f)) {
                                    AsyncImage(
                                        model = result.coverUrl,
                                        contentDescription = result.title,
                                        modifier = Modifier.fillMaxSize(),
                                        contentScale = ContentScale.Crop
                                    )
                                    
                                    // 仅在系列界面显示来源或期号汇总
                                    if (currentStep == ScrapeStep.VOLUME) {
                                        Surface(
                                            color = if (result.source == ScrapeSource.COMIC_VINE) Color(0xFFE53935) else Color(0xFF00B0FF),
                                            modifier = Modifier
                                                .align(Alignment.TopEnd)
                                                .padding(4.dp)
                                                .clip(RoundedCornerShape(4.dp))
                                        ) {
                                            Text(
                                                text = if (result.source == ScrapeSource.COMIC_VINE) "CV" else "BG",
                                                color = Color.White,
                                                style = MaterialTheme.typography.labelSmall,
                                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                    } else if (result.issueNumber != null) {
                                        // 在期号选择界面显示 #1 等角标
                                        Surface(
                                            color = MaterialTheme.colorScheme.primaryContainer,
                                            modifier = Modifier
                                                .align(Alignment.TopStart)
                                                .padding(4.dp)
                                                .clip(RoundedCornerShape(4.dp))
                                        ) {
                                            val iss = result.issueNumber
                                            Text(
                                                text = "#${if (iss % 1f == 0f) iss.toInt() else iss}",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                    }
                                }
                                Column(
                                    modifier = Modifier
                                        .padding(8.dp)
                                        .fillMaxWidth()
                                ) {
                                    val displayTitle = if (currentStep == ScrapeStep.VOLUME) {
                                        result.title
                                    } else {
                                        // 智能去重：如果分期名和系列名一样，且有副标题，优先显示副标题
                                        result.issueTitle ?: result.title
                                    }
                                    
                                    // 强制固定高度（2行），防止因文字长短导致的垂直偏移
                                    Text(
                                        text = displayTitle,
                                        style = MaterialTheme.typography.titleSmall,
                                        maxLines = 2,
                                        minLines = 2, // 确保即便是1行也占位2行
                                        overflow = TextOverflow.Ellipsis,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        lineHeight = androidx.compose.ui.unit.TextUnit.Unspecified // 保持默认
                                    )
                                    
                                    Spacer(Modifier.height(4.dp))
                                    
                                    val subInfo = if (currentStep == ScrapeStep.VOLUME) {
                                        result.year
                                    } else {
                                        result.issueNumber?.let { iss ->
                                            "#${if (iss % 1f == 0f) iss.toInt() else iss}"
                                        }
                                    }
                                    
                                    Text(
                                        text = subInfo ?: " ", // 用空格占位保持对齐
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1
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
