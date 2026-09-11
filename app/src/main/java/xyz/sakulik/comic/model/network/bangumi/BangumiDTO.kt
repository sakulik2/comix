package xyz.sakulik.comic.model.network.bangumi

import com.google.gson.annotations.SerializedName
import xyz.sakulik.comic.model.metadata.ScrapeSource
import xyz.sakulik.comic.model.metadata.ScrapedComicInfo
import xyz.sakulik.comic.model.db.ComicRegion

/**
 * Bangumi API v0 的原生条目响应格式
 */
data class BangumiSearchResponse(
    val results: Int,
    val list: List<BangumiSubject>?
)

data class BangumiSubject(
    val id: Long,
    val name: String?,
    @SerializedName("name_cn") val nameCn: String?,
    val summary: String?,
    val images: BangumiImages?
) {
    /**
     * 防腐转换扩展函数，优先提取中文翻译名称
     */
    fun toDomainModel(): ScrapedComicInfo {
        // 兜底留空串，由 UI 层决定显示什么，映射层不产出用户文案
        val finalTitle = nameCn?.takeIf { it.isNotBlank() } ?: name ?: ""
        return ScrapedComicInfo(
            title = finalTitle,
            coverUrl = images?.large ?: images?.common,
            authors = emptyList(), // Bangumi 列表中未直接展平
            summary = summary,
            genres = emptyList(),
            publisher = null,
            rating = null,
            source = ScrapeSource.BANGUMI,
            region = ComicRegion.MANGA
        )
    }
}

data class BangumiImages(
    val large: String?,
    val common: String?
)
