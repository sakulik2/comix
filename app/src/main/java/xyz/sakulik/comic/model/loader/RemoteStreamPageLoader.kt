package xyz.sakulik.comic.model.loader

import xyz.sakulik.comic.model.network.ComicApiService

/**
 * 云端流媒体实现：通过 Retrofit 的 API 定义来获取页面。
 */
class RemoteStreamPageLoader(
    private val comicId: String,
    private val totalPages: Int,
    private val baseUrl: String,
    private val apiService: ComicApiService? = null
) : ComicPageLoader {

    override suspend fun getPageCount(): Int {
        // 如果 API 允许通过 getPageCount 同步延迟加载，
        // 可以在此处执行 apiService?.getComicDetail(comicId)?.totalPages
        return totalPages
    }

    override suspend fun getPageData(pageIndex: Int, width: Int, height: Int): Any? {
        // 按照用户要求的格式：https://comix.sakulik.xyz/api/comics/{id}/page/{index}
        val normalizedBaseUrl = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
        return "${normalizedBaseUrl}api/comics/$comicId/page/$pageIndex"
    }

    override fun close() {
        // 释放网络资源或清理会话
    }
}
