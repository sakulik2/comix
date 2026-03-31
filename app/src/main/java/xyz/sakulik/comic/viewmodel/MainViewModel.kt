package xyz.sakulik.comic.viewmodel

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import xyz.sakulik.comic.model.CbrReader
import xyz.sakulik.comic.model.CbzReader
import xyz.sakulik.comic.model.ComicBook
import xyz.sakulik.comic.model.PdfReader
import xyz.sakulik.comic.model.db.AppDatabase
import xyz.sakulik.comic.model.db.ComicEntity
import java.io.File
import java.io.FileOutputStream

sealed class ComicState {
    object Idle : ComicState()
    object Loading : ComicState()
    data class Error(val message: String) : ComicState()
    data class Ready(val pageCount: Int, val fileName: String, val extension: String, val uri: Uri) : ComicState()
}

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val _state = MutableStateFlow<ComicState>(ComicState.Idle)
    val state: StateFlow<ComicState> = _state.asStateFlow()

    private val _isRtl = MutableStateFlow(false)
    val isRtl: StateFlow<Boolean> = _isRtl.asStateFlow()

    fun toggleRtl() {
        _isRtl.value = !_isRtl.value
    }

    // 缓存目录，存解压出的图片
    private val comicCacheDir = File(application.cacheDir, "comic_cache")
    // 用于转存 SAF URI 的压缩包，供 java.util.zip/junrar 调用
    private val tempFile = File(application.cacheDir, "current_comic.tmp")

    init {
        if (!comicCacheDir.exists()) {
            comicCacheDir.mkdirs()
        }
    }

    private val dao by lazy { AppDatabase.getDatabase(application).comicDao() }
    private var currentEntity: ComicEntity? = null

    fun openComic(comic: ComicEntity) {
        currentEntity = comic
        openComicUri(Uri.parse(comic.uri), comic.title)
    }

    private fun openComicUri(uri: Uri, comicTitle: String) {
        viewModelScope.launch {
            _state.value = ComicState.Loading
            try {
                clearCache()

                val context = getApplication<Application>()
                val fileName = comicTitle
                val ext = fileName.substringAfterLast('.', "").lowercase()

                val pageCount = xyz.sakulik.comic.model.scanner.ComicPageLoader.getPageCount(context, uri, ext)
                if (pageCount == 0) throw IllegalStateException("无法解析页面书目，该格式 ($ext) 不被支持或文件已损坏！")

                // 因为不需要实例化笨重的全量解压缩器，所以现在打开一本包含几百张图的文件，可以做到近乎零秒耗时。
                _state.value = ComicState.Ready(pageCount, fileName, ext, uri)
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
        if (tempFile.exists()) {
            tempFile.delete()
        }
    }

    override fun onCleared() {
        super.onCleared()
        clearCache()
    }
}
