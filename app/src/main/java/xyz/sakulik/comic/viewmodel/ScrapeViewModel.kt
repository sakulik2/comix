package xyz.sakulik.comic.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import xyz.sakulik.comic.model.db.ComicDao
import xyz.sakulik.comic.model.db.AppDatabase
import xyz.sakulik.comic.model.db.ComicEntity
import xyz.sakulik.comic.model.metadata.FilenameCleaner
import xyz.sakulik.comic.model.metadata.MetadataRepository
import xyz.sakulik.comic.model.metadata.ScrapeStrategy
import xyz.sakulik.comic.model.metadata.ScrapedComicInfo
import xyz.sakulik.comic.model.network.RetrofitClient
import java.io.File
import java.util.UUID

enum class ScrapeStep {
    VOLUME, ISSUE
}

class ScrapeViewModel(
    application: Application,
    private val dao: ComicDao
) : AndroidViewModel(application) {
    private val repository = MetadataRepository(application)
    private val okHttpClient = okhttp3.OkHttpClient()

    private val _isSearching = MutableStateFlow(false)
    val isSearching = _isSearching.asStateFlow()

    private val _searchResults = MutableStateFlow<List<ScrapedComicInfo>>(emptyList())
    val searchResults = _searchResults.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery = _searchQuery.asStateFlow()

    private val _selectedStrategy = MutableStateFlow(ScrapeStrategy.SMART_FALLBACK)
    val selectedStrategy = _selectedStrategy.asStateFlow()

    // 多步状态管理
    private val _currentStep = MutableStateFlow(ScrapeStep.VOLUME)
    val currentStep = _currentStep.asStateFlow()

    private val _selectedVolume = MutableStateFlow<ScrapedComicInfo?>(null)
    val selectedVolume = _selectedVolume.asStateFlow()

    // 缓存目标漫画实体，用于后续更新数据库
    private var targetComic: ComicEntity? = null

    fun loadAndInit(comicId: Long) {
        viewModelScope.launch {
            val comic = dao.getComicById(comicId) ?: return@launch
            targetComic = comic
            initSearch(comic)
        }
    }

    fun initSearch(comic: ComicEntity) {
        _searchQuery.value = FilenameCleaner.clean(comic.title)
        _searchResults.value = emptyList()
        _isSearching.value = false
        _currentStep.value = ScrapeStep.VOLUME
        _selectedVolume.value = null
        _selectedStrategy.value = ScrapeStrategy.SMART_FALLBACK
        // 加载后立即自动触发一次初扫，提升体验
        search()
    }

    fun onQueryChanged(query: String) {
        _searchQuery.value = query
    }

    fun onStrategyChanged(strategy: ScrapeStrategy) {
        _selectedStrategy.value = strategy
    }

    fun search() {
        val query = _searchQuery.value.trim()
        if (query.isEmpty()) return
        
        viewModelScope.launch {
            _isSearching.value = true
            _searchResults.value = emptyList()
            _currentStep.value = ScrapeStep.VOLUME
            _selectedVolume.value = null
            // 搜索 API 本身可能混杂 Volume 和 Issue，但我们第一步将其视为系列的初步筛选
            val results = repository.searchComic(query, _selectedStrategy.value)
            _searchResults.value = results
            _isSearching.value = false
        }
    }

     // * 选择了系列后，立刻拉取该系列下的所有分期
    fun selectVolume(volume: ScrapedComicInfo) {
        val volumeId = volume.remoteId ?: return
        viewModelScope.launch {
            _isSearching.value = true
            _selectedVolume.value = volume
            _currentStep.value = ScrapeStep.ISSUE
            val issues = repository.getIssuesByVolumeId(volumeId)
            _searchResults.value = issues
            _isSearching.value = false
        }
    }

    fun goBackToVolumeSearch() {
        _currentStep.value = ScrapeStep.VOLUME
        _selectedVolume.value = null
        search() // 重新刷新搜索列表
    }

    /**
     * 合并网侧提取出来的新数据，顺便尝试将远端 URL 图片强刷到本地覆盖做封皮
     */
    /**
     * 将解析出的元数据应用到本地数据库，并下载封面图
     */
    fun applyMetadata(comic: ComicEntity, info: ScrapedComicInfo, onComplete: () -> Unit) {
        targetComic = comic
        applyMetadataBySelection(info, onComplete)
    }

    fun applyMetadataBySelection(info: ScrapedComicInfo, onComplete: () -> Unit) {
        val comic = targetComic ?: return
        val currentVol = _selectedVolume.value
        
        viewModelScope.launch(Dispatchers.IO) {
            var finalCoverPath = comic.coverCachePath

            // 若有网络源图，用 OkHttp 下载回来落盘，体验更佳
            info.coverUrl?.let { url ->
                try {
                    val request = okhttp3.Request.Builder().url(url).build()
                    okHttpClient.newCall(request).execute().use { response ->
                        val bytes = response.body?.bytes()
                        if (bytes != null && response.isSuccessful) {
                            val file = File(getApplication<Application>().filesDir, "covers/${UUID.randomUUID()}.webp")
                            file.parentFile?.mkdirs()
                            file.writeBytes(bytes)
                            finalCoverPath = file.absolutePath
                        }
                    }
                } catch (e: Exception) { e.printStackTrace() }
            }

            // 合并逻辑：如果已经有了系列 (Volume)，则使用其标题
            val seriesName = currentVol?.title ?: info.title
            val issueTitle = if (currentVol != null) info.issueTitle else null
            val issueNumber = info.issueNumber ?: comic.issueNumber

            val updatedComic = comic.copy(
                seriesName = seriesName,
                issueTitle = if (issueTitle == seriesName) null else issueTitle, // 去重
                issueNumber = issueNumber,
                coverCachePath = finalCoverPath,
                remoteSeriesId = currentVol?.remoteId ?: info.remoteId,
                region = info.region,
                format = if (info.format != xyz.sakulik.comic.model.db.ComicFormat.UNKNOWN) info.format else comic.format,
                authors = info.authors.joinToString(", ").takeIf { it.isNotEmpty() } ?: currentVol?.authors?.joinToString(", ") ?: comic.authors,
                summary = info.summary ?: currentVol?.summary ?: comic.summary,
                genres = info.genres.joinToString(", ").takeIf { it.isNotEmpty() } ?: currentVol?.genres?.joinToString(", ") ?: comic.genres,
                publisher = info.publisher ?: currentVol?.publisher ?: comic.publisher,
                rating = info.rating ?: comic.rating,
                year = info.year ?: currentVol?.year ?: comic.year
            )
            dao.update(updatedComic)
            
            withContext(Dispatchers.Main) { onComplete() }
        }
    }
}
