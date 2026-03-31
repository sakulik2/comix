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
import xyz.sakulik.comic.navigation.ReaderRoute
import java.io.File
import androidx.navigation.toRoute
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

    fun toggleRtl() { _isRtl.value = !_isRtl.value }

    private val comicCacheDir = File(application.cacheDir, "comic_cache")
    private val tempFile = File(application.cacheDir, "current_comic.tmp")

    // 保存当前正在攻克的实体坐标
    var currentEntity: ComicEntity? = null
        private set

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
                val pageCount = xyz.sakulik.comic.model.scanner.ComicPageLoader.getPageCount(context, uri, ext)
                if (pageCount == 0) throw IllegalStateException("解析图集书目失败，引擎不支持 ($ext) 或档案腐败！")

                _state.value = ComicState.Ready(pageCount, entity.title, ext, uri)
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
        // 联动底层加载引擎，释放沉重的 CBR 物理备份
        xyz.sakulik.comic.model.scanner.ComicPageLoader.clearActiveCache(getApplication())
    }

    override fun onCleared() {
        super.onCleared()
        clearCache()
    }
}
