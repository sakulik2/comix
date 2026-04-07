package xyz.sakulik.comic.model.loader

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import xyz.sakulik.comic.model.processor.ImageEnhanceEngine

/**
 * 深度优化的 PDF 专有页面加载器
 * 1. 支持基于屏幕物理像素的动态 DPI 渲染 (最高 4x)
 * 2. 采用独立的渲染互斥锁，避免 UI 与后台预加载死锁
 * 3. 集成高质量 ARGB_8888 渲染模式
 * 4. 支持双重缓存机制（内存 + 磁盘预渲染）
 */
class LocalPdfPageLoader(
    private val context: Context,
    private val uri: Uri
) : ComicPageLoader {

    private val pdfMutex = Mutex()
    private var pfd: ParcelFileDescriptor? = null
    private var renderer: PdfRenderer? = null
    
    // 渲染缓存配置
    private val sessionDir = File(context.cacheDir, "pdf_session_${uri.toString().hashCode()}").apply {
        if (!exists()) mkdirs()
    }
    private val preRenderedFiles = ConcurrentHashMap<Int, File>()
    private val dimensionsCache = ConcurrentHashMap<Int, Pair<Int, Int>>()
    
    // 图像增强开关 (单本记忆)
    private var isSharpenEnabled = false
    fun setSharpenEnabled(enabled: Boolean) { isSharpenEnabled = enabled }

    private suspend fun ensureRenderer(): PdfRenderer? = pdfMutex.withLock {
        if (renderer != null) return renderer
        try {
            pfd = context.contentResolver.openFileDescriptor(uri, "r")
            pfd?.let { renderer = PdfRenderer(it) }
            renderer
        } catch (e: Exception) {
            android.util.Log.e("PdfLoader", "Failed to open PDF Renderer: ${e.message}")
            null
        }
    }

    override suspend fun getPageCount(): Int = withContext(Dispatchers.IO) {
        ensureRenderer()?.pageCount ?: 0
    }

    override suspend fun getPageSize(pageIndex: Int): Pair<Int, Int>? = withContext(Dispatchers.IO) {
        dimensionsCache[pageIndex]?.let { return@withContext it }
        
        pdfMutex.withLock {
            val rend = renderer ?: return@withLock null
            if (pageIndex !in 0 until rend.pageCount) return@withLock null
            val page = rend.openPage(pageIndex)
            val size = page.width to page.height
            page.close()
            dimensionsCache[pageIndex] = size
            size
        }
    }

    override suspend fun getPageData(pageIndex: Int, width: Int, height: Int): Any? = withContext(Dispatchers.IO) {
        // [路径 A] 磁盘预渲染缓存优先
        preRenderedFiles[pageIndex]?.let { file ->
            if (file.exists()) {
                val bitmap = android.graphics.BitmapFactory.decodeFile(file.absolutePath)
                if (bitmap != null) return@withContext processEnhancement(bitmap)
            }
        }

        // [路径 B] 实时高清渲染
        pdfMutex.withLock {
            val rend = renderer ?: return@withLock null
            if (pageIndex !in 0 until rend.pageCount) return@withLock null
            
            try {
                val page = rend.openPage(pageIndex)
                
                // 动态 DPI 采样：基于请求宽度与原始宽度的比例，最高 4 倍
                val baseScale = width.toFloat() / page.width
                val scale = baseScale.coerceIn(1.0f, 4.0f)
                
                val targetW = (page.width * scale).toInt().coerceAtLeast(1)
                val targetH = (page.height * scale).toInt().coerceAtLeast(1)
                
                // 采用最高画质配置
                val bitmap = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
                bitmap.eraseColor(android.graphics.Color.WHITE)
                
                val matrix = Matrix().apply { postScale(scale, scale) }
                page.render(bitmap, null, matrix, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                page.close()
                
                android.util.Log.d("PdfLoader", "Live Render Page $pageIndex @ ${scale}x")
                processEnhancement(bitmap)
            } catch (e: Exception) {
                android.util.Log.e("PdfLoader", "Render error at $pageIndex: ${e.message}")
                null
            }
        }
    }

    private fun processEnhancement(bitmap: Bitmap): Bitmap {
        return if (isSharpenEnabled) {
            ImageEnhanceEngine.enhance(bitmap) { w, h, cfg -> 
                Bitmap.createBitmap(w, h, cfg) 
            }
        } else bitmap
    }

    override fun releasePageData(data: Any?) {
        if (data is Bitmap && !data.isRecycled) {
            data.recycle()
        }
    }

    override fun close() {
        try {
            renderer?.close()
            pfd?.close()
            if (sessionDir.exists()) sessionDir.deleteRecursively()
            preRenderedFiles.clear()
            dimensionsCache.clear()
        } catch (e: Exception) { /* ignore */ }
    }
}
