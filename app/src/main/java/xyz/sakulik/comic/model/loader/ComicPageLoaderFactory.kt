package xyz.sakulik.comic.model.loader

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.flow.firstOrNull
import xyz.sakulik.comic.model.db.ComicEntity
import xyz.sakulik.comic.model.db.ComicSource
import xyz.sakulik.comic.model.network.ComicApiService
import xyz.sakulik.comic.model.network.RetrofitClient
import xyz.sakulik.comic.model.preferences.SettingsDataStore

/**
 * 漫画加载器工厂根据 ComicEntity 的 source 类型创建对应的加载引擎
 */
class ComicPageLoaderFactory(private val context: Context) {

    private var _apiService: ComicApiService? = null

    private suspend fun getApiService(): ComicApiService {
        val current = _apiService
        if (current != null) return current
        
        // 从 DataStore 中获取用户配置的 API 基础地址
        val baseUrlFromSettings = SettingsDataStore.getComicApiBaseUrlFlow(context).firstOrNull()
            ?: "https://comix.sakulik.xyz/"

        return RetrofitClient.createService(
            context = context,
            baseUrl = if (baseUrlFromSettings.endsWith("/")) baseUrlFromSettings else "$baseUrlFromSettings/",
            serviceClass = ComicApiService::class.java
        ).also { _apiService = it }
    }

    suspend fun create(comic: ComicEntity): ComicPageLoader {
        return when (comic.source) {
            ComicSource.LOCAL -> {
                val uri = if (comic.location.startsWith("content://") || comic.location.startsWith("file://")) {
                    Uri.parse(comic.location)
                } else {
                    Uri.fromFile(java.io.File(comic.location))
                }
                
                if (comic.extension.lowercase() == "pdf") {
                    LocalPdfPageLoader(context, uri)
                } else {
                    LocalArchivePageLoader(context, uri, comic.extension)
                }
            }
            ComicSource.REMOTE -> {
                val baseUrl = SettingsDataStore.getComicApiBaseUrlFlow(context).firstOrNull() ?: "https://comix.sakulik.xyz/"
                RemoteStreamPageLoader(
                    context = context,
                    comicId = comic.location,
                    totalPages = comic.totalPages,
                    baseUrl = baseUrl
                )
            }
        }
    }
}
