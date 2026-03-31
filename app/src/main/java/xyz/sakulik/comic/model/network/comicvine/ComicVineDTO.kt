package xyz.sakulik.comic.model.network.comicvine

import com.google.gson.annotations.SerializedName
import xyz.sakulik.comic.model.metadata.ScrapeSource
import xyz.sakulik.comic.model.metadata.ScrapedComicInfo

import xyz.sakulik.comic.model.db.ComicRegion
import xyz.sakulik.comic.model.db.ComicFormat

/**
 * 欧美地区强大的漫画资料库 ComicVine 的响应体
 */
data class ComicVineResponse(
    val error: String,
    val results: List<ComicVineVolume>?
)

data class ComicVineVolume(
    val id: Long,
    val name: String?,
    val deck: String?,           // 简短摘要
    val description: String?,    // 详细 HTML 描述
    val image: ComicVineImage?,
    val publisher: ComicVinePublisher?
) {
    /**
     * 将第三方脏数据防腐转换为统一领域模型
     */
    fun toDomainModel(): ScrapedComicInfo {
        val titleStr = name ?: "未知标题"
        val titleLower = titleStr.lowercase()
        
        val parsedFormat = when {
            titleLower.contains("tpb") || titleLower.contains("trade paperback") || titleLower.contains("vol") -> ComicFormat.TPB
            titleLower.contains("hc") || titleLower.contains("hardcover") || titleLower.contains("deluxe edition") -> ComicFormat.HC
            titleLower.contains("omnibus") -> ComicFormat.OMNIBUS
            titleStr.contains("Issue") || titleStr.contains("#") -> ComicFormat.ISSUE
            else -> ComicFormat.ISSUE // 默认单期
        }

        return ScrapedComicInfo(
            title = titleStr,
            coverUrl = image?.originalUrl ?: image?.superUrl,
            authors = emptyList(), // ComicVine 的 Search API 不包含下属的 Person 角色解析
            summary = deck ?: description?.replace(Regex("<.*?>"), ""), // 剥去 HTML 标签
            genres = emptyList(),
            publisher = publisher?.name,
            rating = null,
            source = ScrapeSource.COMIC_VINE,
            region = ComicRegion.COMIC,
            format = parsedFormat
        )
    }
}

data class ComicVineImage(
    @SerializedName("original_url") val originalUrl: String?,
    @SerializedName("super_url") val superUrl: String?
)

data class ComicVinePublisher(
    val name: String?
)
