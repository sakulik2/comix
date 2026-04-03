package xyz.sakulik.comic.model.db

/**
 * 漫画发行格式
 */
enum class ComicFormat(val displayName: String) {
    ISSUE("单期 (Issue)"),
    CHAPTER("单话 (Chapter)"),
    TPB("平装合订本 (TPB)"),
    HC("精装本 (HC)"),
    OMNIBUS("大合订本 (Omnibus)"),
    TANKOBON("单行本 (Tankobon)"),
    UNKNOWN("未知格式")
}
