package xyz.sakulik.comic.model.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(
    tableName = "comic_books",
    indices = [androidx.room.Index(value = ["location"], unique = true)]
)
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
    val rating: Float? = null,       //\ 从 0.0 到 100 的评分

    // 卷/章节层级管理系统与阵营分离 (Phase 2 重构强切入)
    val region: ComicRegion = ComicRegion.UNKNOWN,
    val format: ComicFormat = ComicFormat.UNKNOWN,
    val seriesName: String = "",     // 脱敏洗牌后的核心序列组名，用作折叠判定的唯一枢纽
    val issueTitle: String? = null,  // 每一期的独立标题 (如: The Night Gwen Stacy Died)
    val remoteSeriesId: String? = null, // 远程追踪 ID (如 ComicVine Volume ID)
    val issueNumber: Float? = null,  //\ 第几话/期，使用 Float 兼容 15 倍等增刊号
    val volumeNumber: Float? = null, // 第几卷，大分册管理单元
    val year: String? = null,        // 发行年份 (智能防冲突层关键字段)

    // Hybrid Architecture (Local + Cloud)
    val source: ComicSource = ComicSource.LOCAL,
    val location: String = "", // 如果 LOCAL，存本地文件绝对路径或 SAF Uri；如果 REMOTE，存服务端的 Comic ID 或基础 URL
    
    // 增量扫描护航 (Phase 3)
    val lastModified: Long = 0,
    val fileSize: Long = 0,

    // 备注与自定义别名 (Phase 4)
    val remark: String? = null
)
