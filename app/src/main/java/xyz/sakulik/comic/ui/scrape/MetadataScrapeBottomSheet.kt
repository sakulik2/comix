package xyz.sakulik.comic.ui.scrape

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
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
import xyz.sakulik.comic.model.metadata.ScrapeSource
import xyz.sakulik.comic.model.metadata.ScrapeStrategy
import xyz.sakulik.comic.model.metadata.ScrapedComicInfo
import xyz.sakulik.comic.viewmodel.ScrapeViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MetadataScrapeBottomSheet(
    comic: ComicEntity,
    viewModel: ScrapeViewModel,
    onDismissRequest: () -> Unit,
    onApplySuccess: () -> Unit
) {
    val query by viewModel.searchQuery.collectAsState()
    val strategy by viewModel.selectedStrategy.collectAsState()
    val results by viewModel.searchResults.collectAsState()
    val isSearching by viewModel.isSearching.collectAsState()

    LaunchedEffect(comic) {
        viewModel.initSearch(comic)
    }

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        modifier = Modifier.fillMaxHeight(0.9f)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp)
        ) {
            Text("元数据刮削", style = MaterialTheme.typography.titleLarge)
            Spacer(modifier = Modifier.height(16.dp))

            // 搜索框
            OutlinedTextField(
                value = query,
                onValueChange = viewModel::onQueryChanged,
                label = { Text("提取关键字") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                trailingIcon = {
                    IconButton(onClick = { viewModel.search() }) {
                        Icon(Icons.Default.Search, contentDescription = "主动刮削搜索")
                    }
                }
            )
            Spacer(modifier = Modifier.height(12.dp))

            // 策略选择
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ScrapeStrategy.entries.forEach { st ->
                    FilterChip(
                        selected = strategy == st,
                        onClick = { viewModel.onStrategyChanged(st) },
                        label = { Text(st.displayName) }
                    )
                }
            }
            Spacer(modifier = Modifier.height(16.dp))

            // 列表区
            if (isSearching) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else if (results.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("暂无匹配数据", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(results) { item ->
                        ScrapingResultItem(
                            info = item,
                            onClick = {
                                viewModel.applyMetadata(comic, item) {
                                    onDismissRequest()
                                    onApplySuccess()
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun ScrapingResultItem(info: ScrapedComicInfo, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Row(modifier = Modifier.padding(12.dp)) {
            // 封面图
            AsyncImage(
                model = info.coverUrl,
                contentDescription = "Cover",
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(80.dp, 120.dp)
                    .clip(RoundedCornerShape(8.dp))
            )
            Spacer(modifier = Modifier.width(16.dp))

            // 元数据详情
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = info.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(4.dp))
                
                // 来源高警示 Badges
                val badgeColor = if (info.source == ScrapeSource.COMIC_VINE) Color(0xFFD32F2F) else Color(0xFF1976D2)
                val badgeText = if (info.source == ScrapeSource.COMIC_VINE) "ComicVine 数据库" else "Bangumi 番组计划"
                Surface(
                    color = badgeColor,
                    shape = RoundedCornerShape(4.dp)
                ) {
                    Text(
                        text = badgeText,
                        color = Color.White,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
                
                Spacer(modifier = Modifier.height(4.dp))
                info.summary?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}
