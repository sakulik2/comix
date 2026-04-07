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
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import java.io.FileDescriptor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.coroutines.coroutineContext
import xyz.sakulik.comic.model.processor.ImageEnhanceEngine

import net.sf.sevenzipjbinding.IInArchive
import net.sf.sevenzipjbinding.IInStream
import net.sf.sevenzipjbinding.PropID
import net.sf.sevenzipjbinding.SevenZip
import net.sf.sevenzipjbinding.ISequentialOutStream
import net.sf.sevenzipjbinding.ExtractOperationResult
import net.sf.sevenzipjbinding.IArchiveExtractCallback
import net.sf.sevenzipjbinding.ExtractAskMode

/**
 * 本地档案页面加载器，支持 .cbr, .cbz, .pdf 格式
 * 采用滑动窗口解压策略，平衡磁盘空间与 IO 性能
 * 平行维护 content:// URI 的本地镜像，实现随机访问
 */
class LocalArchivePageLoader(
    private val context: Context,
    private val uri: Uri,
    private val extension: String
) : ComicPageLoader {

    private val archiveMutex = Mutex()
    private val mirrorMutex = Mutex()
    private val sessionScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    // 会话缓存目录：基于 URI Hash 确保唯一性
    private val sessionDir: File by lazy {
        File(context.cacheDir, "session_${uri.toString().hashCode()}").apply {
            if (!exists()) mkdirs()
        }
    }

    // 已提取的页面映射：PageIndex -> TempFile
    private val extractedFiles = ConcurrentHashMap<Int, File>()
    
    // 页面原始像素尺寸缓存：PageIndex -> (Width, Height) [核心性能优化]
    private val pageDimensionsCache = ConcurrentHashMap<Int, Pair<Int, Int>>()
    
    // content:// URI 的本地镜像文件，用于支持 RAR/ZIP 随机访问
    private var sessionMirror: File? = null
    @Volatile private var isMirrorReady = false
    @Volatile private var isStreamingOnly = false
    
    // 档案条目信息缓存（按文件名排序确保页码一致性）
    private var cachedEntries: List<String>? = null
    
    // PDF 特有状态
    private var pdfPfd: android.os.ParcelFileDescriptor? = null
    private var pdfRenderer: PdfRenderer? = null

    // 图像增强开关
    private var isSharpenEnabled = false
    fun setSharpenEnabled(enabled: Boolean) { isSharpenEnabled = enabled }

    // Bitmap 复用池，减少堆分配压力
    private val bitmapPool = java.util.Collections.synchronizedList(mutableListOf<Bitmap>())

    private var activePfd: ParcelFileDescriptor? = null
    private var activeChannel: FileChannel? = null
    private var activeRarArchive: Archive? = null
    private var activeSevenZipArchive: IInArchive? = null
    private var activeZipFile: java.util.zip.ZipFile? = null
    private var activeZipIndexer: ZipFastIndexer? = null
    private var activeSortedHeaders: List<Any>? = null

    private suspend fun ensureActiveSession(): Boolean = withContext(Dispatchers.IO) {
        if (activeChannel != null && (activeRarArchive != null || activeZipIndexer != null)) return@withContext true
        try {
            val pfd = context.contentResolver.openFileDescriptor(uri, "r") ?: return@withContext false
            activePfd = pfd
            val channel = java.io.FileInputStream(pfd.fileDescriptor).channel
            activeChannel = channel
            
            android.util.Log.i("ArchiveLoader", "Session init [Turbo-Random]: $extension (${channel.size()} bytes)")
            
            when (extension.lowercase()) {
                "cbz", "zip" -> {
                    // [Turbo-Random] Primary Page Indexer (Manual NIO)
                    // We prioritize the manual indexer because it's more stable than /proc/self/fd/ZipFile
                    val indexer = ZipFastIndexer(channel)
                    activeZipIndexer = indexer
                    val list = indexer.getEntryNames().filter { isImage(it) }.sorted()
                    activeSortedHeaders = list
                    android.util.Log.i("ArchiveLoader", "ZIP Headers Initialized: ${list.size} pages. First: ${list.firstOrNull()}")
                    
                    // Optional: Still try ZipFile as a high-perf C++ speed alternate
                    val pfdFile = java.io.File("/proc/self/fd/${pfd.fd}")
                    try {
                        if (pfdFile.exists()) {
                            activeZipFile = java.util.zip.ZipFile(pfdFile)
                        }
                    } catch (e: Exception) { /* Silently fall back to indexer */ }
                }
                "cbr", "rar" -> {
                    val version = getRarVersion(channel)
                    if (version == 5) {
                        android.util.Log.i("ArchiveLoader", "RAR V5 Fingerprint detected, direct to SevenZip engine.")
                        try {
                            val nioStream = SevenZipNioStream(channel)
                            val archive = SevenZip.openInArchive(null, nioStream)
                            activeSevenZipArchive = archive
                            
                            val list = mutableListOf<SevenZipMeta>()
                            for (i in 0 until archive.numberOfItems) {
                                val path = archive.getProperty(i, PropID.PATH) as? String ?: continue
                                val isFolder = archive.getProperty(i, PropID.IS_FOLDER) as? Boolean ?: false
                                if (!isFolder && isImage(path)) {
                                    list.add(SevenZipMeta(i, path))
                                }
                            }
                            activeSortedHeaders = list.sortedBy { it.path }
                        } catch (e: Exception) {
                            android.util.Log.e("ArchiveLoader", "SevenZip direct open failed: ${e.message}")
                            throw e
                        }
                    } else {
                        android.util.Log.i("ArchiveLoader", "RAR V4 detected, using Junrar engine.")
                        try {
                            val nioChannel = RarNioChannel(channel)
                            val volumeManager = RarVolumeManager(nioChannel)
                            val archive = Archive(volumeManager, null, null)
                            activeRarArchive = archive
                            activeSortedHeaders = archive.getFileHeaders()
                                .filter { !it.isDirectory && isImage(it.fileName ?: "") }
                                .sortedBy { it.fileName }
                        } catch (e: Exception) {
                            android.util.Log.e("ArchiveLoader", "Junrar V4 open failed: ${e.message}")
                            throw e
                        }
                    }
                }
            }
            
            val counts = activeSortedHeaders?.size ?: 0
            if (counts == 0) {
                android.util.Log.e("ArchiveLoader", "Session established but NO image entries found!")
            } else {
                android.util.Log.i("ArchiveLoader", "NIO Index success: found $counts entries.")
            }
            true
        } catch (e: Exception) {
            if (e !is kotlinx.coroutines.CancellationException) {
                android.util.Log.e("ArchiveLoader", "Session failed [CRITICAL]: ${e.message}", e)
            }
            closeActiveSession()
            false
        }
    }

    private fun closeActiveSession() {
        try {
            activeRarArchive?.close()
            activeSevenZipArchive?.close()
            activeZipFile?.close()
            activePfd?.close()
            activeChannel?.close()
        } catch (e: Exception) { /* ignore */ }
        activeRarArchive = null
        activeSevenZipArchive = null
        activeZipFile = null
        activeZipIndexer = null
        activeChannel = null
        activePfd = null
        activeSortedHeaders = null
    }



    override suspend fun getPageSize(pageIndex: Int): Pair<Int, Int>? = withContext(Dispatchers.IO) {
        // [超级极速路径] 命中内存缓存直接返回
        pageDimensionsCache[pageIndex]?.let { return@withContext it }

        // [极速路径] 从已经提取好的本地临时图片中解析尺寸
        extractedFiles[pageIndex]?.let { file ->
            if (file.exists()) {
                val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.absolutePath, opts)
                if (opts.outWidth > 0) {
                    val res = opts.outWidth to opts.outHeight
                    pageDimensionsCache[pageIndex] = res
                    return@withContext res
                }
            }
        }

        // [标准路径] 从原始档案（NIO/Mirror）中读取图片头部信息
        val stream = if (extension.lowercase() in listOf("cbz", "zip")) {
            // [并行优化] ZIP 格式支持并行读取，无需抢占全局锁
            ensureActiveSession()
            val headers = activeSortedHeaders
            if (headers != null && pageIndex in headers.indices) {
                val entry = headers[pageIndex] as String
                activeZipFile?.let { zip ->
                    zip.getEntry(entry)?.let { zip.getInputStream(it) }
                } ?: activeZipIndexer?.getEntryInputStream(entry)
            } else null
        } else {
            // RAR 格式 Junrar 不支持并发，维持互斥锁锁定
            archiveMutex.withLock {
                if (ensureActiveSession()) {
                    val headers = activeSortedHeaders
                    if (headers != null && pageIndex in headers.indices) {
                        activeRarArchive?.getInputStream(headers[pageIndex] as com.github.junrar.rarfile.FileHeader)
                    } else null
                } else null
            }
        }
        
        stream?.use { 
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeStream(it, null, opts)
            if (opts.outWidth > 0) {
                val res = opts.outWidth to opts.outHeight
                pageDimensionsCache[pageIndex] = res
                return@withContext res
            }
        }
        null
    }

    private fun obtainBitmap(w: Int, h: Int, config: Bitmap.Config): Bitmap {
        val requiredBytes = w * h * (if (config == Bitmap.Config.RGB_565) 2 else 4)
        synchronized(bitmapPool) {
            val it = bitmapPool.iterator()
            while (it.hasNext()) {
                val b = it.next()
                if (!b.isRecycled && b.isMutable && b.allocationByteCount >= requiredBytes) {
                    it.remove()
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
            // [性能优化] 提升池容量至 12，支持双页模式下的 翻页预览 + 内存复用
            if (bitmapPool.size < 12) {
                bitmapPool.add(bitmap)
            } else {
                bitmap.recycle()
            }
        }
    }

    private suspend fun ensureSessionMirror(): File? {
        if (isMirrorReady || isStreamingOnly || activeChannel != null) return sessionMirror
        if (uri.scheme == "file") {
            sessionMirror = File(uri.path!!)
            isMirrorReady = true
            return sessionMirror
        }
        
        return withContext(Dispatchers.IO) {
            mirrorMutex.withLock {
                if (isMirrorReady || isStreamingOnly) return@withLock sessionMirror
                
                try {
                    val fileSize = getUriSize(uri)
                    val available = getAvailableSpace()
                    val HUGE_FILE_THRESHOLD = 650L * 1024 * 1024 // 650MB 作为强制流式模式的分水岭
                    if (fileSize > HUGE_FILE_THRESHOLD || available < fileSize + 500 * 1024 * 1024) {
                        isStreamingOnly = true
                        android.util.Log.i("ArchiveLoader", "Storage pressure or large file ($fileSize bytes), skipping mirror.")
                        return@withLock null
                    }

                    val start = System.currentTimeMillis()
                    val tempFile = File(sessionDir, "mirror_${System.currentTimeMillis()}.tmp")
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        tempFile.outputStream().use { out ->
                            input.copyTo(out)
                        }
                    }
                    sessionMirror = tempFile
                    isMirrorReady = true
                    android.util.Log.d("ArchiveLoader", "Mirror created in ${System.currentTimeMillis() - start}ms: ${tempFile.length()} bytes")
                    tempFile
                } catch (e: Exception) {
                    if (e.message?.contains("ENOSPC") == true) {
                        isStreamingOnly = true
                        android.util.Log.w("ArchiveLoader", "ENOSPC detected during mirroring, switching to streaming mode.")
                    }
                    e.printStackTrace()
                    null
                }
            }
        }
    }

    private fun getUriSize(uri: Uri): Long {
        if (uri.scheme == "file") return File(uri.path ?: "").length()
        return try {
            context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getLong(0) else 0L
            } ?: 0L
        } catch (e: Exception) { 0L }
    }

    private fun getAvailableSpace(): Long {
        return try {
            val stat = android.os.StatFs(context.cacheDir.path)
            stat.availableBlocksLong * stat.blockSizeLong
        } catch (e: Exception) { Long.MAX_VALUE }
    }

    override suspend fun getPageCount(): Int = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        android.util.Log.v("ArchiveLoader", "Awaiting archiveMutex for getPageCount...")
        archiveMutex.withLock {
            android.util.Log.v("ArchiveLoader", "Acquired archiveMutex for getPageCount (waited ${System.currentTimeMillis() - startTime}ms)")
            try {
                if (cachedEntries != null) return@withLock cachedEntries!!.size
                
                val archiveFile = ensureSessionMirror()
                
                // [高级核心优化]：SELinux 兼容的持久会话随机访问 (O(1))
                if (archiveFile == null) {
                    val count = if (ensureActiveSession()) {
                        val size = activeSortedHeaders?.size ?: 0
                        if (size > 0) {
                            android.util.Log.i("ArchiveLoader", "NIO Session success, entries: $size")
                            cachedEntries = activeSortedHeaders?.map { 
                                if (it is String) it else (it as com.github.junrar.rarfile.FileHeader).fileName 
                            }
                            size
                        } else null
                    } else null
                    
                    if (count != null) return@withLock count
                    
                    val fSize = getUriSize(uri)
                    val HUGE_FILE_THRESHOLD = 650L * 1024 * 1024
                    if (fSize > HUGE_FILE_THRESHOLD) {
                        android.util.Log.e("ArchiveLoader", "NIO Session failed for HUGE file ($fSize bytes). Refusing sequential scan fallback to avoid UI lockup.")
                        return@withLock 0
                    }

                    android.util.Log.i("ArchiveLoader", "Scanning metadata via sequential stream (Slow Path)...")
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        val names = mutableListOf<String>()
                        when (extension.lowercase()) {
                            "cbz", "zip" -> {
                                ZipInputStream(input).use { zis ->
                                    var entry = zis.nextEntry
                                    while (entry != null) {
                                        if (!entry.isDirectory && isImage(entry.name)) names.add(entry.name)
                                        zis.closeEntry()
                                        entry = zis.nextEntry
                                    }
                                }
                            }
                            "cbr", "rar" -> {
                                try {
                                    Archive(input).use { archive ->
                                        archive.getFileHeaders().forEach { header ->
                                            if (!header.isDirectory && isImage(header.fileName ?: "")) names.add(header.fileName)
                                        }
                                    }
                                } catch (e: Exception) {
                                    if (e is com.github.junrar.exception.UnsupportedRarV5Exception || e.message?.contains("V5") == true) {
                                        android.util.Log.w("ArchiveLoader", "Junrar V5 mismatch in sequential scan, falling back to SevenZip (This may be slow for streams)...")
                                        // 顺位降级：对于 Stream 来说，SevenZip 很难处理，所以这里最好提示或记录异常
                                        // 实际上由于 ensureActiveSession 应该已经处理了文件/NIO 模式下的 RAR5，此路径主要针对冷启动
                                    }
                                }
                            }
                        }
                        cachedEntries = names.sorted()
                        return@withLock names.size
                    }
                    return@withLock 0
                }

                if (extension.lowercase() == "pdf") {
                    openPdfSync()
                    return@withLock pdfRenderer?.pageCount ?: 0
                }

                val names = mutableListOf<String>()
                when (extension.lowercase()) {
                    "cbz", "zip" -> {
                        java.util.zip.ZipFile(archiveFile).use { zipFile ->
                            zipFile.entries().asSequence().forEach { entry ->
                                if (!entry.isDirectory && isImage(entry.name)) names.add(entry.name)
                            }
                        }
                    }
                    "cbr", "rar" -> {
                        try {
                            Archive(archiveFile).use { archive ->
                                archive.getFileHeaders().forEach { header ->
                                    if (!header.isDirectory && isImage(header.fileName ?: "")) names.add(header.fileName)
                                }
                            }
                        } catch (e: Exception) {
                            if (e is com.github.junrar.exception.UnsupportedRarV5Exception || e.message?.contains("V5") == true) {
                                val inStream = SevenZipNioStream(java.io.RandomAccessFile(archiveFile, "r").channel)
                                SevenZip.openInArchive(null, inStream).use { szArchive ->
                                    for (i in 0 until szArchive.numberOfItems) {
                                        val path = szArchive.getProperty(i, PropID.PATH) as? String ?: continue
                                        val isFolder = szArchive.getProperty(i, PropID.IS_FOLDER) as? Boolean ?: false
                                        if (!isFolder && isImage(path)) names.add(path)
                                    }
                                }
                            }
                        }
                    }
                }
                cachedEntries = names.sorted()
                return@withLock names.size
            } catch (e: Exception) {
                e.printStackTrace()
                0
            }
        }
    }

   override suspend fun getPageData(pageIndex: Int, width: Int, height: Int): Any? = withContext(Dispatchers.IO) {
        // 1. 缓存优先 (支持预读图片或预渲染 PDF)
        extractedFiles[pageIndex]?.let { if (it.exists()) return@withContext decodeImageFile(it, width, height) }

        if (extension.lowercase() == "pdf") return@withContext loadPdfPage(pageIndex, width, height)

        // 快速检查：如果协程已取消（用户已划走），直接退出，不争夺锁
        ensureActive()

        android.util.Log.v("ArchiveLoader", "Awaiting archiveMutex for getPageData($pageIndex)...")
        val finalResult = archiveMutex.withLock {
            android.util.Log.v("ArchiveLoader", "Acquired archiveMutex for getPageData($pageIndex)")
            // 拿到锁后再次检查有效性
            if (!kotlin.coroutines.coroutineContext.isActive) return@withLock null
            
            // 2. [核心优先级]：NIO 持久会话随机寻址 (O(1))
            // 针对冷启动的大文件，这是最快的路径
            if (ensureActiveSession()) {
                val headers = activeSortedHeaders
                android.util.Log.d("ArchiveLoader", "Fast Path NIO: check index $pageIndex (Total: ${headers?.size})")
                if (headers != null && pageIndex in headers.indices) {
                    val entry = headers[pageIndex]
                    val result = try {
                        when (extension.lowercase()) {
                            "cbz", "zip" -> {
                                val entryName = entry as String
                                android.util.Log.d("ArchiveLoader", "NIO ZIP: fetching $entryName")
                                
                                // Attempt 1: System ZipFile (via /proc/self/fd)
                                var result = activeZipFile?.let { zip ->
                                    zip.getEntry(entryName)?.let { zipEntry ->
                                        zip.getInputStream(zipEntry).use { decodeImageStream(it, width, height) }
                                    }
                                }
                                
                                // Attempt 2: Manual ZipFastIndexer fallback
                                if (result == null) {
                                    result = activeZipIndexer?.let { indexer ->
                                        indexer.getEntryInputStream(entryName)?.use { decodeImageStream(it, width, height) }
                                    }
                                }
                                result
                            }
                            "cbr", "rar" -> {
                                if (activeRarArchive != null) {
                                    val header = entry as com.github.junrar.rarfile.FileHeader
                                    activeRarArchive?.getInputStream(header)?.use { stream ->
                                        decodeImageStream(stream, width, height)
                                    }
                                } else if (activeSevenZipArchive != null) {
                                    val meta = entry as SevenZipMeta
                                    val cacheFile = File(sessionDir, "sz_${meta.index}.tmp")
                                    if (!cacheFile.exists()) {
                                        extractSevenZipToFile(activeSevenZipArchive!!, meta.index, cacheFile)
                                    }
                                    if (cacheFile.exists()) {
                                        decodeImageFile(cacheFile, width, height)
                                    } else null
                                } else null
                            }
                            else -> null
                        }
                    } catch (e: Exception) {
                        if (e !is kotlinx.coroutines.CancellationException) {
                            android.util.Log.e("ArchiveLoader", "NIO Fetch failed: ${e.message}", e)
                        }
                        null
                    }
                    if (result != null) {
                        android.util.Log.i("ArchiveLoader", "Fast Path NIO SUCCESS for page $pageIndex: ${result.width}x${result.height} [${result.config}]")
                        return@withLock result
                    } else {
                        android.util.Log.w("ArchiveLoader", "Fast Path NIO returned NULL for page $pageIndex")
                    }
                }
            }

            android.util.Log.d("ArchiveLoader", "Trying Mirror Path for page $pageIndex")

            // 3. 本地镜像读取 (针对较小文件或已镜像的文件)
            ensureSessionMirror()?.let { mirror ->
                val result = try {
                    when (extension.lowercase()) {
                        "cbz", "zip" -> {
                            java.util.zip.ZipFile(mirror).use { zip ->
                                val entries = zip.entries().asSequence().filter { !it.isDirectory && isImage(it.name) }.sortedBy { it.name }.toList()
                                if (pageIndex in entries.indices) {
                                    zip.getInputStream(entries[pageIndex]).use { decodeImageStream(it, width, height) }
                                } else null
                            }
                        }
                        "cbr", "rar" -> {
                            Archive(mirror).use { archive ->
                                val headers = archive.getFileHeaders().filter { !it.isDirectory && isImage(it.fileName) }.sortedBy { it.fileName }
                                if (pageIndex in headers.indices) {
                                    archive.getInputStream(headers[pageIndex]).use { decodeImageStream(it, width, height) }
                                } else null
                            }
                        }
                        else -> null
                    }
                } catch (e: Exception) { null }
                if (result != null) return@withLock result
            }

            // 4. [最后兜底]：慢速顺序流扫描
            // 只有当上述随机读取方式都失败时，才进行代价极高的全量线性扫描
            try {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    when (extension.lowercase()) {
                        "cbz", "zip" -> {
                            val zis = ZipInputStream(input)
                            var entry = zis.nextEntry
                            var idx = 0
                            while (entry != null) {
                                if (!entry.isDirectory && isImage(entry.name)) {
                                    if (idx == pageIndex) return@withLock decodeImageStream(zis, width, height)
                                    idx++
                                }
                                zis.closeEntry()
                                entry = zis.nextEntry
                            }
                        }
                        "cbr", "rar" -> {
                            Archive(input).use { archive ->
                                val headers = archive.getFileHeaders().filter { !it.isDirectory && isImage(it.fileName) }.sortedBy { it.fileName }
                                if (pageIndex in headers.indices) {
                                    archive.getInputStream(headers[pageIndex]).use { return@withLock decodeImageStream(it, width, height) }
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) { e.printStackTrace() }
        }
        
        triggerSlidingWindow(pageIndex)
        return@withContext finalResult
    }

    private fun decodeImageStream(input: java.io.InputStream, reqWidth: Int, reqHeight: Int): Bitmap? {
        val MAX_MARK_SIZE = 30 * 1024 * 1024 // 30MB limit for huge pages
        val bis = if (input is java.io.BufferedInputStream) input else java.io.BufferedInputStream(input, 128 * 1024)
        
        try {
            bis.mark(MAX_MARK_SIZE)
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeStream(bis, null, options)
            
            if (options.outWidth <= 0 || options.outHeight <= 0) {
                android.util.Log.e("ArchiveLoader", "Failed to decode image bounds (possibly unsupported format). Header size: ${options.outMimeType}")
                return null
            }
            
            try {
                bis.reset()
            } catch (e: java.io.IOException) {
                android.util.Log.e("ArchiveLoader", "Mark reset failed (Image too large for buffer?): ${e.message}")
                return null
            }
            
            val sampleSize = calculateInSampleSize(options, reqWidth, reqHeight)
            options.inSampleSize = sampleSize
            options.inJustDecodeBounds = false
            options.inPreferredConfig = Bitmap.Config.RGB_565
            options.inMutable = true

            applyBitmapReuse(options, options.outWidth / sampleSize, options.outHeight / sampleSize)

            var bitmap = try {
                BitmapFactory.decodeStream(bis, null, options)
            } catch (e: Exception) {
                android.util.Log.w("ArchiveLoader", "inBitmap decode CRASHED, retrying fresh: ${e.message}")
                null // Will trigger the check below
            }
            
            if (bitmap == null) {
                // Final attempt without inBitmap if previous returned null or crashed
                if (options.inBitmap != null) {
                    try {
                        bis.reset()
                        options.inBitmap = null
                        bitmap = BitmapFactory.decodeStream(bis, null, options)
                    } catch (ex: Exception) {
                        android.util.Log.e("ArchiveLoader", "Final decode retry failed: ${ex.message}")
                    }
                }
            }
            
            if (bitmap == null) {
                android.util.Log.e("ArchiveLoader", "BitmapFactory returned null for stream even after retry")
            }
            
            return if (isSharpenEnabled && bitmap != null) {
                ImageEnhanceEngine.enhance(bitmap) { w, h, cfg -> obtainBitmap(w, h, cfg) }
            } else bitmap
        } catch (e: Exception) {
            android.util.Log.e("ArchiveLoader", "decodeImageStream encountered fatal error: ${e.message}", e)
            return null
        }
    }

    private fun applyBitmapReuse(options: BitmapFactory.Options, targetW: Int, targetH: Int) {
        val requiredBytes = targetW * targetH * (if (options.inPreferredConfig == Bitmap.Config.RGB_565) 2 else 4)
        synchronized(bitmapPool) {
            val it = bitmapPool.iterator()
            while (it.hasNext()) {
                val b = it.next()
                if (!b.isRecycled && b.isMutable && b.allocationByteCount >= requiredBytes) {
                    options.inBitmap = b
                    it.remove()
                    break
                }
            }
        }
    }

    private suspend fun extractWindow(targetIndex: Int) {
        val count = getPageCount()
        if (count == 0 || cachedEntries == null) return
        
        val start = (targetIndex - 5).coerceAtLeast(0)
        val end = (targetIndex + 5).coerceAtMost(count - 1)
        val neededIndices = (start..end).filter { extractedFiles[it]?.exists() != true }
        if (neededIndices.isEmpty()) return

        // 核心理念：后台预加载绝不能长时间霸占全局锁
        neededIndices.forEach { idx ->
            coroutineContext.ensureActive()
            kotlinx.coroutines.yield()

            try {
                archiveMutex.withLock {
                    if (!kotlin.coroutines.coroutineContext.isActive) return@withLock
                    
                    val mirrorFile = ensureSessionMirror()
                    if (mirrorFile != null) {
                        // 1. 通过物理镜像提取
                        when (extension.lowercase()) {
                            "cbz", "zip" -> {
                                java.util.zip.ZipFile(mirrorFile).use { zip ->
                                    val entriesList = zip.entries().asSequence().filter { !it.isDirectory && isImage(it.name) }.sortedBy { it.name }.toList()
                                    if (idx in entriesList.indices) {
                                        zip.getInputStream(entriesList[idx]).use { saveEntryToCache(it, idx) }
                                    }
                                }
                            }
                            "cbr", "rar" -> {
                                Archive(mirrorFile).use { archive ->
                                    val hList = archive.getFileHeaders().filter { !it.isDirectory && isImage(it.fileName) }.sortedBy { it.fileName }
                                    if (idx in hList.indices) {
                                        archive.getInputStream(hList[idx]).use { imgIn -> saveEntryToCache(imgIn, idx) }
                                    }
                                }
                            }
                        }
                    } else if (ensureActiveSession()) {
                        // 2. 超大文件利用 NIO Bridge 零开销预提取
                        val headers = activeSortedHeaders ?: return@withLock
                        if (idx in headers.indices) {
                            val entry = headers[idx]
                            when (extension.lowercase()) {
                                "cbz", "zip" -> {
                                    val name = entry as String
                                    val stream = activeZipFile?.let { zip ->
                                        zip.getEntry(name)?.let { zip.getInputStream(it) }
                                    } ?: activeZipIndexer?.getEntryInputStream(name)
                                    stream?.use { saveEntryToCache(it, idx) }
                                }
                                "cbr", "rar" -> {
                                    if (activeRarArchive != null && entry is com.github.junrar.rarfile.FileHeader) {
                                        activeRarArchive?.getInputStream(entry)?.use { saveEntryToCache(it, idx) }
                                    } else if (activeSevenZipArchive != null && entry is SevenZipMeta) {
                                        // RAR5 后台静默预解压：直写磁盘 tmp
                                        val cacheFile = File(sessionDir, "p_$idx.tmp")
                                        extractSevenZipToFile(activeSevenZipArchive!!, entry.index, cacheFile)
                                        extractedFiles[idx] = cacheFile
                                    }
                                }
                                "pdf" -> {
                                    // [PDF 核心优化]：利用后台空闲 IO 预渲染临近页面
                                    renderPdfToCache(idx)
                                }
                                else -> {}
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                if (e !is kotlinx.coroutines.CancellationException) {
                    android.util.Log.e("ArchiveLoader", "Background pre-fetch failed for page $idx: ${e.message}")
                }
            }
        }
        cleanupOldCache(targetIndex)
    }

    private fun saveEntryToCache(input: java.io.InputStream, index: Int) {
        val file = File(sessionDir, "p_$index.tmp")
        file.outputStream().use { out -> input.copyTo(out) }
        extractedFiles[index] = file
    }

    private var slidingWindowJob: kotlinx.coroutines.Job? = null
    private fun triggerSlidingWindow(centerIndex: Int) {
        slidingWindowJob?.cancel()
        slidingWindowJob = sessionScope.launch(Dispatchers.IO) {
            // 延时 300ms：如果用户在疯狂划动进度条，不启动任何后台 I/O
            kotlinx.coroutines.delay(300)
            extractWindow(centerIndex)
        }
    }

    private fun cleanupOldCache(currentIndex: Int) {
        val keepRange = (currentIndex - 15)..(currentIndex + 15)
        extractedFiles.keys().asSequence().forEach { index ->
            if (index !in keepRange) extractedFiles.remove(index)?.delete()
        }
    }

    private suspend fun loadPdfPage(pageIndex: Int, reqWidth: Int, reqHeight: Int): Bitmap? = archiveMutex.withLock {
        try {
            openPdfSync()
            val renderer = pdfRenderer ?: return@withLock null
            if (pageIndex !in 0 until renderer.pageCount) return@withLock null
            val page = renderer.openPage(pageIndex)
            val scale = (minOf(reqWidth.toFloat() / page.width, reqHeight.toFloat() / page.height)).coerceAtMost(2f)
            val w = (page.width * scale).toInt().coerceAtLeast(1)
            val h = (page.height * scale).toInt().coerceAtLeast(1)
            
            // [性能优化] 接入 Bitmap 内存池
            val bitmap = obtainBitmap(w, h, Bitmap.Config.RGB_565)
            bitmap.eraseColor(android.graphics.Color.WHITE)
            val matrix = Matrix().apply { postScale(scale, scale) }
            
            android.util.Log.v("ArchiveLoader", "PDF Sync Render: $pageIndex @ ${scale}x")
            page.render(bitmap, null, matrix, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            page.close()
            return@withLock if (isSharpenEnabled) {
                ImageEnhanceEngine.enhance(bitmap) { w, h, cfg -> obtainBitmap(w, h, cfg) }
            } else bitmap
        } catch (e: Exception) { 
            android.util.Log.e("ArchiveLoader", "PDF Load Error: ${e.message}")
            return@withLock null 
        }
    }

    private fun renderPdfToCache(pageIndex: Int) {
        val file = File(sessionDir, "p_$pageIndex.tmp")
        if (file.exists()) {
            extractedFiles[pageIndex] = file
            return
        }
        
        try {
            // 注意：此处由 extractWindow 的 archiveMutex 保护，外部已加锁
            openPdfSync()
            val renderer = pdfRenderer ?: return
            if (pageIndex !in 0 until renderer.pageCount) return
            
            val page = renderer.openPage(pageIndex)
            // 预渲染使用 1.5x 固定中等采样率
            val scale = 1.5f
            val w = (page.width * scale).toInt()
            val h = (page.height * scale).toInt()
            
            val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565)
            bitmap.eraseColor(android.graphics.Color.WHITE)
            val matrix = Matrix().apply { postScale(scale, scale) }
            page.render(bitmap, null, matrix, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            page.close()
            
            file.outputStream().use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
            }
            bitmap.recycle()
            extractedFiles[pageIndex] = file
            android.util.Log.d("ArchiveLoader", "PDF Pre-rendered page $pageIndex to cache.")
        } catch (e: Exception) {
            android.util.Log.e("ArchiveLoader", "PDF Pre-render failed: ${e.message}")
        }
    }

    // 已迁移至 xyz.sakulik.comic.model.processor.ImageEnhanceEngine

    private fun openPdfSync() {
        if (pdfRenderer != null) return
        try {
            pdfPfd?.close()
            val pfd = context.contentResolver.openFileDescriptor(uri, "r")
            pdfPfd = pfd
            pdfPfd?.let { pdfRenderer = PdfRenderer(it) }
        } catch (e: Exception) {
            android.util.Log.e("ArchiveLoader", "Failed to open PDF: ${e.message}")
            pdfPfd?.close()
            pdfPfd = null
            pdfRenderer = null
        }
    }

    private fun decodeImageFile(file: File, reqWidth: Int, reqHeight: Int): Bitmap? {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, options)
        val sampleSize = calculateInSampleSize(options, reqWidth, reqHeight)
        options.inSampleSize = sampleSize
        options.inJustDecodeBounds = false
        options.inPreferredConfig = Bitmap.Config.RGB_565 
        val targetW = options.outWidth / sampleSize
        val targetH = options.outHeight / sampleSize
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
        options.inMutable = true
        return try {
            BitmapFactory.decodeFile(file.absolutePath, options)
        } catch (e: Exception) {
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
        closeActiveSession()
        try {
            pdfRenderer?.close()
            pdfPfd?.close()
            if (sessionDir.exists()) sessionDir.deleteRecursively()
            if (uri.scheme != "file") sessionMirror?.delete()
            extractedFiles.clear()
            synchronized(bitmapPool) {
                bitmapPool.forEach { it.recycle() }
                bitmapPool.clear()
            }
        } catch (e: Exception) { e.printStackTrace() }
    }

    private fun getRarVersion(channel: FileChannel): Int {
        val originalPos = try { channel.position() } catch (e: Exception) { 0L }
        try {
            val buf = ByteBuffer.allocate(32)
            channel.read(buf, 0L)
            buf.flip()
            if (buf.remaining() < 7) return 4
            val sig = ByteArray(6)
            buf.get(sig)
            if (sig[0] == 0x52.toByte() && sig[1] == 0x61.toByte() && sig[2] == 0x72.toByte() &&
                sig[3] == 0x21.toByte() && sig[4] == 0x1A.toByte() && sig[5] == 0x07.toByte()) {
                val v = buf.get().toInt() and 0xFF
                return if (v == 1) 5 else 4
            }
            return 4
        } catch (e: Exception) { 
            return 4 
        } finally {
            try { channel.position(originalPos) } catch (e: Exception) {}
        }
    }

    private fun extractSevenZipToFile(archive: IInArchive, index: Int, targetFile: File) {
        if (targetFile.exists()) return
        try {
            val out = java.io.FileOutputStream(targetFile)
            out.use { fos ->
                archive.extract(intArrayOf(index), false, object : IArchiveExtractCallback {
                    override fun getStream(index: Int, askMode: ExtractAskMode?): ISequentialOutStream? {
                        if (askMode != ExtractAskMode.EXTRACT) return null
                        return ISequentialOutStream { data ->
                            fos.write(data)
                            data.size
                        }
                    }
                    override fun prepareOperation(askMode: ExtractAskMode?) {}
                    override fun setOperationResult(result: ExtractOperationResult?) {}
                    override fun setCompleted(completeValue: Long) {}
                    override fun setTotal(totalValue: Long) {}
                })
            }
        } catch (e: Exception) {
            if (targetFile.exists()) targetFile.delete()
            android.util.Log.e("ArchiveLoader", "SevenZip extraction failed for index $index: ${e.message}")
        }
    }

    private data class SevenZipMeta(val index: Int, val path: String)

    private inner class SevenZipNioStream(private val channel: FileChannel) : IInStream {
        override fun seek(offset: Long, origin: Int): Long {
            val target = when (origin) {
                IInStream.SEEK_SET -> offset
                IInStream.SEEK_CUR -> channel.position() + offset
                IInStream.SEEK_END -> channel.size() + offset
                else -> channel.position()
            }
            channel.position(target)
            return target
        }
        override fun read(data: ByteArray): Int {
            val buf = ByteBuffer.wrap(data)
            val read = channel.read(buf)
            return if (read < 0) 0 else read
        }
        override fun close() {}
    }

    private inner class RarNioChannel(private val channel: FileChannel) : com.github.junrar.io.SeekableReadOnlyByteChannel {
        private var position: Long = 0
        private val tinyBuf = ByteBuffer.allocate(8192) // 核心：8KB 预读缓冲区，避免单字节 I/O
        private var bufPos: Long = -1L

        override fun getPosition(): Long = position
        override fun setPosition(pos: Long) { position = pos }

        override fun read(): Int {
            if (bufPos == -1L || position < bufPos || position >= bufPos + tinyBuf.limit()) {
                tinyBuf.clear()
                val read = channel.read(tinyBuf, position)
                if (read <= 0) return -1
                tinyBuf.flip()
                bufPos = position
            }
            val b = tinyBuf.get((position - bufPos).toInt()).toInt() and 0xFF
            position++
            return b
        }

        override fun read(buffer: ByteArray?, off: Int, count: Int): Int {
            if (buffer == null) return 0
            val bb = ByteBuffer.wrap(buffer, off, count)
            val read = channel.read(bb, position)
            if (read > 0) {
                position += read
                bufPos = -1L // Invalidate tiny buffer on large reads
            }
            return read
        }
        override fun readFully(buffer: ByteArray, count: Int): Int {
            val bb = ByteBuffer.wrap(buffer, 0, count)
            var totalRead = 0
            while (totalRead < count) {
                val read = channel.read(bb, position + totalRead)
                if (read <= 0) break
                totalRead += read
            }
            position += totalRead
            return totalRead
        }
        override fun close() { /* Session handles channel closure */ }
    }

    private inner class RarVolume(
        private val nioChannel: RarNioChannel,
        private var archive: com.github.junrar.Archive?
    ) : com.github.junrar.volume.Volume {
        override fun getChannel(): com.github.junrar.io.SeekableReadOnlyByteChannel = nioChannel
        override fun getLength(): Long = activeChannel?.size() ?: 0
        override fun getArchive(): com.github.junrar.Archive = archive ?: activeRarArchive!!
    }

    private inner class RarVolumeManager(private val nioChannel: RarNioChannel) : com.github.junrar.volume.VolumeManager {
        override fun nextVolume(archive: com.github.junrar.Archive?, lastVolume: com.github.junrar.volume.Volume?): com.github.junrar.volume.Volume? = 
            if (lastVolume == null) RarVolume(nioChannel, archive) else null
    }

    private inner class ZipFastIndexer(private val channel: FileChannel) {
        private val entries = mutableMapOf<String, ZipEntryMeta>()
        init { parseCentralDirectory() }
        fun getEntryNames(): List<String> = entries.keys.toList()
        fun getEntryInputStream(name: String): java.io.InputStream? {
            val meta = entries[name] ?: return null
            try {
                // 1. 读取 Local File Header (30 bytes)
                val lfhBuf = ByteBuffer.allocate(30).order(ByteOrder.LITTLE_ENDIAN)
                channel.read(lfhBuf, meta.localHeaderOffset)
                lfhBuf.flip()
                val sig = lfhBuf.getInt()
                if (sig != 0x04034b50) {
                    android.util.Log.e("ArchiveLoader", "NIO ZIP LFH Sig Mismatch at ${meta.localHeaderOffset} for $name: expected 04034b50, got ${Integer.toHexString(sig)}")
                    return null
                }
                
                lfhBuf.position(8)
                val method = lfhBuf.getShort().toInt() and 0xFFFF
                
                lfhBuf.position(26)
                val nameLen = lfhBuf.getShort().toInt() and 0xFFFF
                val extraLen = lfhBuf.getShort().toInt() and 0xFFFF
                
                val dataOffset = meta.localHeaderOffset + 30 + nameLen + extraLen
                
                // 2. 创建 Sub-Channel InputStream
                val rawStream = object : java.io.InputStream() {
                    private var currentPos = dataOffset
                    private val endPos = dataOffset + meta.compressedSize
                    private val singleBuf = ByteBuffer.allocate(8192)
                    private var bufPos = -1L
                    
                    override fun read(): Int {
                        if (currentPos >= endPos) return -1
                        if (bufPos == -1L || currentPos < bufPos || currentPos >= bufPos + singleBuf.limit()) {
                            singleBuf.clear()
                            val remain = (endPos - currentPos).toInt()
                            if (remain <= 0) return -1
                            singleBuf.limit(remain.coerceAtMost(8192))
                            val read = channel.read(singleBuf, currentPos)
                            if (read <= 0) return -1
                            singleBuf.flip()
                            bufPos = currentPos
                        }
                        val b = singleBuf.get((currentPos - bufPos).toInt()).toInt() and 0xFF
                        currentPos++
                        return b
                    }
                    
                    override fun read(b: ByteArray, off: Int, len: Int): Int {
                        if (currentPos >= endPos) return -1
                        val remain = (endPos - currentPos).toLong()
                        val toRead = len.toLong().coerceAtMost(remain).toInt()
                        val bb = ByteBuffer.wrap(b, off, toRead)
                        val read = channel.read(bb, currentPos)
                        if (read > 0) {
                            currentPos += read
                            bufPos = -1L // Invalidate buffer
                        }
                        return read
                    }
                }
                
                return when (method) {
                    0 -> rawStream // Stored
                    8 -> {
                        val inflater = java.util.zip.Inflater(true)
                        object : java.util.zip.InflaterInputStream(rawStream, inflater) {
                            override fun close() {
                                super.close()
                                inflater.end() // 关键：释放原生内存以防 OOM
                            }
                        }
                    }
                    else -> null
                }
            } catch (e: Exception) {
                return null
            }
        }
        private fun parseCentralDirectory() {
            val size = channel.size()
            if (size < 22) return
            
            // 1. 寻找 Standard EOCD (扩展扫描范围以应对包含大量非压缩数据的档案)
            val scanLength = (256 * 1024 + 22).toLong().coerceAtMost(size)
            val scanStart = size - scanLength
            val scanBuf = ByteBuffer.allocate(scanLength.toInt()).order(ByteOrder.LITTLE_ENDIAN)
            channel.read(scanBuf, scanStart)
            scanBuf.flip()
            
            var eocdOffset = -1L
            for (i in (scanBuf.limit() - 22) downTo 0) {
                if (scanBuf.getInt(i) == 0x06054b50) {
                    eocdOffset = scanStart + i
                    break
                }
            }
            if (eocdOffset == -1L) return

            // 2. 检查 ZIP64
            var totalEntries: Long = -1
            var cdSize: Long = -1
            var cdOffset: Long = -1

            val locatorOffset = eocdOffset - 20
            if (locatorOffset >= 0) {
                val locBuf = ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN)
                channel.read(locBuf, locatorOffset)
                locBuf.flip()
                if (locBuf.getInt() == 0x07064b50) {
                    locBuf.getInt() // skip disk number
                    val zip64EocdOffset = locBuf.getLong()
                    
                    val recBuf = ByteBuffer.allocate(56).order(ByteOrder.LITTLE_ENDIAN)
                    channel.read(recBuf, zip64EocdOffset)
                    recBuf.flip()
                    if (recBuf.getInt() == 0x06064b50) {
                        recBuf.getLong() // record size
                        recBuf.getShort() // version made
                        recBuf.getShort() // version needed
                        recBuf.getInt() // disk
                        recBuf.getInt() // disk start
                        recBuf.getLong() // entries in disk
                        totalEntries = recBuf.getLong()
                        cdSize = recBuf.getLong()
                        cdOffset = recBuf.getLong()
                    }
                }
            }

            if (totalEntries == -1L) {
                val eocdBuf = ByteBuffer.allocate(22).order(ByteOrder.LITTLE_ENDIAN)
                channel.read(eocdBuf, eocdOffset)
                eocdBuf.flip()
                eocdBuf.position(10)
                totalEntries = eocdBuf.getShort().toLong() and 0xFFFF
                cdSize = eocdBuf.getInt().toLong() and 0xFFFFFFFFL
                cdOffset = eocdBuf.getInt().toLong() and 0xFFFFFFFFL
            }

            // 3. 解析 Central Directory
            channel.position(cdOffset)
            val cdBuf = ByteBuffer.allocate(cdSize.toInt()).order(ByteOrder.LITTLE_ENDIAN)
            channel.read(cdBuf)
            cdBuf.flip()

            repeat(totalEntries.toInt()) {
                var entryStart = cdBuf.position()
                if (cdBuf.remaining() < 46) return@repeat
                var sig = cdBuf.getInt()
                if (sig != 0x02014b50) {
                    // [修复：静默对齐 Bug] 发现签名不匹配时尝试向前探测（Hunter 模式）
                    android.util.Log.e("ArchiveLoader", "ZIP CD signature mismatch at $entryStart, hunting...")
                    var found = false
                    while (cdBuf.remaining() >= 46) {
                        if (cdBuf.getInt() == 0x02014b50) {
                            entryStart = cdBuf.position() - 4
                            found = true
                            break
                        }
                    }
                    if (!found) return@repeat
                }
                
                cdBuf.position(entryStart + 20)
                var compSize = cdBuf.getInt().toLong() and 0xFFFFFFFFL
                var uncompSize = cdBuf.getInt().toLong() and 0xFFFFFFFFL
                
                cdBuf.position(entryStart + 28)
                val nameLen = cdBuf.getShort().toInt() and 0xFFFF
                val extraLen = cdBuf.getShort().toInt() and 0xFFFF
                val commentLen = cdBuf.getShort().toInt() and 0xFFFF
                cdBuf.position(entryStart + 42)
                var localHeaderOffset = cdBuf.getInt().toLong() and 0xFFFFFFFFL
                
                val nameBytes = ByteArray(nameLen)
                cdBuf.get(nameBytes)
                val name = String(nameBytes, kotlin.text.Charsets.UTF_8)
                
                if (extraLen > 0) {
                    val extraStart = cdBuf.position()
                    var p = 0
                    while (p + 4 <= extraLen) {
                        cdBuf.position(extraStart + p)
                        val tag = cdBuf.getShort().toInt() and 0xFFFF
                        val dataSize = cdBuf.getShort().toInt() and 0xFFFF
                        
                        if (tag == 0x0001) {
                            // ZIP64 Extra: 根据 CD 记录中的 0xFFFFFFFF 标志来顺序读取字段
                            var posInExtra = 0
                            if (uncompSize == 0xFFFFFFFFL && posInExtra + 8 <= dataSize) {
                                uncompSize = cdBuf.getLong(extraStart + p + 4 + posInExtra)
                                posInExtra += 8
                            }
                            if (compSize == 0xFFFFFFFFL && posInExtra + 8 <= dataSize) {
                                compSize = cdBuf.getLong(extraStart + p + 4 + posInExtra)
                                posInExtra += 8
                            }
                            if (localHeaderOffset == 0xFFFFFFFFL && posInExtra + 8 <= dataSize) {
                                localHeaderOffset = cdBuf.getLong(extraStart + p + 4 + posInExtra)
                                posInExtra += 8
                            }
                            android.util.Log.v("ArchiveLoader", "NIO ZIP: Loaded ZIP64 metadata for $name")
                            break
                        }
                        
                        p += 4 + dataSize
                    }
                    cdBuf.position(extraStart + extraLen)
                }
                
                entries[name] = ZipEntryMeta(localHeaderOffset, compSize)
                // 严格跳转：基于头部记录的偏移量 + 所有变长字段长度
                cdBuf.position(entryStart + 46 + nameLen + extraLen + commentLen)
            }
        }
        private inner class ZipEntryMeta(val localHeaderOffset: Long, val compressedSize: Long)
    }
}
