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

    // 之前用 Hilt 给 backStackEntry 直接注入 ViewModel 时，toRoute 可以自动拆箱。
    // 现在用了原生自定义包含 HashMap 的 SavedStateHandle，手动从 key-value 读出才是安全的对抗！
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

    // 保存当前正在攻克的实体坐标
    var currentEntity: ComicEntity? = null
        private set

    // 核心加载引擎句柄
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
                // 通过路由带过来的 ID 直接对接底层 Dao 反向查阅出这本神作的实体数据
                val entity = dao.getComicById(matchedComicId)
                    ?: throw IllegalArgumentException("图鉴编号 [$matchedComicId] 不存在于数据库的虚空之中！")
                
                currentEntity = entity
                val uri = Uri.parse(entity.uri)
                // 优先使用数据库内専属存储的 extension 字段，
                // 如果旧数据这个字段为空（元数据刷射后 title 已改名），则从 URI 路径縴耶山小追识
                val ext = entity.extension.ifBlank {
                    uri.lastPathSegment?.substringAfterLast('.', "")?.lowercase() ?: ""
                }

                clearCache()
                val context = getApplication<Application>()
                
                // 【核心变阵】通过工厂实例化符合数据源逻辑的加载引擎
                val loader = loaderFactory.create(entity)
                pageLoader = loader
                
                val pageCount = loader.getPageCount()
                if (pageCount == 0) throw IllegalStateException("解析图集书目失败，引擎不支持或档案文件损坏！")

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
        // 核心 Archive 生命周期现已移入 LocalArchivePageLoader.close()。
    }

    override fun onCleared() {
        super.onCleared()
        clearCache()
        // 彻底切断底层物理连接与资源占用
        pageLoader?.close()
    }
}
