package xyz.sakulik.comic.model.network

import retrofit2.http.GET
import retrofit2.http.Path

/**
 * 针对云端漫画服务器的 API 定义。
 * 配合 Retrofit 自动解构后端传输的 JSON 数据。
 */
interface ComicApiService {

    /**
     * 【主界面同步】 获取服务器上所有的漫画列表详情。
     */
    @GET("api/comics")
    suspend fun getComics(): List<CloudComicItem>

    /**
     * 获取特定漫画的详细元数据（例如总页数）。
     */
    @GET("api/comics/{id}")
    suspend fun getComicDetail(@Path("id") comicId: String): ComicDetailResponse
    
    // ... 原有的 getPageUrl 定义
}

data class CloudComicItem(
    val id: String,
    val originalName: String,
    val coverUrl: String,
    val isReady: Boolean,
    val totalPages: Int
)

data class ComicDetailResponse(
    val id: String,
    val title: String,
    val totalPages: Int,
    val coverUrl: String
)
