package xyz.sakulik.comic.model.db

/**
 * 标定该资源的原始发行归属区域
 */
enum class ComicRegion(val displayName: String) {
    COMIC("美漫/欧漫"),
    MANGA("日漫/国漫"),
    UNKNOWN("未分类")
}
