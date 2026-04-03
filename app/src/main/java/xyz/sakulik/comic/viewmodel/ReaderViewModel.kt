package xyz.sakulik.comic.viewmodel

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import xyz.sakulik.comic.model.db.ComicDao
import xyz.sakulik.comic.model.db.ComicEntity
import xyz.sakulik.comic.model.loader.ComicPageLoader
import xyz.sakulik.comic.model.loader.ComicPageLoaderFactory
import xyz.sakulik.comic.model.loader.LocalArchivePageLoader
import xyz.sakulik.comic.model.loader.RemoteStreamPageLoader
import xyz.sakulik.comic.navigation.ReaderRoute
import java.io.File
import androidx.navigation.toRoute
enum class ReaderMode {
    PAGER,      // 传统单页翻页
    DUAL_PAGE,   // 横屏双页模式
    WEBTOON     // 纵向卷轴条漫
}

class ReaderViewModel(
    application: Application,
    private val dao: ComicDao,
    savedStateHandle: SavedStateHandle
) : AndroidViewModel(application) {

    // 手动从 SavedStateHandle 中读取参数
    // 从 SavedStateHandle 中读取由 Navigation Compose 自动填充的参数
    //\ 注意：Navigation 28+ 默认会将 @Serializable 路由的所有参数存入 SavedStateHandle
    val matchedComicId: Long = savedStateHandle.get<Long>("comicId") ?: -1L
    val matchedInitialPage: Int = savedStateHandle.get<Int>("initialPage") ?: 0

    private val _state = MutableStateFlow<ComicState>(ComicState.Idle)
    val state: StateFlow<ComicState> = _state.asStateFlow()

    private val _isRtl = MutableStateFlow(false)
    val isRtl: StateFlow<Boolean> = _isRtl.asStateFlow()

    private val _readerMode = MutableStateFlow(ReaderMode.PAGER)
    val readerMode: StateFlow<ReaderMode> = _readerMode.asStateFlow()

    private val _isImmersive = MutableStateFlow(false)
    val isImmersive: StateFlow<Boolean> = _isImmersive.asStateFlow()

    private val _isSharpenEnabled = MutableStateFlow(false)
    val isSharpenEnabled: StateFlow<Boolean> = _isSharpenEnabled.asStateFlow()

    fun toggleRtl() { _isRtl.value = !_isRtl.value }
    fun toggleSharpen() { 
        _isSharpenEnabled.value = !_isSharpenEnabled.value 
        (pageLoader as? LocalArchivePageLoader)?.setSharpenEnabled(_isSharpenEnabled.value)
        (pageLoader as? RemoteStreamPageLoader)?.setSharpenEnabled(_isSharpenEnabled.value)
    }
    fun toggleReaderMode() {
        _readerMode.value = when (_readerMode.value) {
            ReaderMode.PAGER -> ReaderMode.WEBTOON
            ReaderMode.WEBTOON -> ReaderMode.DUAL_PAGE
            ReaderMode.DUAL_PAGE -> ReaderMode.PAGER
        }
    }
    fun setImmersive(immersive: Boolean) { _isImmersive.value = immersive }

    private val comicCacheDir = File(application.cacheDir, "comic_cache")
    private val tempFile = File(application.cacheDir, "current_comic.tmp")

    // 当前阅读的漫画实体
    var currentEntity: ComicEntity? = null
        private set

    // 页面加载引擎
    private var pageLoader: ComicPageLoader? = null
    private val loaderFactory = ComicPageLoaderFactory(application)

    init {
        if (!comicCacheDir.exists()) comicCacheDir.mkdirs()
        initialLoading()
    }

    private fun initialLoading() {
        viewModelScope.launch {
            _state.value = ComicState.Loading
            try {
                // 根据 ID 查询漫画实体
                val entity = dao.getComicById(matchedComicId)
                    ?: throw IllegalArgumentException("找不到 ID 为 [$matchedComicId] 的漫画记录")
                
                currentEntity = entity
                val uri = Uri.parse(entity.uri)
                // 优先使用数据库中存储的扩展名，如果为空则尝试从路径解析
                val ext = entity.extension.ifBlank {
                    uri.lastPathSegment?.substringAfterLast('.', "")?.lowercase() ?: ""
                }

                clearCache()
                val context = getApplication<Application>()
                
                // 使用工厂创建对应的加载引擎
                val loader = loaderFactory.create(entity)
                pageLoader = loader
                
                val pageCount = loader.getPageCount()
                if (pageCount == 0) throw IllegalStateException("无法加载漫画页面，文件可能已损坏或暂不支持该格式")

                _state.value = ComicState.Ready(pageCount, entity.title, ext, uri, loader)
            } catch (e: Exception) {
                e.printStackTrace()
                _state.value = ComicState.Error(e.message ?: "解析失败，可能是文件损坏或格式不支持")
            }
        }
    }

    fun updateProgress(page: Int, totalPages: Int) {
        val entity = currentEntity ?: return
        viewModelScope.launch {
            dao.updateProgress(entity.id, page, totalPages, System.currentTimeMillis())
        }
    }

    private fun clearCache() {
        comicCacheDir.deleteRecursively()
        comicCacheDir.mkdirs()
        if (tempFile.exists()) tempFile.delete()
        // 通用清理：ReaderViewModel 的 clearCache 现在只关注 View 层级临时副本清理，
        //\ 核心 Archive 生命周期现已移入 LocalArchivePageLoaderclose()
    }

    override fun onCleared() {
        super.onCleared()
        clearCache()
        // 关闭加载引擎并释放资源
        pageLoader?.close()
    }
}
