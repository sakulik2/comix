package xyz.sakulik.comic.model.metadata

import androidx.annotation.StringRes
import xyz.sakulik.comic.R

/**
 * 指定抓取引擎时的决策行为树，对应 UI 端 FilterChip 的选项
 */
enum class ScrapeStrategy(@param:StringRes val labelRes: Int) {
    SMART_FALLBACK(R.string.scrape_smart_fallback),
    COMIC_VINE_ONLY(R.string.scrape_comic_vine_only),
    BANGUMI_ONLY(R.string.scrape_bangumi_only)
}
