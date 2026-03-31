package xyz.sakulik.comic.model.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "comic_books")
data class ComicEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val title: String,
    val uri: String, // 唯一标识：文件的 URI
    val extension: String, // pdf, cbr, cbz
    val totalPages: Int = 0,
    val currentPage: Int = 0,
    val lastReadTime: Long = 0,
    val addedTime: Long = System.currentTimeMillis(),
    val coverCachePath: String? = null, // 本地封面缓存路径

    // Metadata 扩展字段 (Phase 1)
    val series: String? = null,
    val authors: String? = null,     // 支持多个作者存为逗号分隔字符串
    val summary: String? = null,
    val genres: String? = null,      // 支持多个题材存为逗号分隔字符串
    val publisher: String? = null,
    val rating: Float? = null,       // 从 0.0 到 10.0 的评分

    // 卷/章节层级管理系统与阵营分离 (Phase 2 重构强切入)
    val region: ComicRegion = ComicRegion.UNKNOWN,
    val format: ComicFormat = ComicFormat.UNKNOWN,
    val seriesName: String = "",     // 脱敏洗牌后的核心序列组名，用作折叠判定的唯一枢纽
    val issueNumber: Float? = null,  // 第几话/期，使用 Float 兼容 1.5 倍等增刊号
    val volumeNumber: Float? = null  // 第几卷，大分册管理单元
)
