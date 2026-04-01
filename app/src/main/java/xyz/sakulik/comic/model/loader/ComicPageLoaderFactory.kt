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
 * 智能加载器工厂：根据 ComicEntity 的 source 字段分发适合的加载引擎。
 */
class ComicPageLoaderFactory(private val context: Context) {

    private var _apiService: ComicApiService? = null

    private suspend fun getApiService(): ComicApiService {
        val current = _apiService
        if (current != null) return current
        
        // 【动态域名适配】 优先从 DataStore 抓取用户自定义的服务器主地址
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
                
                LocalArchivePageLoader(context, uri, comic.extension)
            }
            ComicSource.REMOTE -> {
                val baseUrl = SettingsDataStore.getComicApiBaseUrlFlow(context).firstOrNull() ?: "https://comix.sakulik.xyz/"
                RemoteStreamPageLoader(
                    comicId = comic.location, // 如果是云端，location 存的是漫画的 Slugs/ID (例如 lailastarr)
                    totalPages = comic.totalPages,
                    baseUrl = baseUrl,
                    apiService = getApiService()
                )
            }
        }
    }
}
