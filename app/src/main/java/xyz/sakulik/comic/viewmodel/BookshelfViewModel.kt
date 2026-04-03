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
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import xyz.sakulik.comic.model.preferences.SettingsDataStore
import xyz.sakulik.comic.model.db.ComicDao
import xyz.sakulik.comic.model.db.ComicEntity
import xyz.sakulik.comic.model.db.ComicFormat
import xyz.sakulik.comic.model.db.ComicRegion
import xyz.sakulik.comic.model.db.ComicSource
import xyz.sakulik.comic.model.network.ComicApiService
import xyz.sakulik.comic.model.network.RetrofitClient
import xyz.sakulik.comic.model.metadata.FilenameCleaner
import xyz.sakulik.comic.model.metadata.MetadataRepository
import xyz.sakulik.comic.model.metadata.ScrapeStrategy
import xyz.sakulik.comic.worker.LibraryScanWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.lifecycle.SavedStateHandle
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
    val coverComic: ComicEntity, 
    val bookCount: Int,          
    val books: List<ComicEntity>, 
    val disambiguation: String? = null,
    val displayTitle: String = seriesName,
    val displayBadge: String? = disambiguation
)

// ==== 书架网格呈现基础元素，允许单一实体，也允许聚合体并排存在 ====
sealed class BookshelfItem {
    data class SingleComic(
        val comic: ComicEntity, 
        val disambiguation: String? = null,
        val displayTitle: String = "",
        val displayBadge: String? = null
    ) : BookshelfItem()
    data class SeriesGroup(val group: SeriesGroupData) : BookshelfItem()
}

