package xyz.sakulik.comic.viewmodel

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import xyz.sakulik.comic.model.db.ComicDao
import xyz.sakulik.comic.model.db.ComicEntity
import xyz.sakulik.comic.model.db.ComicFormat
import xyz.sakulik.comic.model.db.ComicRegion
import xyz.sakulik.comic.model.metadata.FilenameCleaner
import xyz.sakulik.comic.model.metadata.MetadataRepository
import xyz.sakulik.comic.model.metadata.ScrapeStrategy
import xyz.sakulik.comic.worker.LibraryScanWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.io.File
import java.util.UUID
import android.content.Intent

enum class SortOrder(val displayName: String) {
    LAST_READ("上次阅读"),
    ADDED_TIME("添加时间"),
    TITLE_AZ("标题 (A-Z)")
}

// ==== 数据组合体，代表合并后在 UI 中的堆叠文件夹 ====
data class SeriesGroupData(
    val seriesName: String,
    val coverComic: ComicEntity, // 用其中一本来代表堆叠外放封皮
    val bookCount: Int,          // 此文件夹里的子书籍数量
    val books: List<ComicEntity> // 内含的所有完整分卷表单
)

// ==== 书架网格呈现基础元素，允许单一实体，也允许聚合体并排存在 ====
sealed class BookshelfItem {
    data class SingleComic(val comic: ComicEntity) : BookshelfItem()
    data class SeriesGroup(val group: SeriesGroupData) : BookshelfItem()
}

