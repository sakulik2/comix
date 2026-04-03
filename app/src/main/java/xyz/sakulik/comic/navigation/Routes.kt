package xyz.sakulik.comic.navigation

import kotlinx.serialization.Serializable

/**
 * 依托于 Navigation Compose 2.8+ 的强类型安全路由表
 * 通过 @Serializable 实现物理级的不可篡改与防崩溃传递！
 */

@Serializable
object HomeRoute // 首页漫画库

@Serializable
data class SeriesDetailRoute(val seriesName: String = "") // 书系详情页


@Serializable
data class ReaderRoute(
    val comicId: Long,
    val initialPage: Int = 0
) // 承接底仓 OOM 免疫图片引擎的阅读页

@Serializable
object SettingsRoute // 全局配置页面

@Serializable
data class MetadataSearchRoute(val comicId: Long) // 手动刮削搜索页
