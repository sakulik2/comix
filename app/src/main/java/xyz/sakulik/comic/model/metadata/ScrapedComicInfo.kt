package xyz.sakulik.comic.model.metadata
import xyz.sakulik.comic.model.db.ComicRegion

/**
 * 刮削数据源标识
 */
enum class ScrapeSource {
    COMIC_VINE,
    BANGUMI
}

/**
 * 通用的独立领域层刮削信息体
 * 不依附具体的后端 API 格式，代表最终呈现到界面的脏数据清洗结果
 */
data class ScrapedComicInfo(
    val title: String,                // 书名
    val coverUrl: String?,            // 封面图 URL
    val authors: List<String>,        // 作者列表
    val summary: String?,             // 剧情简介
    val genres: List<String>,         // 类型、题材标签
    val publisher: String?,           // 出版社
    val rating: Float?,               // 本平台评分 (0.0 到 10.0)
    val source: ScrapeSource,         // 数据来源于哪个子系统
    val region: ComicRegion,          // 极其重要的阵营划分
    val format: xyz.sakulik.comic.model.db.ComicFormat = xyz.sakulik.comic.model.db.ComicFormat.UNKNOWN, // 发行格式推知（默认为前线离线推测的兜底）
    val remoteId: String? = null,     // 远程数据库中的 ID (用于系列追踪)
    val issueTitle: String? = null,   // 具体的期号标题 (如: The Night Gwen Stacy Died)
    val issueNumber: Float? = null,  // 远程数据库中的独立期号
    val year: String? = null          // 发行年份 (如: 2012)
)
