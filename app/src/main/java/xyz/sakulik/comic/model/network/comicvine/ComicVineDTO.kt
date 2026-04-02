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
    val results: List<ComicVineSearchResultItem>?
)

data class ComicVineSearchResultItem(
    val id: Long,
    val name: String?,
    @SerializedName("resource_type") val resourceType: String?,
    val deck: String?,
    val description: String?,
    val image: ComicVineImage?,
    val publisher: ComicVinePublisher?,
    @SerializedName("start_year") val startYear: String?,
    // Issue specific
    @SerializedName("issue_number") val issueNumber: String?,
    val volume: ComicVineVolumeShort?
) {
    fun toDomainModel(): ScrapedComicInfo {
        return if (resourceType == "issue") {
            ScrapedComicInfo(
                title = volume?.name ?: "Unknown Series",
                coverUrl = image?.originalUrl ?: image?.superUrl,
                authors = emptyList(),
                summary = deck ?: description?.replace(Regex("<.*?>"), ""),
                genres = emptyList(),
                publisher = null,
                rating = null,
                source = ScrapeSource.COMIC_VINE,
                region = ComicRegion.COMIC,
                format = ComicFormat.ISSUE,
                remoteId = volume?.id?.toString(),
                issueTitle = name,
                issueNumber = issueNumber?.toFloatOrNull(),
                year = startYear
            )
        } else {
            val titleStr = name ?: "未知标题"
            val titleLower = titleStr.lowercase()
            val parsedFormat = when {
                titleLower.contains("tpb") || titleLower.contains("trade paperback") || titleLower.contains("vol") -> ComicFormat.TPB
                titleLower.contains("hc") || titleLower.contains("hardcover") || titleLower.contains("deluxe edition") -> ComicFormat.HC
                titleLower.contains("omnibus") -> ComicFormat.OMNIBUS
                else -> ComicFormat.ISSUE
            }
            ScrapedComicInfo(
                title = titleStr,
                coverUrl = image?.originalUrl ?: image?.superUrl,
                authors = emptyList(),
                summary = deck ?: description?.replace(Regex("<.*?>"), ""),
                genres = emptyList(),
                publisher = publisher?.name,
                rating = null,
                source = ScrapeSource.COMIC_VINE,
                region = ComicRegion.COMIC,
                format = parsedFormat,
                remoteId = id.toString(),
                year = startYear
            )
        }
    }
}

data class ComicVineIssueResponse(
    val error: String,
    val results: List<ComicVineIssue>?
)

data class ComicVineVolume(
    val id: Long,
    val name: String?,
    val deck: String?,           // 简短摘要
    val description: String?,    // 详细 HTML 描述
    val image: ComicVineImage?,
    val publisher: ComicVinePublisher?,
    @SerializedName("start_year") val startYear: String?,
    @SerializedName("count_of_issues") val countOfIssues: Int?
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
            format = parsedFormat,
            remoteId = id.toString(),
            year = startYear
        )
    }
}

data class ComicVineIssue(
    val id: Long,
    val name: String?,
    @SerializedName("issue_number") val issueNumber: String?,
    val deck: String?,
    val description: String?,
    val image: ComicVineImage?,
    @SerializedName("volume") val volume: ComicVineVolumeShort?
) {
    fun toDomainModel(): ScrapedComicInfo {
        return ScrapedComicInfo(
            title = volume?.name ?: "Unknown Series",
            coverUrl = image?.originalUrl ?: image?.superUrl,
            authors = emptyList(),
            summary = deck ?: description?.replace(Regex("<.*?>"), ""),
            genres = emptyList(),
            publisher = null, // Issue 详情中通常不直接带出版社，需要从 Volume 获取
            rating = null,
            source = ScrapeSource.COMIC_VINE,
            region = ComicRegion.COMIC,
            format = ComicFormat.ISSUE,
            remoteId = volume?.id?.toString(),
            issueTitle = name,
            issueNumber = issueNumber?.toFloatOrNull(),
            year = null
        )
    }
}

data class ComicVineVolumeShort(
    val id: Long,
    val name: String?
)

data class ComicVineImage(
    @SerializedName("original_url") val originalUrl: String?,
    @SerializedName("super_url") val superUrl: String?
)

data class ComicVinePublisher(
    val name: String?
)
