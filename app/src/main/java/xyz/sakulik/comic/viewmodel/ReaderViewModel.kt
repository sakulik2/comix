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
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.util.Log
import xyz.sakulik.comic.model.preferences.SettingsDataStore
import xyz.sakulik.comic.model.network.RetrofitClient
import xyz.sakulik.comic.model.network.ComicApiService
import xyz.sakulik.comic.model.db.ComicDao
import xyz.sakulik.comic.model.db.ComicEntity
import xyz.sakulik.comic.model.loader.ComicPageLoader
import xyz.sakulik.comic.model.loader.ComicPageLoaderFactory
import xyz.sakulik.comic.model.loader.LocalArchivePageLoader
import xyz.sakulik.comic.model.loader.LocalPdfPageLoader
import xyz.sakulik.comic.model.loader.RemoteStreamPageLoader
import xyz.sakulik.comic.model.loader.RemoteResourceLimitException
import xyz.sakulik.comic.R
import xyz.sakulik.comic.utils.LocalizedIllegalStateException
import xyz.sakulik.comic.utils.UiText
import xyz.sakulik.comic.utils.toUiText
import xyz.sakulik.comic.model.loader.RemoteResourceLimits
import xyz.sakulik.comic.navigation.ReaderRoute
import java.io.File
import androidx.navigation.toRoute
enum class ReaderMode(val storageId: String) {
    PAGER("pager"),
    DUAL_PAGE("dual_page"),
    WEBTOON("webtoon");

    companion object {
        fun fromStorageId(storageId: String?): ReaderMode? {
            return entries.firstOrNull { it.storageId == storageId }
        }
    }
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
        (pageLoader as? LocalPdfPageLoader)?.setSharpenEnabled(enabled)
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
        prefs.edit().putString("reader_mode_$cid", _readerMode.value.storageId).apply()
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
            
            val readerModeKey = "reader_mode_$cid"
            when (val savedMode = prefs.all[readerModeKey]) {
                is String -> {
                    _readerMode.value = ReaderMode.fromStorageId(savedMode) ?: ReaderMode.PAGER
                }
                is Int -> {
                    _readerMode.value = ReaderMode.entries.getOrNull(savedMode) ?: ReaderMode.PAGER
                    prefs.edit().putString(readerModeKey, _readerMode.value.storageId).apply()
                }
                else -> {
                    _readerMode.value = ReaderMode.PAGER
                }
            }

