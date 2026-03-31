package xyz.sakulik.comic.model.scanner

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import com.github.junrar.Archive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipInputStream

object ComicPageLoader {
    private var currentCbrUri: String? = null
    // 【高性能核心】缓存 CBR 的文件头索引，避免翻页时重复扫描 GB 级压缩包
    private var cachedCbrHeaders: List<com.github.junrar.rarfile.FileHeader>? = null
    // 【句柄锁定】持久化活动中的 Archive 句柄，避免每翻一页都产生 15s 的震荡性 I/O 损耗
    private var activeArchive: Archive? = null
    
    // 【并发安全护盾】 解决 HorizontalPager 全量并发请求引发的句柄互斥冲突
    private val archiveMutex = Mutex()

    /**
     * 【存储爆破专家】 显式销毁当前处于活跃阅读状态的 CBR 物理副本。
     */
    fun clearActiveCache(context: Context) {
        val tempFile = File(context.cacheDir, "reader_active.cbr")
        if (tempFile.exists()) {
            tempFile.delete()
        }
        try {
            activeArchive?.close() 
        } catch (e: Exception) { e.printStackTrace() }
        
        activeArchive = null
        currentCbrUri = null
        cachedCbrHeaders = null 
    }

    suspend fun loadPageBitmap(
        context: Context, 
        uri: Uri, 
        extension: String, 
        pageIndex: Int,
        reqWidth: Int, 
        reqHeight: Int
    ): Bitmap? = withContext(Dispatchers.IO) {
        // [护盾启动] 全局锁定，确保 Pager 的 5 个并发请求在此按顺序排队进入
        archiveMutex.withLock {
            try {
                when (extension.lowercase()) {
                    "pdf" -> loadPdfPage(context, uri, pageIndex, reqWidth, reqHeight)
                    "cbz", "zip" -> loadCbzPage(context, uri, pageIndex, reqWidth, reqHeight)
                    "cbr", "rar" -> loadCbrPage(context, uri, pageIndex, reqWidth, reqHeight)
                    else -> null
                }
            } catch (e: Exception) {
                e.printStackTrace()
                null
            }
        }
    }

