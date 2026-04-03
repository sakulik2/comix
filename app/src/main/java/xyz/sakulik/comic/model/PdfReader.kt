package xyz.sakulik.comic.model

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 原生 PDF 解析器，提供动态渲染
 */
class PdfReader(private val pfd: ParcelFileDescriptor) : ComicBook, PdfEngine {
    private val pdfRenderer = PdfRenderer(pfd)
    
    // PdfRenderer 限制同时只能 open 一页，加入 Mutex 互斥锁防止水平滚动时并发崩溃
    private val mutex = Mutex()

    override val pageCount: Int
        get() = pdfRenderer.pageCount

    override suspend fun getPage(index: Int): PageResult = withContext(Dispatchers.IO) {
        mutex.withLock {
            val page = pdfRenderer.openPage(index)
            val w = page.width
            val h = page.height
            page.close()
            PageResult.DynamicPdf(this@PdfReader, index, w, h)
        }
    }

    override suspend fun renderPage(pageIndex: Int, scale: Float, viewport: Rect): Bitmap = withContext(Dispatchers.IO) {
        mutex.withLock {
            val page = pdfRenderer.openPage(pageIndex)
            
            // 为了防止异常视口导致过大 Bitmap，添加一点容差与安全判断，不过通常由 UI 控制
            val validWidth = viewport.width().coerceAtLeast(1)
            val validHeight = viewport.height().coerceAtLeast(1)
            
            val dstBitmap = Bitmap.createBitmap(validWidth, validHeight, Bitmap.Config.ARGB_8888)
            dstBitmap.eraseColor(android.graphics.Color.WHITE) // PDF 默认透明，需要抹为白色背景

            val matrix = Matrix()
            // 放大比例
            matrix.postScale(scale, scale)
            // 平移以对齐可视区域左上角到画板 (0,0)
            matrix.postTranslate(-viewport.left.toFloat(), -viewport.top.toFloat())

            // 渲染显示
            page.render(dstBitmap, null, matrix, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            page.close()
            dstBitmap
        }
    }

    override fun close() {
        pdfRenderer.close()
        pfd.close()
    }
}
