package xyz.sakulik.comic.model.db

/**
 * 漫画发行格式
 *
 * 只作为分类依据参与逻辑判断，不直接渲染到界面；
 * 若将来需要展示，按 ComicRegion 的方式加 @StringRes labelRes。
 */
enum class ComicFormat {
    ISSUE,
    CHAPTER,
    TPB,
    HC,
    OMNIBUS,
    TANKOBON,
    UNKNOWN
}
