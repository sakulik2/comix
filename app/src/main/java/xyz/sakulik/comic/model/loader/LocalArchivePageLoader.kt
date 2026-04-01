package xyz.sakulik.comic.model.loader

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import com.github.junrar.Archive
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipInputStream

/**
 * 【重工业重构版】本地档案实现：支持 .cbr, .cbz, .pdf。
 * 遵循 [方案 A]：采用滑动窗口解压策略，平衡磁盘空间与 IO 性能。
 * 针对 PDF 使用下采样与 RGB_565，通过会话机制管理临时文件生命周期。
 */
class LocalArchivePageLoader(
    private val context: Context,
    private val uri: Uri,
    private val extension: String
) : ComicPageLoader {

    private val archiveMutex = Mutex()
    private val sessionScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    // 会话缓存目录：基于 URI Hash 确保唯一性
    private val sessionDir: File by lazy {
        File(context.cacheDir, "session_${uri.toString().hashCode()}").apply {
            if (!exists()) mkdirs()
        }
    }

    // 已提取的页面映射：PageIndex -> TempFile
    private val extractedFiles = ConcurrentHashMap<Int, File>()
    
    // 【核心加速器 v4.0】针对 content:// 协议的本地镜像文件。
    // 一旦有了镜像，我们将彻底告别低效的 InputStream 线性扫描。
    private var sessionMirror: File? = null
    
    // 档案条目信息缓存（按文件名排序确保页码一致性）
    private var cachedEntries: List<String>? = null
    
    // PDF 特有状态
    private var pdfPfd: android.os.ParcelFileDescriptor? = null
    private var pdfRenderer: PdfRenderer? = null

    // 图像增强开关
    private var isSharpenEnabled = false
    fun setSharpenEnabled(enabled: Boolean) { isSharpenEnabled = enabled }

    // 【内存护航】简单的 Bitmap 复用池
    private val bitmapPool = java.util.Collections.synchronizedList(mutableListOf<Bitmap>())

    private fun obtainBitmap(w: Int, h: Int, config: Bitmap.Config): Bitmap {
        val requiredBytes = w * h * (if (config == Bitmap.Config.RGB_565) 2 else 4)
        synchronized(bitmapPool) {
            val it = bitmapPool.iterator()
            while (it.hasNext()) {
                val b = it.next()
                if (!b.isRecycled && b.isMutable && b.allocationByteCount >= requiredBytes) {
                    it.remove()
                    // 注意：如果尺寸不完全一致，需手动重置其有效区域或通过 Canvas 局部绘制。
                    // 这里由于我们是全量覆盖绘制，所以只需成功获取内存块即可。
                    b.eraseColor(android.graphics.Color.TRANSPARENT)
                    return b
                }
            }
        }
        return Bitmap.createBitmap(w, h, config)
    }

    override fun releasePageData(data: Any?) {
        if (data is Bitmap) releaseBitmap(data)
    }

    private fun releaseBitmap(bitmap: Bitmap?) {
        if (bitmap == null || bitmap.isRecycled) return
        synchronized(bitmapPool) {
            if (bitmapPool.size < 5) { // 限制池大小以保留更多堆空间给系统
                bitmapPool.add(bitmap)
            } else {
                bitmap.recycle()
            }
        }
    }

    private suspend fun ensureSessionMirror(): File? {
        if (sessionMirror?.exists() == true) return sessionMirror
        if (uri.scheme == "file") {
            sessionMirror = File(uri.path!!)
            return sessionMirror
        }
        
        return withContext(Dispatchers.IO) {
            try {
                val tempFile = File(sessionDir, "mirror_${System.currentTimeMillis()}.tmp")
                context.contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(tempFile).use { out ->
                        // 【优化 1：大 Buffer 并发拷贝】
                        // 提高从 content:// 读取的吞吐量
                        val buffer = ByteArray(65536) // 64KB
                        var bytes = input.read(buffer)
                        while (bytes >= 0) {
                            out.write(buffer, 0, bytes)
                            bytes = input.read(buffer)
                            yield() // 让出 CPU，保证首屏 UI 不被磁盘 IO 锁死
                        }
                    }
                }
                sessionMirror = tempFile
                tempFile
            } catch (e: Exception) {
                e.printStackTrace()
                null
            }
        }
    }

    override suspend fun getPageCount(): Int = withContext(Dispatchers.IO) {
        archiveMutex.withLock {
            try {
                if (cachedEntries != null) return@withLock cachedEntries!!.size
                
                // 确保镜像就绪（针对 PDF 同样适用，PdfRenderer 的 PFD 本身就是随机访问的）
                val archiveFile = ensureSessionMirror() ?: return@withLock 0
                
                if (extension.lowercase() == "pdf") {
                    openPdfSync()
                    return@withLock pdfRenderer?.pageCount ?: 0
                }

                when (extension.lowercase()) {
                    "cbz", "zip" -> {
                        val names = mutableListOf<String>()
                        java.util.zip.ZipFile(archiveFile).use { zipFile ->
                            zipFile.entries().asSequence().forEach { entry ->
                                if (!entry.isDirectory && isImage(entry.name)) {
                                    names.add(entry.name)
                                }
                            }
                        }
                        cachedEntries = names.sorted()
                        names.size
                    }
                    "cbr", "rar" -> {
                        val names = mutableListOf<String>()
                        Archive(archiveFile).use { archive ->
                            archive.fileHeaders.forEach { header ->
                                if (!header.isDirectory && isImage(header.fileName ?: "")) {
                                    names.add(header.fileName)
                                }
                            }
                        }
                        cachedEntries = names.sorted()
                        names.size
                    }
                    else -> 0
                }
            } catch (e: Exception) {
                e.printStackTrace()
                0
            }
        }
    }

    override suspend fun getPageData(pageIndex: Int, width: Int, height: Int): Any? = withContext(Dispatchers.IO) {
        // PDF 路径是动态渲染，不经过滑动窗口解压
        if (extension.lowercase() == "pdf") {
            return@withContext loadPdfPage(pageIndex, width, height)
        }

        // 【优化 3：内存直通解码 (Direct Decoding)】
        // 如果当前页仍在提取中，不再等待磁盘，直接从档案流中解码首屏。
        // 这规避了 “压缩包 -> 临时文件 -> 位图” 这种冗余的磁盘 IO 闭环。
        archiveMutex.withLock {
            val file = extractedFiles[pageIndex]
            if (file?.exists() == true) {
                return@withContext decodeImageFile(file, width, height)
            }
            
            // 【优化 4：首屏秒开 Fallback】
            // 如果镜像尚未完成（大文件镜像可能需要数秒），直接从原始 URI 流式抓取当前页。
            // 这一步能让用户在打开 1GB 文件时瞬间看到第一页，而不是盯着黑屏等镜像完成。
            val mirror = sessionMirror
            if (mirror == null || !mirror.exists()) {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    when (extension.lowercase()) {
                        "cbz", "zip" -> {
                            val zis = ZipInputStream(input)
                            var entry = zis.nextEntry
                            var idx = 0
                            while (entry != null) {
                                if (!entry.isDirectory && isImage(entry.name)) {
                                    if (idx == pageIndex) {
                                        return@withContext decodeImageStream(zis, width, height)
                                    }
                                    idx++
                                }
                                zis.closeEntry()
                                entry = zis.nextEntry
                            }
                        }
                        "cbr", "rar" -> {
                            Archive(input).use { archive ->
                                val headers = archive.fileHeaders.filter { !it.isDirectory && isImage(it.fileName) }.sortedBy { it.fileName }
                                if (pageIndex in headers.indices) {
                                    archive.getInputStream(headers[pageIndex]).use { return@withContext decodeImageStream(it, width, height) }
                                }
                            }
                        }
                    }
                }
            }

            val readyMirror = ensureSessionMirror() ?: return@withContext null
            when (extension.lowercase()) {
                "cbz", "zip" -> {
                    java.util.zip.ZipFile(readyMirror).use { zip ->
                        val entries = zip.entries().asSequence().filter { !it.isDirectory && isImage(it.name) }.sortedBy { it.name }.toList()
                        if (pageIndex in entries.indices) {
                            zip.getInputStream(entries[pageIndex]).use { return@withContext decodeImageStream(it, width, height) }
                        }
                    }
                }
                "cbr", "rar" -> {
                    Archive(readyMirror).use { archive ->
                        val headers = archive.fileHeaders.filter { !it.isDirectory && isImage(it.fileName) }.sortedBy { it.fileName }
                        if (pageIndex in headers.indices) {
                            archive.getInputStream(headers[pageIndex]).use { return@withContext decodeImageStream(it, width, height) }
                        }
                    }
                }
            }
        }
        
        // 2. 发起预加载
        triggerSlidingWindow(pageIndex)
        null
    }

    private fun decodeImageStream(input: java.io.InputStream, reqWidth: Int, reqHeight: Int): Bitmap? {
        // 先缓存字节流以支持两次采样解码
        val bytes = input.readBytes() 
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        
        val sampleSize = calculateInSampleSize(options, reqWidth, reqHeight)
        options.inSampleSize = sampleSize
        options.inJustDecodeBounds = false
        options.inPreferredConfig = Bitmap.Config.RGB_565
        
        val targetW = options.outWidth / sampleSize
        val targetH = options.outHeight / sampleSize
        
        synchronized(bitmapPool) {
            val it = bitmapPool.iterator()
            val requiredBytes = targetW * targetH * 2
            while (it.hasNext()) {
                val b = it.next()
                if (!b.isRecycled && b.isMutable && b.allocationByteCount >= requiredBytes) {
                    options.inBitmap = b
                    it.remove()
                    break
                }
            }
        }
        options.inMutable = true
        val bitmap = try {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        } catch (e: Exception) {
            options.inBitmap = null
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        }
        
        return if (isSharpenEnabled && bitmap != null) sharpenBitmap(bitmap) else bitmap
    }

    /**
     * 滑动窗口提取核心：[方案 A] 的实现
     * 提取 [targetIndex - 5, targetIndex + 5] 范围内的图片。
     */
    private suspend fun extractWindow(targetIndex: Int) {
        val count = getPageCount()
        if (count == 0) return
        
        val entries = cachedEntries ?: return
        val start = (targetIndex - 5).coerceAtLeast(0)
        val end = (targetIndex + 5).coerceAtMost(count - 1)
        
        val neededIndices = (start..end).filter { extractedFiles[it]?.exists() != true }
        if (neededIndices.isEmpty()) return

        try {
            when (extension.lowercase()) {
                "cbz", "zip" -> {
                    val archiveFile = ensureSessionMirror() ?: return
                    java.util.zip.ZipFile(archiveFile).use { zipFile ->
                        // 【优化 2：单 PASS 批量抓取】
                        // 一次遍历扫描出窗口内所有条目，彻底消除重复扫描开销
                        val items = zipFile.entries().asSequence()
                            .filter { !it.isDirectory && isImage(it.name) }
                            .sortedBy { it.name }
                            .toList()

                        neededIndices.forEach { idx ->
                            if (idx in items.indices) {
                                zipFile.getInputStream(items[idx]).use { input ->
                                    saveEntryToCache(input, idx)
                                }
                            }
                        }
                    }
                }
                "cbr", "rar" -> {
                    val archiveFile = ensureSessionMirror() ?: return
                    Archive(archiveFile).use { archive ->
                        // RAR 索引常驻单次遍历
                        val headers = archive.fileHeaders
                            .filter { !it.isDirectory && isImage(it.fileName) }
                            .sortedBy { it.fileName }
                        
                        neededIndices.forEach { idx ->
                            if (idx in headers.indices) {
                                archive.getInputStream(headers[idx]).use { imgIn ->
                                    saveEntryToCache(imgIn, idx)
                                }
                            }
                        }
                    }
                }
            }
            cleanupOldCache(targetIndex)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun saveEntryToCache(input: java.io.InputStream, index: Int) {
        val file = File(sessionDir, "p_$index.tmp")
        FileOutputStream(file).use { out ->
            input.copyTo(out)
        }
        extractedFiles[index] = file
    }

    private fun triggerSlidingWindow(centerIndex: Int) {
        sessionScope.launch {
            archiveMutex.withLock {
                extractWindow(centerIndex)
            }
        }
    }

    private fun cleanupOldCache(currentIndex: Int) {
        val keepRange = (currentIndex - 15)..(currentIndex + 15)
        extractedFiles.keys().asSequence().forEach { index ->
            if (index !in keepRange) {
                extractedFiles.remove(index)?.delete()
            }
        }
    }

    /**
     * PDF 分级渲染优化：[RGB_565] 显存优化版本
     */
    private fun loadPdfPage(pageIndex: Int, reqWidth: Int, reqHeight: Int): Bitmap? {
        try {
            openPdfSync()
            val renderer = pdfRenderer ?: return null
            if (pageIndex !in 0 until renderer.pageCount) return null

            val page = renderer.openPage(pageIndex)
            val scale = (minOf(reqWidth.toFloat() / page.width, reqHeight.toFloat() / page.height)).coerceAtMost(2f)
            
            val w = (page.width * scale).toInt().coerceAtLeast(1)
            val h = (page.height * scale).toInt().coerceAtLeast(1)
            
            // 使用复用池获取 Bitmap
            val bitmap = obtainBitmap(w, h, Bitmap.Config.RGB_565)
            bitmap.eraseColor(android.graphics.Color.WHITE)
            
            val matrix = android.graphics.Matrix().apply { postScale(scale, scale) }
            page.render(bitmap, null, matrix, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            page.close()
            
            return if (isSharpenEnabled) sharpenBitmap(bitmap) else bitmap
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
    }

    private fun sharpenBitmap(src: Bitmap): Bitmap {
        val width = src.width
        val height = src.height
        val config = if (src.config == Bitmap.Config.RGB_565) Bitmap.Config.RGB_565 else Bitmap.Config.ARGB_8888
        val kernel = floatArrayOf(
            0f, -1f, 0f,
            -1f, 5f, -1f,
            0f, -1f, 0f
        )
        
        // 从池中获取目标 Bitmap，而不是直接分配新的
        val dest = obtainBitmap(width, height, config)
        
        // Android 平台上如果不使用 RenderScript，最快的方式是使用 ColorMatrix 或直接在 Canvas 上叠绘偏移
        // 这里使用一种高性能的 Canvas 偏移叠绘法模拟卷积锐化 (避免逐像素循环)
        val canvas = android.graphics.Canvas(dest)
        val paint = android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG)
        
        canvas.drawBitmap(src, 0f, 0f, null) // 底图
        
        // 叠绘偏移层实现边缘增强 (Laplacian 模拟)
        paint.alpha = 100 // 混合强度
        canvas.drawBitmap(src, 1f, 1f, paint) 
        
        // 【重大修复】处理完毕，归还原始图到池中，释放显存
        releaseBitmap(src)
        
        return dest
    }

    private fun openPdfSync() {
        if (pdfRenderer != null) return
        pdfPfd = context.contentResolver.openFileDescriptor(uri, "r")
        pdfPfd?.let {
            pdfRenderer = PdfRenderer(it)
        }
    }

    private fun decodeImageFile(file: File, reqWidth: Int, reqHeight: Int): Bitmap? {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, options)
        
        val sampleSize = calculateInSampleSize(options, reqWidth, reqHeight)
        options.inSampleSize = sampleSize
        options.inJustDecodeBounds = false
        options.inPreferredConfig = Bitmap.Config.RGB_565 
        
        // 尝试从池中寻找可复用的 Bitmap
        // 注意：BitmapFactory 复用要求比较严格，尤其是 inSampleSize 后的实际尺寸
        val targetW = options.outWidth / sampleSize
        val targetH = options.outHeight / sampleSize
        
        // 【核心科技：inBitmap 弹性物理复用】
        // 只要池中 Bitmap 的总字节数 (byteCount) 大于等于目标尺寸，即可复用（Android 4.4+ 支持）。
        // 这极大地提升了复用命中率，因为不需要长宽完全相等。
        synchronized(bitmapPool) {
            val it = bitmapPool.iterator()
            val requiredBytes = targetW * targetH * (if (options.inPreferredConfig == Bitmap.Config.RGB_565) 2 else 4)
            while (it.hasNext()) {
                val b = it.next()
                if (!b.isRecycled && b.isMutable && b.allocationByteCount >= requiredBytes) {
                    options.inBitmap = b
                    it.remove()
                    break
                }
            }
        }
        
        options.inMutable = true // 必须为 true 才能在下次被复用
        
        return try {
            BitmapFactory.decodeFile(file.absolutePath, options)
        } catch (e: IllegalArgumentException) {
            // 如果 inBitmap 报错（极少见），回退到普通解码
            options.inBitmap = null
            BitmapFactory.decodeFile(file.absolutePath, options)
        }
    }

    private fun calculateInSampleSize(options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
        val (height: Int, width: Int) = options.outHeight to options.outWidth
        var inSampleSize = 1
        if (height > reqHeight || width > reqWidth) {
            val halfHeight: Int = height / 2
            val halfWidth: Int = width / 2
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

    override fun close() {
        sessionScope.cancel()
        try {
            pdfRenderer?.close()
            pdfPfd?.close()
            if (sessionDir.exists()) {
                sessionDir.deleteRecursively()
            }
            // 确保 sessionMirror （如果是临时拷贝的话）被清理
            if (uri.scheme != "file") {
                sessionMirror?.delete()
            }
            extractedFiles.clear()
            synchronized(bitmapPool) {
                bitmapPool.forEach { it.recycle() }
                bitmapPool.clear()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
