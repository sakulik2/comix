package xyz.sakulik.comic.model.db

/**
 * 标定此资源的实际连载/发行体例层级（美漫最高纯度精细级拆分版）
 */
enum class ComicFormat(val displayName: String) {
    ISSUE("单期特报(Issue)"),
    CHAPTER("连载单话(Chapter)"),
    TPB("平装商业合集(TPB)"),
    HC("硬皮精装图鉴(HC)"),
    OMNIBUS("终极总集篇(Omnibus)"),
    TANKOBON("日系单行本(Tankobon)"),
    UNKNOWN("未知形态散本")
}
