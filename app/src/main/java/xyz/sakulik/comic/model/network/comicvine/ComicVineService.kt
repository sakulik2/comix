package xyz.sakulik.comic.model.network.comicvine

import retrofit2.http.GET
import retrofit2.http.Query

/**
 * Retrofit 管理下的 Comic Vine 服务抓取器
 * Header 拦截器会自动监控这个域名并塞入 API Key
 */
interface ComicVineService {
    
    @GET("api/search/")
    suspend fun searchVolumes(
        @Query("query") query: String,
        @Query("resources") resources: String = "volume",
        @Query("format") format: String = "json",
        @Query("limit") limit: Int = 10
    ): ComicVineResponse
}
