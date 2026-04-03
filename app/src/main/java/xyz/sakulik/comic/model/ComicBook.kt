package xyz.sakulik.comic.model

import android.graphics.Bitmap
import android.graphics.Rect
import java.io.Closeable
import java.io.File

/**
 * 专门用于处理 PDF 动态渲染的高级接口
 */
interface PdfEngine {
    /**
     * 根据指定的缩放比例 (scale) 和可视区域 (viewport)，渲染所需的一块 Bitmap 图
     * 避免了缩放后整体高分辨率 PDF 造成的 OOM
     */
    suspend fun renderPage(pageIndex: Int, scale: Float, viewport: Rect): Bitmap
}

/**
 * 统一获取 ComicBook 页面的返回结果
 * 图片类文件直接返回可供 Coil 读取的文件
 * 对于 PDF 提供具备动态视口渲染能力的 PdfEngine
 */
sealed class PageResult {
    data class ImageFile(val file: File) : PageResult()
    data class DynamicPdf(
        val engine: PdfEngine,
        val pageIndex: Int,
        val originalWidth: Int,
        val originalHeight: Int
    ) : PageResult()
}

/**
 * 各种类型漫画阅读器的抽象接口
 */
interface ComicBook : Closeable {
    val pageCount: Int
    suspend fun getPage(index: Int): PageResult
}
