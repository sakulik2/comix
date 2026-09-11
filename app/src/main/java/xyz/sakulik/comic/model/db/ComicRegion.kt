package xyz.sakulik.comic.model.db

import androidx.annotation.StringRes
import xyz.sakulik.comic.R

/**
 * 标定该资源的原始发行归属区域
 *
 * 只携带资源 id，渲染时由 UI 层 stringResource(labelRes) 解析，model 层不需要 Context。
 */
enum class ComicRegion(@param:StringRes val labelRes: Int) {
    COMIC(R.string.region_comic),
    MANGA(R.string.region_manga),
    UNKNOWN(R.string.region_unknown)
}
