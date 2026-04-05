package xyz.sakulik.comic.model.metadata

import org.simpleframework.xml.Element
import org.simpleframework.xml.Root

/**
 * 符合 ComicRack 规范的 ComicInfo.xml 映射模型
 * 虽然我们使用 XmlPullParser 手动解析以减少依赖，
 * 但保留注解以便以后可能切换回 SimpleXML 或其他序列化库。
 */
@Root(name = "ComicInfo", strict = false)
data class ComicInfo(
    @field:Element(name = "Title", required = false)
    var title: String? = null,

    @field:Element(name = "Series", required = false)
    var series: String? = null,

    @field:Element(name = "Number", required = false)
    var number: String? = null,

    @field:Element(name = "Count", required = false)
    var count: Int? = null,

    @field:Element(name = "Volume", required = false)
    var volume: Int? = null,

    @field:Element(name = "Year", required = false)
    var year: Int? = null,

    @field:Element(name = "Month", required = false)
    var month: Int? = null,

    @field:Element(name = "Day", required = false)
    var day: Int? = null,

    @field:Element(name = "Writer", required = false)
    var writer: String? = null,

    @field:Element(name = "Penciller", required = false)
    var penciller: String? = null,

    @field:Element(name = "Inker", required = false)
    var inker: String? = null,

    @field:Element(name = "Letterer", required = false)
    var letterer: String? = null,

    @field:Element(name = "CoverArtist", required = false)
    var coverArtist: String? = null,

    @field:Element(name = "Editor", required = false)
    var editor: String? = null,

    @field:Element(name = "Publisher", required = false)
    var publisher: String? = null,

    @field:Element(name = "Genre", required = false)
    var genre: String? = null,

    @field:Element(name = "Summary", required = false)
    var summary: String? = null,

    @field:Element(name = "Manga", required = false)
    var manga: String? = null, // Yes, No, YesAndRightToLeft

    @field:Element(name = "CommunityRating", required = false)
    var rating: Float? = null
) {
    // 助手方法：组合出所有作者/参与者
    fun getAllAuthors(): String? {
        val authors = listOfNotNull(writer, penciller, inker, coverArtist)
            .flatMap { it.split(",").map { s -> s.trim() } }
            .distinct()
        return if (authors.isEmpty()) null else authors.joinToString(", ")
    }
}