class BookshelfViewModel(
    application: Application,
    private val dao: ComicDao
) : AndroidViewModel(application) {
    
    init {
        // 冷启动即刻自动排查全部记录与图鉴，实现绝对同步
        scanAllFolders()
    }
    
    private val _sortOrder = MutableStateFlow(SortOrder.LAST_READ)
    val sortOrder = _sortOrder.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery = _searchQuery.asStateFlow()

    private val _selectedRegionFilter = MutableStateFlow<ComicRegion?>(null)
    val selectedRegionFilter = _selectedRegionFilter.asStateFlow()

    private val _selectedFormatFilter = MutableStateFlow<ComicFormat?>(null)
    val selectedFormatFilter = _selectedFormatFilter.asStateFlow()

    // ======= 核心重构：打通 WorkManager 返回的数据管道 =======
    val scanProgress: StateFlow<String?> = WorkManager.getInstance(application)
        .getWorkInfosByTagFlow("SCAN_LIBRARY_WORK")
        .map { workInfos ->
            val workInfo = workInfos.firstOrNull()
            if (workInfo != null && workInfo.state == WorkInfo.State.RUNNING) {
                // 仅在任务真正运行时返回进度，触发 UI 进度条
                workInfo.progress.getString(LibraryScanWorker.PROGRESS_MSG)
            } else {
                // 任务完成（SUCCEEDED）或未开始时返回 null，隐藏进度条
                null
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /**
     * 【大核心折叠中枢流出节点】 
     * 运用 combine 并发结合数据库与4合1交互态参数，通过 groupBy 彻底瓦解原来的单线性书籍展馆，制造坍缩折叠堆堆叠结构。
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val groupedItems: StateFlow<List<BookshelfItem>> = combine(
        _sortOrder,
        _searchQuery,
        _selectedRegionFilter,
        _selectedFormatFilter
    ) { order, query, regionFilter, formatFilter ->
        Triple(Pair(order, query), regionFilter, formatFilter)
    }.flatMapLatest { (orderQuery, regionFilter, formatFilter) ->
        val (order, query) = orderQuery
        // 发掘底层原始列表
        val sourceFlow = if (query.isNotBlank()) {
            dao.searchComics(query)
        } else {
            when (order) {
                SortOrder.LAST_READ -> dao.getAllComicsByLastReadFlow()
                SortOrder.ADDED_TIME -> dao.getAllComicsByAddedTimeFlow()
                SortOrder.TITLE_AZ -> dao.getAllComicsByTitleFlow()
            }
        }

        souceRefine(sourceFlow, regionFilter, formatFilter)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )
    
    // 私有提炼流水线装配工段
    private fun souceRefine(sourceFlow: Flow<List<ComicEntity>>, regionFilter: ComicRegion?, formatFilter: ComicFormat?): Flow<List<BookshelfItem>> {
        return sourceFlow.map { rawList ->
            // 第一闸刀：切除掉多维选项不匹配的书籍
            var filteredList = rawList
            if (regionFilter != null) {
                // 精准打击：不再接受 UNKNOWN 混入已定义的分类标签
                filteredList = filteredList.filter { it.region == regionFilter }
            }
            if (formatFilter != null) {
                filteredList = filteredList.filter { it.format == formatFilter }
            }

            val displayItems = mutableListOf<BookshelfItem>()
            
            // 第二屠龙刀：聚合同类项，折叠为卷集
            val groupedMap = filteredList.groupBy { it.seriesName }

            for ((seriesName, booksInSeries) in groupedMap) {
                if (seriesName.isBlank() || booksInSeries.size == 1) {
                    // 没有有效系列名，或者这套书仅仅收录了一本，那么没必要文件夹化，直接平铺开去
                    booksInSeries.forEach { displayItems.add(BookshelfItem.SingleComic(it)) }
                } else {
                    // 检测到有多本归属于同系列的残卷，在此强行折叠坍缩，构造文件夹虚拟实体 (SeriesGroup)
                    // 首先我们要把内部进行正确时序理顺（按卷号或者按期数号），这是为了让文件夹拿第一卷作为封面
                    val ascendingBooks = booksInSeries.sortedWith(
                        compareBy({ it.volumeNumber ?: 9999f }, { it.issueNumber ?: 9999f })
                    )
                    
                    displayItems.add(
                        BookshelfItem.SeriesGroup(
                            SeriesGroupData(
                                seriesName = seriesName,
                                coverComic = ascendingBooks.first(),
                                bookCount = booksInSeries.size,
                                books = ascendingBooks
                            )
                        )
                    )
                }
            }
            displayItems
        }
    }

    fun onSortOrderChanged(order: SortOrder) { _sortOrder.value = order }
    fun onSearchQueryChanged(query: String) { _searchQuery.value = query }
    fun setRegionFilter(region: ComicRegion?) { _selectedRegionFilter.value = region }
    fun setFormatFilter(format: ComicFormat?) { _selectedFormatFilter.value = format }

    fun addFolder(treeUri: Uri) {
        // 关键：持久化 SAF 树 URI 的读取权限。
        // 没有这一行，授权仅在当前会话有效，进程重启或切后台就会报 SecurityException。
        try {
            getApplication<Application>().contentResolver.takePersistableUriPermission(
                treeUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        } catch (e: SecurityException) {
            e.printStackTrace() // 部分设备可能不支持 write，寄失不报
        }

        // 添加后仅扫该单个分支即可，速度比全盘排摸更快
        val workRequest = OneTimeWorkRequestBuilder<LibraryScanWorker>()
            .setInputData(workDataOf(LibraryScanWorker.KEY_URI to treeUri.toString()))
            .addTag("SCAN_LIBRARY_WORK")
            .build()
        WorkManager.getInstance(getApplication()).enqueueUniqueWork(
            "LibraryScan_Single", ExistingWorkPolicy.KEEP, workRequest
        )
    }

    // 全量刷新：核对已授权表与 DB 的镜像差异
    fun scanAllFolders() {
        val workRequest = OneTimeWorkRequestBuilder<LibraryScanWorker>()
            .addTag("SCAN_LIBRARY_WORK")
            .build()
        WorkManager.getInstance(getApplication()).enqueueUniqueWork(
            "LibraryScan_All", ExistingWorkPolicy.KEEP, workRequest
        )
    }

    // ============ 一键刷射承载序列流 ============
    private val _autoScrapeState = MutableStateFlow<AutoScrapeState>(AutoScrapeState.Idle)
    val autoScrapeState = _autoScrapeState.asStateFlow()

    /**
     * 一键自动层层 SMART_FALLBACK 刷射并自动应用最佳第一条匹配结果，放山无人等待。
     */
    fun autoScrape(comic: ComicEntity) {
        viewModelScope.launch {
            _autoScrapeState.value = AutoScrapeState.Loading(comic.id)

            val repository = MetadataRepository(getApplication())
            val keyword = FilenameCleaner.clean(comic.title)
            val results = try {
                repository.searchComic(keyword, ScrapeStrategy.SMART_FALLBACK)
            } catch (e: Exception) {
                _autoScrapeState.value = AutoScrapeState.Error(comic.id, "网络异常: ${e.localizedMessage}")
                return@launch
            }

            val best = results.firstOrNull()
            if (best == null) {
                _autoScrapeState.value = AutoScrapeState.Error(comic.id, "未搜索到相关内容")
                return@launch
            }

            withContext(Dispatchers.IO) {
                var finalCoverPath = comic.coverCachePath
                best.coverUrl?.let { url ->
                    try {
                        // [网络层防护] ComicVine / Bangumi 往往拦截默认空 User-Agent 的裸请求
                        val client = OkHttpClient()
                        val request = Request.Builder()
                            .url(url)
                            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                            .build()
                        val response = client.newCall(request).execute()
                        val bytes = response.body?.bytes()
                        
                        if (bytes != null && response.isSuccessful) {
                            // [格式与毁损防护] 经过原生 BitmapFactory 解构压缩，彻底杜绝服务器传回 JPG 却被盲目盖上 .webp 后缀导致 Coil 框架解析雪崩的隐患。
                            val bitmap = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                            if (bitmap != null) {
                                val file = File(getApplication<Application>().filesDir, "covers/${UUID.randomUUID()}.webp")
                                file.parentFile?.mkdirs()
                                java.io.FileOutputStream(file).use { out ->
                                    @Suppress("DEPRECATION")
                                    bitmap.compress(android.graphics.Bitmap.CompressFormat.WEBP, 85, out)
                                }
                                bitmap.recycle()
                                finalCoverPath = file.absolutePath
                            }
                        }
                    } catch (e: Exception) { e.printStackTrace() }
                }

                val updated = comic.copy(
                    title = best.title + buildString {
                        if (comic.volumeNumber != null) append(" Vol.${comic.volumeNumber}")
                        if (comic.issueNumber != null) append(" #${comic.issueNumber}")
                    },
                    coverCachePath = finalCoverPath,
                    seriesName = best.title,
                    region = best.region,
                    format = if (best.format != ComicFormat.UNKNOWN) best.format else comic.format,
                    authors = best.authors.joinToString(", ").takeIf { it.isNotEmpty() } ?: comic.authors,
                    summary = best.summary ?: comic.summary,
                    genres = best.genres.joinToString(", ").takeIf { it.isNotEmpty() } ?: comic.genres,
                    publisher = best.publisher ?: comic.publisher,
                    rating = best.rating ?: comic.rating
                )
                dao.update(updated)
            }

            _autoScrapeState.value = AutoScrapeState.Done(comic.id)
        }
    }

    fun resetComicProgress(comic: ComicEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            dao.updateProgress(comic.id, 0, comic.totalPages, System.currentTimeMillis())
        }
    }

    fun deleteComic(comic: ComicEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            dao.delete(comic)
        }
    }

    fun clearScrapeState() { _autoScrapeState.value = AutoScrapeState.Idle }
}

sealed class AutoScrapeState {
    object Idle : AutoScrapeState()
    data class Loading(val comicId: Long) : AutoScrapeState()
    data class Done(val comicId: Long) : AutoScrapeState()
    data class Error(val comicId: Long, val message: String) : AutoScrapeState()
}