            _state.value = ComicState.Loading
            try {
                // 根据 ID 查询漫画实体
                var entity = dao.getComicById(matchedComicId)
                    ?: throw LocalizedIllegalStateException(
                        UiText.Res(R.string.error_comic_not_found, listOf(matchedComicId.toString())),
                        "no comic record for id $matchedComicId"
                    )
                
                currentEntity = entity
                val context = getApplication<Application>()

                // 如果是远程漫画，动态与服务端同步最新的解压状态和总页数，支持自愈与轮询等待
                if (entity.source == xyz.sakulik.comic.model.db.ComicSource.REMOTE) {
                    val baseUrl = SettingsDataStore.getComicApiBaseUrlFlow(context).firstOrNull()
                    if (baseUrl.isNullOrBlank()) {
                        throw LocalizedIllegalStateException(
                            UiText.Res(R.string.error_remote_not_configured),
                            "remote API base URL is not configured"
                        )
                    }
                    val apiService = RetrofitClient.createService(
                        context = context,
                        baseUrl = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/",
                        serviceClass = ComicApiService::class.java
                    )
                    
                    var isReady = false
                    var remotePages = 0
                    var retryCount = 0
                    val safeComicId = RemoteResourceLimits.validateComicId(entity.location)
                    val maxReadyChecks = 120
                    val readyCheckDelayMillis = 5_000L
                    
                    // 大文件服务端解压可能持续数分钟，保持可取消轮询并识别明确失败状态
                    while (!isReady && retryCount < maxReadyChecks) {
                        try {
                            val detail = apiService.getComicDetail(safeComicId)
                            RemoteResourceLimits.validateComicId(detail.id)
                            if (detail.id != safeComicId) {
                                throw RemoteResourceLimitException(
                                    UiText.Res(R.string.error_remote_detail_id_mismatch),
                                    "remote detail id does not match the request"
                                )
                            }
                            if (detail.status.equals("failed", ignoreCase = true)) {
                                // 服务端返回的错误文本本地无法翻译，原样展示
                                throw RemoteResourceLimitException(
                                    detail.error?.takeIf { it.isNotBlank() }?.let { UiText.Raw(it) }
                                        ?: UiText.Res(R.string.error_remote_server_failed),
                                    "server reported a failed comic: ${detail.error}"
                                )
                            }
                            isReady = detail.isReady
                            remotePages = RemoteResourceLimits.validatePageCount(
                                detail.totalPages,
                                allowZero = !isReady
                            )
                            if (!isReady) {
                                _state.value = ComicState.Loading // 保持加载状态
                                Log.d("ReaderViewModel", "remote extraction in progress, poll #${retryCount + 1}")
                                kotlinx.coroutines.delay(readyCheckDelayMillis)
                                retryCount++
                            }
                        } catch (e: RemoteResourceLimitException) {
                            throw e
                        } catch (e: Exception) {
                            if (e is kotlinx.coroutines.CancellationException) throw e
                            Log.e("ReaderViewModel", "remote detail request failed", e)
                            kotlinx.coroutines.delay(readyCheckDelayMillis)
                            retryCount++
                        }
                    }

                    if (isReady && remotePages > 0) {
                        // 仅当就绪且页数大于 0 时，同步更新本地数据库的总页数与缓存状态
                        val updatedEntity = entity.copy(totalPages = remotePages)
                        dao.update(updatedEntity)
                        entity = updatedEntity // 替换为最新实体以供后续 loader 创建使用
                        currentEntity = updatedEntity
                    } else {
                        throw LocalizedIllegalStateException(
                            UiText.Res(R.string.error_remote_not_ready),
                            "remote comic is not ready yet"
                        )
                    }
                }

                val uri = Uri.parse(entity.uri)
                // 优先使用数据库中存储的扩展名，如果为空则尝试从路径解析
                val ext = entity.extension.ifBlank {
                    uri.lastPathSegment?.substringAfterLast('.', "")?.lowercase() ?: ""
                }

                clearCache()
                
                // 使用工厂创建对应的加载引擎
                val loader = loaderFactory.create(entity)
                (loader as? LocalArchivePageLoader)?.setSharpenEnabled(_isSharpenEnabled.value)
                (loader as? LocalPdfPageLoader)?.setSharpenEnabled(_isSharpenEnabled.value)
                (loader as? RemoteStreamPageLoader)?.setSharpenEnabled(_isSharpenEnabled.value)
                pageLoader = loader
                
                val pageCount = loader.getPageCount()
                if (pageCount == 0) throw LocalizedIllegalStateException(
                    UiText.Res(R.string.error_no_pages),
                    "loader reported zero pages"
                )
                if (pageCount > RemoteResourceLimits.MAX_TOTAL_PAGES) {
                    throw LocalizedIllegalStateException(
                        UiText.Res(
                            R.string.error_remote_total_pages,
                            listOf(pageCount, RemoteResourceLimits.MAX_TOTAL_PAGES)
                        ),
                        "page count out of range: $pageCount"
                    )
                }

                _state.value = ComicState.Ready(pageCount, entity.title, ext, uri, loader)
                
                // 即入置顶策略：只要成功打开漫画，就立即更新最后阅读时间
                updateProgress(entity.currentPage.coerceIn(0, pageCount - 1), pageCount)
            } catch (e: Exception) {
                e.printStackTrace()
                _state.value = ComicState.Error(e.toUiText())
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
