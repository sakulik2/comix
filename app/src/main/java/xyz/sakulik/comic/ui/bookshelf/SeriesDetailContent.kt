package xyz.sakulik.comic.ui.bookshelf

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import xyz.sakulik.comic.viewmodel.SeriesGroupData
import xyz.sakulik.comic.model.db.ComicEntity
import xyz.sakulik.comic.model.db.ComicFormat

@Composable
fun SeriesDetailContent(
    series: SeriesGroupData,
    adaptiveColumns: Int,
    onComicClick: (ComicEntity) -> Unit,
    onLongClick: (ComicEntity) -> Unit,
    modifier: Modifier = Modifier
) {
    val issuesList = series.books.filter { it.format == ComicFormat.ISSUE || it.format == ComicFormat.CHAPTER || (it.format == ComicFormat.UNKNOWN && it.volumeNumber == null) }.sortedBy { it.issueNumber ?: 9999f }
    val collectedList = series.books.filter { it.format == ComicFormat.HC || it.format == ComicFormat.TPB || it.format == ComicFormat.OMNIBUS || it.format == ComicFormat.TANKOBON || (it.format == ComicFormat.UNKNOWN && it.volumeNumber != null) }.sortedBy { it.volumeNumber ?: 9999f }
    val isSingleCount = series.bookCount == 1

    // 辅助清洗：系列内展示仅需去除 HC/SC 前缀
    val formatsToStrip = listOf("HC - ", "SC - ", "TPB - ", "OHC - ", "Absolute ")

    LazyVerticalGrid(
        columns = GridCells.Fixed(adaptiveColumns),
        contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = modifier.fillMaxSize()
    ) {
        // 1. 系列总览 Header
        item(span = { GridItemSpan(maxLineSpan) }) {
            SeriesHeader(group = series)
        }

        if (collectedList.isNotEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Text("合订本 / 卷", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp, bottom = 4.dp), color = MaterialTheme.colorScheme.primary)
            }
            items(collectedList) { book -> 
                var displayT = book.issueTitle ?: book.title
                formatsToStrip.forEach { if (displayT.startsWith(it, true)) displayT = displayT.substring(it.length).trim() }
                if (isSingleCount && !book.seriesName.isNullOrBlank()) displayT = book.seriesName

                val badge = if (!isSingleCount && book.issueNumber != null) {
                    "#${if (book.issueNumber % 1f == 0f) book.issueNumber.toInt() else book.issueNumber}"
                } else null

                ComicSwimlaneItem(
                    displayTitle = displayT,
                    displayBadge = badge,
                    comic = book, 
                    onClick = { onComicClick(book) },
                    onLongClick = { onLongClick(book) }
                ) 
            }
        }
        if (issuesList.isNotEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column(modifier = Modifier.padding(top = 16.dp, bottom = 8.dp)) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("单本 / 期", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.secondary)
                }
            }
            items(issuesList) { book -> 
                var displayT = book.issueTitle ?: book.title
                formatsToStrip.forEach { if (displayT.startsWith(it, true)) displayT = displayT.substring(it.length).trim() }
                if (isSingleCount && !book.seriesName.isNullOrBlank()) displayT = book.seriesName

                val badge = if (!isSingleCount && book.issueNumber != null) {
                    "#${if (book.issueNumber % 1f == 0f) book.issueNumber.toInt() else book.issueNumber}"
                } else null

                ComicSwimlaneItem(
                    displayTitle = displayT,
                    displayBadge = badge,
                    comic = book, 
                    onClick = { onComicClick(book) },
                    onLongClick = { onLongClick(book) }
                ) 
            }
        }
    }
}
