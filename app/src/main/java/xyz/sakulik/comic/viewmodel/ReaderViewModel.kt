package xyz.sakulik.comic.viewmodel

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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

    private val _isVolumeKeyEnabled = MutableStateFlow(false)
    val isVolumeKeyEnabled: StateFlow<Boolean> = _isVolumeKeyEnabled.asStateFlow()

    fun toggleVolumeKeyPaging() {
        val cid = matchedComicId
        _isVolumeKeyEnabled.value = !_isVolumeKeyEnabled.value
        val enabled = _isVolumeKeyEnabled.value
        xyz.sakulik.comic.utils.VolumeKeyHandler.isEnabled = enabled
        prefs.edit().putBoolean("vol_paging_$cid", enabled).apply()
    }

    fun toggleRtl() { 
        val cid = matchedComicId
        _isRtl.value = !_isRtl.value 
        prefs.edit().putBoolean("rtl_$cid", _isRtl.value).apply()
    }
    
    fun toggleSharpen() { 
        val cid = matchedComicId
        _isSharpenEnabled.value = !_isSharpenEnabled.value 
        val enabled = _isSharpenEnabled.value
        (pageLoader as? LocalArchivePageLoader)?.setSharpenEnabled(enabled)
        (pageLoader as? RemoteStreamPageLoader)?.setSharpenEnabled(enabled)
        prefs.edit().putBoolean("sharpen_$cid", enabled).apply()
    }
    
    fun toggleReaderMode() {
        val cid = matchedComicId
        _readerMode.value = when (_readerMode.value) {
            ReaderMode.PAGER -> ReaderMode.WEBTOON
            ReaderMode.WEBTOON -> ReaderMode.DUAL_PAGE
            ReaderMode.DUAL_PAGE -> ReaderMode.PAGER
        }
        // 核心单本记忆：以漫画 ID 为唯一标识，持久化存储当前漫画的阅读布局偏好
        prefs.edit().putInt("reader_mode_$cid", _readerMode.value.ordinal).apply()
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

    private val prefs = application.getSharedPreferences("reader_settings", Context.MODE_PRIVATE)

    init {
        if (!comicCacheDir.exists()) comicCacheDir.mkdirs()
        initialLoading()
    }

    private fun initialLoading() {
        viewModelScope.launch {
            // 体验优化：微延时 150ms 让位给导航转场动画，防止进入阅读器时闪烁卡顿
            kotlinx.coroutines.delay(150)
            
            // 核心功能点：单本选项精准加载。所有配置项现在均以漫画 ID 为唯一枢纽进行独立存档
            val cid = matchedComicId
            _isRtl.value = prefs.getBoolean("rtl_$cid", false)
            _isSharpenEnabled.value = prefs.getBoolean("sharpen_$cid", false)
            _isVolumeKeyEnabled.value = prefs.getBoolean("vol_paging_$cid", false)
            xyz.sakulik.comic.utils.VolumeKeyHandler.isEnabled = _isVolumeKeyEnabled.value
            
            val savedMode = prefs.getInt("reader_mode_$cid", -1)
            if (savedMode != -1) {
                _readerMode.value = ReaderMode.values()[savedMode]
            }

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
                
                // 即入置顶策略：只要成功打开漫画，就立即更新最后阅读时间
                updateProgress(entity.currentPage, pageCount)
            } catch (e: Exception) {
                e.printStackTrace()
                val message = when {
                    e.message?.contains("ENOSPC") == true -> "手机存储空间不足，无法加载漫画"
                    e is java.io.FileNotFoundException -> "找不到漫画文件，请检查 SD 卡是否已卸载"
                    else -> e.message ?: "解析失败，可能是文件损坏或格式不支持"
                }
                _state.value = ComicState.Error(message)
            }
        }
    }

    fun updateProgress(page: Int, totalPages: Int) {
        val entity = currentEntity ?: return
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            try {
                dao.updateProgress(entity.id, page, totalPages, System.currentTimeMillis())
            } catch (e: Exception) { e.printStackTrace() }
        }
    }

    /**
     * [Phase 2] 将当前页设为封面，并同步导出元数据
     */
    fun setAsCover(pageIndex: Int, onComplete: (Boolean) -> Unit = {}) {
        val entity = currentEntity ?: return
        viewModelScope.launch(Dispatchers.IO) {
            // 1. 获取现有封面路径并清理旧系统生成的封面（如果在内部 covers 目录）
            val coverDir = File(getApplication<Application>().filesDir, "covers").apply { mkdirs() }
            
            // 物理清理旧文件，防止存储空间膨胀，且有助于路径变更触发 UI 刷新
            entity.coverCachePath?.let { path ->
                val oldFile = File(path)
                if (oldFile.exists() && oldFile.parentFile?.absolutePath == coverDir.absolutePath) {
                    oldFile.delete()
                }
            }

            // 2. 始终生成新的 UUID 文件名
            // 关键：Coil 是基于文件路径缓存的，路径变化是强制触发 BookshelfScreen 刷新的最稳健方案
            val finalCoverFile = File(coverDir, "${java.util.UUID.randomUUID()}.webp")

            // 3. 物理提取
            val success = xyz.sakulik.comic.model.scanner.CoverExtractor.extractPageToCache(
                context = getApplication(),
                uri = Uri.parse(entity.uri),
                extension = entity.extension,
                pageIndex = pageIndex,
                outPath = finalCoverFile
            )

            if (success) {
                // 4. 更新数据库：包含自定义页码记录和新封面路径
                val updated = entity.copy(
                    customCoverPage = pageIndex,
                    coverCachePath = finalCoverFile.absolutePath
                )
                dao.update(updated)
                currentEntity = updated // 同步内存状态

                // 5. 对接：触发 ComicInfo.xml 同级导出
                xyz.sakulik.comic.model.metadata.LocalComicInfoWriter.writeCompanionXml(getApplication(), updated)
                
                withContext(Dispatchers.Main) {
                    onComplete(true)
                }
            } else {
                withContext(Dispatchers.Main) {
                    onComplete(false)
                }
            }
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
        // 核心性能优化：使用显式后台作用域执行 IO 清理，解决退出动画卡顿
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            clearCache()
            pageLoader?.close()
        }
        // 恢复音量键状态
        xyz.sakulik.comic.utils.VolumeKeyHandler.isEnabled = false
    }
}
