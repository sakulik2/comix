package xyz.sakulik.comic.model.metadata

/**
 * 指定抓取引擎时的决策行为树，对应 UI 端 FilterChip 的选项
 */
enum class ScrapeStrategy(val displayName: String) {
    SMART_FALLBACK("双核容灾优先"),
    COMIC_VINE_ONLY("仅限 ComicVine"),
    BANGUMI_ONLY("仅限 Bangumi")
}
