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

    // 暂存正主实体，以便选择后能够将其归档同步到数据库
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
            val results = repository.searchComic(query, _selectedStrategy.value)
            _searchResults.value = results
            _isSearching.value = false
        }
    }

    /**
     * 合并网侧提取出来的新数据，顺便尝试将远端 URL 图片强刷到本地覆盖做封皮。
     */
    /**
     * 【兼容性适配器】供旧版 BottomSheet 直接传递实体调用
     */
    fun applyMetadata(comic: ComicEntity, info: ScrapedComicInfo, onComplete: () -> Unit) {
        targetComic = comic
        applyMetadataBySelection(info, onComplete)
    }

    fun applyMetadataBySelection(info: ScrapedComicInfo, onComplete: () -> Unit) {
        val comic = targetComic ?: return
        viewModelScope.launch(Dispatchers.IO) {
            var finalCoverPath = comic.coverCachePath

            // 若有网络源图，用 OkHttp 下载回来落盘，体验更佳
            info.coverUrl?.let { url ->
                try {
                    val request = okhttp3.Request.Builder().url(url).build()
                    okHttpClient.newCall(request).execute().use { response ->
                        val bytes = response.body?.bytes()
                        if (bytes != null && response.isSuccessful) {
                            val file = File(getApplication<Application>().cacheDir, "covers/${UUID.randomUUID()}.webp")
                            file.parentFile?.mkdirs()
                            file.writeBytes(bytes)
                            finalCoverPath = file.absolutePath
                        }
                    }
                } catch (e: Exception) { e.printStackTrace() }
            }

            val originalExtText = buildString {
                if (comic.volumeNumber != null) append(" Vol.${comic.volumeNumber}")
                if (comic.issueNumber != null) append(" #${comic.issueNumber}")
            }
            // 重新在远端干净标题上拼接上它本地的原生期号后缀
            val newTitle = info.title + originalExtText

            val updatedComic = comic.copy(
                title = newTitle,
                coverCachePath = finalCoverPath,
                seriesName = info.title,
                region = info.region, // 用远端库更权威的阵营覆盖本地可能猜错推测的阵营
                format = if (info.format != xyz.sakulik.comic.model.db.ComicFormat.UNKNOWN) info.format else comic.format, // 用上游剥出来的权威发行形态覆盖原本未定义的猜想
                authors = info.authors.joinToString(", ").takeIf { it.isNotEmpty() } ?: comic.authors,
                summary = info.summary ?: comic.summary,
                genres = info.genres.joinToString(", ").takeIf { it.isNotEmpty() } ?: comic.genres,
                publisher = info.publisher ?: comic.publisher,
                rating = info.rating ?: comic.rating
            )
            dao.update(updatedComic)
            
            withContext(Dispatchers.Main) { onComplete() }
        }
    }
}