class BookshelfViewModel(
    application: Application,
    private val dao: ComicDao,
    private val savedStateHandle: SavedStateHandle
) : AndroidViewModel(application) {

    private val sharedOkHttpClient = OkHttpClient()
    
    init {
        // 冷启动即刻自动排查全部记录与图鉴，实现绝对同步
        scanAllFolders()
    }
    
    private val _sortOrder = savedStateHandle.getStateFlow("sortOrder", SortOrder.LAST_READ)
    val sortOrder = _sortOrder

    private val _searchQuery = savedStateHandle.getStateFlow("searchQuery", "")
    val searchQuery = _searchQuery

    private val _selectedRegionFilter = savedStateHandle.getStateFlow<ComicRegion?>("regionFilter", null)
    val selectedRegionFilter = _selectedRegionFilter

    private val _selectedFormatFilter = savedStateHandle.getStateFlow<ComicFormat?>("formatFilter", null)
    val selectedFormatFilter = _selectedFormatFilter

    // 云端同步开关状态
    val remoteEnabled = SettingsDataStore.getRemoteEnabledFlow(application)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    // 元数据启用状态开关
    val metadataEnabled = SettingsDataStore.getMetadataEnabledFlow(application)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

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
     * 将数据库流与搜索/过滤/排序状态组合，输出最终用于书架 UI 的内容列表
     * 运用 combine 并发结合数据库与多种交互参数
     * 为了避免超过 5 个参数导致类型推断失败，我们将过滤器参数先行聚合
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val groupedItems: StateFlow<List<BookshelfItem>> = combine(
        combine(
            _selectedRegionFilter,
            _selectedFormatFilter,
            remoteEnabled,
            metadataEnabled
        ) { region, format, remote, metadata ->
            Quadruple(region, format, remote, metadata)
        },
        _sortOrder,
        _searchQuery
    ) { filters, order, query ->
        Triple(filters, order, query)
    }.flatMapLatest { (filters, order, query) ->
        val (regionFilter, formatFilter, remoteEnabled, metadataEnabled) = filters
        
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

        souceRefine(sourceFlow, regionFilter, formatFilter, remoteEnabled, metadataEnabled)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )
    
    // 私有提炼流水线装配工段
    private fun souceRefine(sourceFlow: Flow<List<ComicEntity>>, regionFilter: ComicRegion?, formatFilter: ComicFormat?, remoteEnabled: Boolean, metadataEnabled: Boolean): Flow<List<BookshelfItem>> {
        val formatsToStrip = listOf("HC - ", "SC - ", "TPB - ", "OHC - ", "Absolute ")
        val uselessKeywords = setOf("hc", "sc", "tpb", "gn", "ohc")

        return sourceFlow.map { rawList ->
            //\ 1 处理过滤
            var filteredList = rawList
            if (!remoteEnabled) filteredList = filteredList.filter { it.source != ComicSource.REMOTE }
            if (regionFilter != null) filteredList = filteredList.filter { it.region == regionFilter }
            if (formatFilter != null) filteredList = filteredList.filter { it.format == formatFilter }

            if (!metadataEnabled) {
                // 如果没有开启元数据清洗，直接展示原始标题
                return@map filteredList.map { 
                    BookshelfItem.SingleComic(it, null, it.title, null) 
                }
            }

            //\ 2 初步按系列聚合
            val tempGroups = mutableListOf<BookshelfItem>()
            val groupedMap = filteredList.groupBy { "${it.seriesName}|${it.format}|${it.year}|${it.remoteSeriesId}" }

            for ((_, booksInSeries) in groupedMap) {
                val firstBook = booksInSeries.first()
                val seriesName = firstBook.seriesName
                if (seriesName.isBlank() || booksInSeries.size == 1) {
                    booksInSeries.forEach { tempGroups.add(BookshelfItem.SingleComic(it)) }
                } else {
                    val ascendingBooks = booksInSeries.sortedWith(compareBy({ it.volumeNumber ?: 9999f }, { it.issueNumber ?: 9999f }))
                    tempGroups.add(BookshelfItem.SeriesGroup(SeriesGroupData(seriesName, ascendingBooks.first(), booksInSeries.size, ascendingBooks)))
                }
            }

            //\ 3 统计名称重名，以便后续进行 Disambiguation 处理
            val nameCountMap = tempGroups.groupBy { 
                when(it) {
                    is BookshelfItem.SingleComic -> it.comic.seriesName
                    is BookshelfItem.SeriesGroup -> it.group.seriesName
                }
            }.mapValues { it.value.size }

            //\ 4 重构结果并计算所有 UI 展示所需标题与角标
            tempGroups.map { item ->
                when (item) {
                    is BookshelfItem.SingleComic -> {
                        val comic = item.comic
                        val sName = comic.seriesName
                        val rawITitle = comic.issueTitle ?: comic.title
                        
                        //\ 1 清洗标题前缀
                        var cleanedITitle = rawITitle
                        formatsToStrip.forEach { if (cleanedITitle.startsWith(it, true)) cleanedITitle = cleanedITitle.substring(it.length).trim() }
                        
                        //\ 2 计算冲突辨识 (Disambiguation)
                        val disambiguation = if (!sName.isNullOrBlank() && (nameCountMap[sName] ?: 0) > 1) {
                            listOfNotNull(comic.year, comic.format.name.takeIf { it != "UNKNOWN" }).joinToString(", ")
                        } else null

                        //\ 3 决定最终展示标题 (处理 Batman: Hush 这种系列名与标题重复的情况)
                        val displayTitle = if (!sName.isNullOrBlank()) {
                            val sNorm = sName.lowercase().filter { it.isLetterOrDigit() }
                            val iNorm = cleanedITitle.lowercase().filter { it.isLetterOrDigit() }
                            val isUseless = uselessKeywords.contains(cleanedITitle.lowercase().trim())
                            
                            if (!isUseless && sNorm != iNorm && !iNorm.contains(sNorm) && !sNorm.contains(iNorm)) {
                                "$sName - $cleanedITitle"
                            } else if (isUseless) sName else cleanedITitle
                        } else cleanedITitle

                        item.copy(disambiguation = disambiguation, displayTitle = displayTitle, displayBadge = disambiguation)
                    }
                    is BookshelfItem.SeriesGroup -> {
                        val group = item.group
                        val sName = group.seriesName
                        val disambiguation = if ((nameCountMap[sName] ?: 0) > 1) {
                            listOfNotNull(group.coverComic.year, group.coverComic.format.name.takeIf { it != "UNKNOWN" }).joinToString(", ")
                        } else null
                        
                        BookshelfItem.SeriesGroup(group.copy(disambiguation = disambiguation, displayBadge = disambiguation))
                    }
                }
            }
        }.flowOn(Dispatchers.Default) // [核心优化] 强制在计算线程执行，守护 UI 60 帧
    }

    fun onSortOrderChanged(order: SortOrder) { savedStateHandle["sortOrder"] = order }
    fun onSearchQueryChanged(query: String) { savedStateHandle["searchQuery"] = query }
    fun setRegionFilter(region: ComicRegion?) { savedStateHandle["regionFilter"] = region }
    fun setFormatFilter(format: ComicFormat?) { savedStateHandle["formatFilter"] = format }

    fun addFolder(treeUri: Uri) {
        // 关键：持久化 SAF 树 URI 的读取权限
        // 没有这一行，授权仅在当前会话有效，进程重启或切后台就会报 SecurityException
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
            "LibraryScan_Single", ExistingWorkPolicy.REPLACE, workRequest
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

    // 自动刮削相关状态
    private val _autoScrapeState = MutableStateFlow<AutoScrapeState>(AutoScrapeState.Idle)
    val autoScrapeState = _autoScrapeState.asStateFlow()

    /**
     * 一键自动层层 SMART_FALLBACK 刷射并自动应用最佳第一条匹配结果，放山无人等待
     */
    fun autoScrape(comic: ComicEntity) {
        viewModelScope.launch {
            _autoScrapeState.value = AutoScrapeState.Loading(comic.id)
            try {
                xyz.sakulik.comic.model.metadata.MetadataScraper.autoScrape(getApplication(), comic)
                _autoScrapeState.value = AutoScrapeState.Done(comic.id)
            } catch (e: Exception) {
                _autoScrapeState.value = AutoScrapeState.Error(comic.id, e.localizedMessage ?: "未知错误")
            }
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

    fun saveCloudApi(url: String) {
        viewModelScope.launch {
            SettingsDataStore.saveComicApiBaseUrl(getApplication(), url)
        }
    }

    /**
     * 从网络服务拉取远端漫画列表并同步到本地数据库
     */
    fun syncRemoteLibrary() {
        viewModelScope.launch {
            Log.d("BookshelfSync", "开始云端同步...")
            _autoScrapeState.value = AutoScrapeState.Loading(-1L)
            try {
                val context = getApplication<Application>()
                val baseUrl = SettingsDataStore.getComicApiBaseUrlFlow(context).firstOrNull() 
                    ?: "https://comix.sakulik.xyz/"
                
                Log.d("BookshelfSync", "使用 BaseURL: $baseUrl")

                val apiService = RetrofitClient.createService(
                    context = context,
                    baseUrl = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/",
                    serviceClass = ComicApiService::class.java
                )

                val remoteComics = apiService.getComics()
                Log.d("BookshelfSync", "获取到 ${remoteComics.size} 本远程漫画")
                
                withContext(Dispatchers.IO) {
                    remoteComics.forEach { item ->
                        val existing = dao.getComicByLocation(item.id)
                        
                        // 统一封面处理：拼接完整 URL
                        val absoluteCoverUrl = if (item.coverUrl.startsWith("http")) {
                            item.coverUrl
                        } else {
                            val normalizedBase = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
                            val relativePath = item.coverUrl.removePrefix("/")
                            "$normalizedBase$relativePath"
                        }

                        if (existing == null) {
                            Log.d("BookshelfSync", "新增漫画: ${item.originalName}")
                            val newComic = ComicEntity(
                                title = item.originalName.substringBeforeLast("."),
                                uri = absoluteCoverUrl,
                                extension = item.originalName.substringAfterLast(".", "cbr"),
                                totalPages = item.totalPages,
                                source = ComicSource.REMOTE,
                                location = item.id,
                                coverCachePath = absoluteCoverUrl,
                                seriesName = item.originalName.substringBeforeLast(".")
                            )
                            dao.insert(newComic)
                        } else {
                            Log.d("BookshelfSync", "更新记录: ${item.originalName}")
                            val updated = existing.copy(
                                totalPages = item.totalPages,
                                coverCachePath = absoluteCoverUrl
                            )
                            dao.update(updated)
                        }
                    }
                }
                Log.d("BookshelfSync", "同步成功")
                _autoScrapeState.value = AutoScrapeState.Done(-1L)
            } catch (e: Exception) {
                Log.e("BookshelfSync", "同步失败: ${e.message}", e)
                _autoScrapeState.value = AutoScrapeState.Error(-1L, "同步失败: ${e.localizedMessage}")
            }
        }
    }
    fun saveRemoteEnabled(enabled: Boolean) {
        viewModelScope.launch {
            SettingsDataStore.saveRemoteEnabled(getApplication(), enabled)
        }
    }

    fun getSeriesByName(name: String): Flow<SeriesGroupData?> {
        return groupedItems.map { items ->
            items.filterIsInstance<BookshelfItem.SeriesGroup>()
                .find { it.group.seriesName == name }
                ?.group
        }
    }

    /**
     * 删除所有来源为远端的漫画记录
     */

    fun clearRemoteLibrary() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                dao.deleteComicsBySource(ComicSource.REMOTE)
            }
        }
    }
}

// 辅助数据结构
data class Quadruple<out A, out B, out C, out D>(
    val first: A,
    val second: B,
    val third: C,
    val fourth: D
) : java.io.Serializable {
    override fun toString(): String = "($first, $second, $third, $fourth)"
}

sealed class AutoScrapeState {
    object Idle : AutoScrapeState()
    data class Loading(val comicId: Long) : AutoScrapeState()
    data class Done(val comicId: Long) : AutoScrapeState()
    data class Error(val comicId: Long, val message: String) : AutoScrapeState()
}
