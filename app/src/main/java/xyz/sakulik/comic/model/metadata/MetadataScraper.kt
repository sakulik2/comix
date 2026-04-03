package xyz.sakulik.comic.model.metadata

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import okhttp3.OkHttpClient
import okhttp3.Request
import xyz.sakulik.comic.model.db.AppDatabase
import xyz.sakulik.comic.model.db.ComicEntity
import xyz.sakulik.comic.model.db.ComicFormat
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/**
 * 核心刮削调度引擎 —— 解耦自 ViewModel，允许在 Worker 中异步静默执行
 */
object MetadataScraper {
    private val client = OkHttpClient()

    suspend fun autoScrape(context: Context, comic: ComicEntity) {
        val dao = AppDatabase.getDatabase(context).comicDao()
        val repository = MetadataRepository(context)

        // [核心改进] 搜索词动态生成
        val precisionQuery = when {
            comic.format == ComicFormat.TPB && comic.volumeNumber != null -> {
                //\ 如果是合订本，搜索词带上 Vol 帮助命中合订本卷集
                val volStr = if (comic.volumeNumber % 1f == 0f) comic.volumeNumber.toInt() else comic.volumeNumber
                "${comic.seriesName} Vol. $volStr"
            }
            comic.issueNumber != null -> {
                // 如果是单期，带上期号
                val issStr = if (comic.issueNumber % 1f == 0f) comic.issueNumber.toInt() else comic.issueNumber
                "${comic.seriesName} $issStr"
            }
            else -> {
                comic.seriesName.ifEmpty { FilenameCleaner.clean(comic.title) }
            }
        }

        try {
            // [Layer 1] 抓取系列/分期 (Volume/Issue) 信息
            // 我们不再在搜索词里强加括号年份，以免 API 过滤掉正传系列，改用后置评分
            val finalResults = repository.searchComic(precisionQuery, ScrapeStrategy.SMART_FALLBACK)

            // [核心重控] 加权评分匹配算法 (Weighted Scoring Algorithm)
            val bestVolume = finalResults.maxByOrNull { result ->
                var score = 0
                val titleLower = result.title.lowercase()
                val summaryLower = result.summary?.lowercase() ?: ""
                
                //\ 1 标题基础分 (完全匹配或高度包含)
                if (result.title.equals(comic.seriesName, ignoreCase = true)) {
                    score += 100
                } else if (titleLower.contains(comic.seriesName.lowercase())) {
                    score += 50
                }
                
                //\ 2 年份硬匹配 (2011 对上 2011) - +80 分
                if (comic.year != null && result.year != null && result.year.contains(comic.year)) {
                    score += 80
                }
                
                //\ 3 [关键] 格式对齐加权
                if (comic.format == ComicFormat.TPB || comic.format == ComicFormat.HC || comic.format == ComicFormat.OMNIBUS) {
                    // 如果文件是合订本，寻找标题带 Vol/TPB/HC 的结果
                    if (titleLower.contains("tpb") || titleLower.contains("trade paperback") || titleLower.contains("vol") || titleLower.contains("hc") || titleLower.contains("hardcover")) {
                        score += 100
                    }
                } else if (comic.format == ComicFormat.ISSUE) {
                    // 如果文件是单期，对描述中含有“合订本”特征的结果进行强力降权
                    if (summaryLower.contains("collecting") || summaryLower.contains("collects") || summaryLower.contains("trade paperback") || summaryLower.contains("hardcover")) {
                        score -= 100
                    }
                }
                
                //\ 4 特殊特征词匹配 (如 New 52 / N52)
                val isN52File = comic.title.contains("N52", ignoreCase = true) || comic.title.contains("New 52", ignoreCase = true)
                val isN52Result = titleLower.contains("new 52") || summaryLower.contains("new 52")
                if (isN52File && isN52Result) score += 50
                
                score
            } ?: return

            var currentIssueTitle = comic.issueTitle
            var currentIssueNumber = comic.issueNumber ?: comic.volumeNumber // [TPB 关键映射] 将卷号作为期号用于抓取
            var finalCoverPath = comic.coverCachePath

            // [Layer 2] 分层抓取逻辑
            if (bestVolume.format == ComicFormat.ISSUE && bestVolume.issueNumber != null) {
                // 如果搜索直接命中了期号结果
                currentIssueNumber = bestVolume.issueNumber
                currentIssueTitle = bestVolume.issueTitle
            }

            if (bestVolume.source == ScrapeSource.COMIC_VINE && bestVolume.remoteId != null && (currentIssueNumber != null)) {
                val issueInfo = repository.searchIssue(bestVolume.remoteId, currentIssueNumber!!)
                if (issueInfo != null) {
                    issueInfo.coverUrl?.let { url ->
                        val path = downloadCover(context, url)
                        if (path != null) finalCoverPath = path
                    }
                    currentIssueTitle = issueInfo.issueTitle
                    currentIssueNumber = issueInfo.issueNumber ?: currentIssueNumber
                }
            } else if (finalCoverPath == null || !File(finalCoverPath).exists()) {
                bestVolume.coverUrl?.let { url ->
                    val path = downloadCover(context, url)
                    if (path != null) finalCoverPath = path
                }
            }

            // [智能去重] 如果分期名就是系列名本身，强制清空，交给 UI 逻辑去拼装漂亮的 "#5"
            if (currentIssueTitle != null && currentIssueTitle.trim().equals(bestVolume.title, ignoreCase = true)) {
                currentIssueTitle = null
            }

            // [Step 3] 更新当前书籍
            val updated = comic.copy(
                coverCachePath = finalCoverPath,
                seriesName = bestVolume.title,
                issueTitle = currentIssueTitle,
                issueNumber = currentIssueNumber, // [期号回填固化]
                remoteSeriesId = bestVolume.remoteId,
                region = bestVolume.region,
                format = if (bestVolume.format != ComicFormat.UNKNOWN) bestVolume.format else comic.format,
                authors = bestVolume.authors.joinToString(", ").takeIf { it.isNotEmpty() } ?: comic.authors,
                summary = bestVolume.summary ?: comic.summary,
                genres = bestVolume.genres.joinToString(", ").takeIf { it.isNotEmpty() } ?: comic.genres,
                publisher = bestVolume.publisher ?: comic.publisher,
                rating = bestVolume.rating ?: comic.rating,
                year = bestVolume.year ?: comic.year
            )
            dao.update(updated)

            // [Step 4] 元数据传播 (Propagation)
            // 将系列级信息同步给库中同一系列名的所有漫画（避免重复抓取 Volume）
            dao.updateSeriesMetadata(
                seriesName = bestVolume.title,
                authors = updated.authors,
                summary = updated.summary,
                genres = updated.genres,
                publisher = updated.publisher,
                region = updated.region,
                remoteId = bestVolume.remoteId,
                year = updated.year
            )
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private suspend fun downloadCover(context: Context, url: String): String? {
        return try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                .build()
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    response.body?.byteStream()?.use { stream ->
                        // [核心改进] 内存直通解码：不通过 ByteArray 缓冲，直接从网络流解码位图
                        // 注意：如果需要存盘，必须先存盘再解码，或者通过 BufferedInputStream 标记位重读
                        // 这里我们采用先存盘再解码的策略，因为物理存盘是必要的
                        val file = File(context.filesDir, "covers/${UUID.randomUUID()}.webp")
                        file.parentFile?.mkdirs()
                        
                        FileOutputStream(file).use { out ->
                            stream.copyTo(out)
                        }
                        
                        if (file.exists()) {
                            val options = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 }
                            val bitmap = BitmapFactory.decodeFile(file.absolutePath, options)
                            if (bitmap != null) {
                                bitmap.recycle() // 我们只需要验证下载成功并存盘，MetadataScraper 主要目的是存盘
                                return file.absolutePath
                            }
                        }
                    }
                }
            }
            null
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}