    suspend fun getPageCount(context: Context, uri: Uri, extension: String): Int = withContext(Dispatchers.IO) {
        try {
            when (extension.lowercase()) {
                "pdf" -> {
                    val pfd = context.contentResolver.openFileDescriptor(uri, "r") ?: return@withContext 0
                    val count = PdfRenderer(pfd).also { it.close() }.pageCount
                    pfd.close()
                    count
                }
                "cbz", "zip" -> {
                    var count = 0
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        val zis = ZipInputStream(input)
                        var entry = zis.nextEntry
                        while (entry != null) {
                            if (!entry.isDirectory && isImage(entry.name)) count++
                            zis.closeEntry()
                            entry = zis.nextEntry
                        }
                    }
                    count
                }
            "cbr", "rar" -> {
                    // 使用固定缓冲区文件替代随机时间戳，防止因崩溃导致的文件累积
                    val tempFile = File(context.cacheDir, "scanner_buffer.cbr")
                    try {
                        context.contentResolver.openInputStream(uri)?.use { input ->
                            tempFile.outputStream().use { output -> input.copyTo(output) }
                        }
                        val archive = Archive(tempFile)
                        val count = archive.fileHeaders.count { !it.isDirectory && isImage(it.fileName) }
                        archive.close()
                        count
                    } finally {
                        if (tempFile.exists()) tempFile.delete()
                    }
                }
                else -> 0
            }
        } catch (e: Exception) {
            e.printStackTrace()
            0
        }
    }

    private fun loadPdfPage(context: Context, uri: Uri, pageIndex: Int, reqWidth: Int, reqHeight: Int): Bitmap? {
        val pfd = context.contentResolver.openFileDescriptor(uri, "r") ?: return null
        val renderer = PdfRenderer(pfd)
        if (pageIndex < 0 || pageIndex >= renderer.pageCount) {
            renderer.close()
            pfd.close()
            return null
        }
        val page = renderer.openPage(pageIndex)
        // 缩放适配请求尺寸，防止 OOM
        val scaleW = reqWidth.toFloat() / page.width.toFloat()
        val scaleH = reqHeight.toFloat() / page.height.toFloat()
        // 适应长边的 downscale，放大不得超过两倍，以防 PDF 中的极小内嵌图导致爆内存
        val scale = minOf(scaleW, scaleH).coerceAtMost(2f)

        val w = (page.width * scale).toInt().coerceAtLeast(1)
        val h = (page.height * scale).toInt().coerceAtLeast(1)
        
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.WHITE)
        
        val matrix = Matrix().apply { postScale(scale, scale) }
        page.render(bitmap, null, matrix, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        
        page.close()
        renderer.close()
        pfd.close()
        return bitmap
    }

    /**
     * 【硬核极速解码组】采用深层 Zip 流寻轨技术。跳开文件盘，直走 RAM！
     */
    private fun loadCbzPage(context: Context, uri: Uri, targetIndex: Int, reqWidth: Int, reqHeight: Int): Bitmap? {
        context.contentResolver.openInputStream(uri)?.use { input ->
            val zis = ZipInputStream(input)
            var currentImageIndex = 0
            
            // 为了保证极致的防 OOM 性与极简的闪存占用（即“零中间临时文件”理念），
            // 在此我们利用 Zip 流的高速前滚探针，直接扫略 `nextEntry` 物理索引表。
            var entry = zis.nextEntry
            while (entry != null) {
                if (!entry.isDirectory && isImage(entry.name)) {
                    if (currentImageIndex == targetIndex) {
                        return decodeSampledBitmapFromStream(zis, reqWidth, reqHeight)
                    }
                    currentImageIndex++
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
        return null
    }

    private fun loadCbrPage(context: Context, uri: Uri, targetIndex: Int, reqWidth: Int, reqHeight: Int): Bitmap? {
        val tempFile = File(context.cacheDir, "reader_active.cbr")
        val uriStr = uri.toString()
        
        try {
            // 1. 【性能堡垒】只有换书时才重新索引。空间占用始终 ≤ 1本漫画大小。
            if (currentCbrUri != uriStr || !tempFile.exists()) {
                activeArchive?.close() // 换书，必须立刻释放老句柄
                context.contentResolver.openInputStream(uri)?.use { input ->
                    tempFile.outputStream().use { output -> input.copyTo(output) }
                }
                currentCbrUri = uriStr
                cachedCbrHeaders = null 
                // 立刻建立全书索引与物理关联
                activeArchive = Archive(tempFile)
                cachedCbrHeaders = activeArchive!!.fileHeaders
                    .filter { !it.isDirectory && isImage(it.fileName) }
                    .sortedBy { it.fileName }
            }

            val archive = activeArchive ?: return null
            val imageHeaders = cachedCbrHeaders ?: return null

            if (targetIndex in imageHeaders.indices) {
                val targetHeader = imageHeaders[targetIndex]
                
                // 2. 【极速引擎】跳过物理磁盘，直接在内存流中解析！
                archive.getInputStream(targetHeader).use { imgInput ->
                    // 借用 CBZ 的逻辑，直接在此处执行 inSampleSize 下采样解码
                    return decodeSampledBitmapFromStream(imgInput, reqWidth, reqHeight)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            // 如果发生意外，重置句柄以备下次重试
            try { activeArchive?.close() } catch (ex: Exception) {}
            activeArchive = null
            currentCbrUri = null
        }
        return null
    }

    /**
     * 【神级解码器】在流媒体下实行防穿透的 downScale 二次下采样。
     * 现已升级为通用流处理器，同时支持 ZIP 与 RAR 流。
     */
    private fun decodeSampledBitmapFromStream(jis: java.io.InputStream, reqWidth: Int, reqHeight: Int): Bitmap? {
        // [危险领域]：BitmapFactory 对 InputStream 使用 inJustDecodeBounds 会向深水区吃掉流标记，使无法复位。
        // 所以最高效无伤的方案是将这一章轻薄页面的总字节数（约 2-5 MB），整个暂存到 ByteArray 黑盒池里！
        val bytes = jis.readBytes()
        
        // 第一次测绘（雷达扫描虚空长高），绝对不产生 OOM 强行占据物理显存！
        val options = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        
        // 第二次神级裁剪
        options.inSampleSize = calculateInSampleSize(options, reqWidth, reqHeight)
        options.inJustDecodeBounds = false
        options.inPreferredConfig = Bitmap.Config.RGB_565 // （可选极致杀手锏：抛弃透明高光 8888 通道！立省一半显存负担）
        
        // 分配物理显存并在下采样的前提下实例化低规格无感小图
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    }

    private fun decodeSampledBitmapFromFile(path: String, reqWidth: Int, reqHeight: Int): Bitmap? {
        val options = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        BitmapFactory.decodeFile(path, options)
        
        options.inSampleSize = calculateInSampleSize(options, reqWidth, reqHeight)
        options.inJustDecodeBounds = false
        options.inPreferredConfig = Bitmap.Config.RGB_565
        
        return BitmapFactory.decodeFile(path, options)
    }

    private fun calculateInSampleSize(options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
        val (height: Int, width: Int) = options.outHeight to options.outWidth
        var inSampleSize = 1

        if (height > reqHeight || width > reqWidth) {
            val halfHeight: Int = height / 2
            val halfWidth: Int = width / 2
            // 采用倍数式阶梯缩小法
            while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }

    private fun isImage(name: String): Boolean {
        val lower = name.lowercase()
        return lower.endsWith(".jpg") || lower.endsWith(".jpeg") ||
               lower.endsWith(".png") || lower.endsWith(".webp")
    }
}
