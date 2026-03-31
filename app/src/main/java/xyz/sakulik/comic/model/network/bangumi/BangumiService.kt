package xyz.sakulik.comic.model.network.bangumi

import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * Bangumi 番组计划抓取接口
 * 无需特殊身份认证（只要 HeaderInterceptor 中自带合法的 User-Agent 即可避免 403）
 */
interface BangumiService {
    
    @GET("search/subject/{keywords}")
    suspend fun searchSubjects(
        @Path("keywords") keywords: String,
        @Query("type") type: Int = 1, // 类型 1 代表书籍/轻小说/漫画
        @Query("responseGroup") responseGroup: String = "small",
        @Query("max_results") maxResults: Int = 10
    ): BangumiSearchResponse
}
