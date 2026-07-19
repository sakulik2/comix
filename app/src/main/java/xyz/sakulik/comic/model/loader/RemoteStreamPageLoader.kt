package xyz.sakulik.comic.model.loader

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import xyz.sakulik.comic.model.processor.ImageEnhanceEngine
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap

/**
 * 云端流媒体实现 v2.0：具备 L2 磁盘缓存与图像增强
 */
class RemoteStreamPageLoader(
    private val context: Context,
    private val comicId: String,
    private val totalPages: Int,
    private val baseUrl: String
) : ComicPageLoader {

    private val client = xyz.sakulik.comic.model.network.RetrofitClient.getClient(context)
    private val cacheDir = File(context.cacheDir, "remote_l2/$comicId").apply { if (!exists()) mkdirs() }
    private val dimensionsCache = ConcurrentHashMap<Int, Pair<Int, Int>>()
    private val sessionScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var isSharpenEnabled = false

    init {
        // 每次打开阅读该漫画时，触摸并更新缓存目录的“最后修改时间”，用于 LRU 自动过期清理
        try {
            if (cacheDir.exists()) {
                cacheDir.setLastModified(System.currentTimeMillis())
            }
        } catch (e: Exception) {
            android.util.Log.e("RemoteLoader", "触摸缓存目录失败", e)
        }
    }

    fun setSharpenEnabled(enabled: Boolean) { isSharpenEnabled = enabled }

    override suspend fun getPageCount(): Int = totalPages

    override suspend fun getPageSize(pageIndex: Int): Pair<Int, Int>? = withContext(Dispatchers.IO) {
        dimensionsCache[pageIndex]?.let { return@withContext it }
        
        val cacheFile = File(cacheDir, "p$pageIndex.webp")
        if (cacheFile.exists()) {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(cacheFile.absolutePath, opts)
            if (opts.outWidth > 0) {
                val res = opts.outWidth to opts.outHeight
                dimensionsCache[pageIndex] = res
                return@withContext res
            }
        }
        null
    }

    override suspend fun getPageData(pageIndex: Int, width: Int, height: Int): Any? = withContext(Dispatchers.IO) {
        val cacheFile = File(cacheDir, "p$pageIndex.webp")
        
        // 核心优化：触发后台预加载窗口 (前 5 后 2)
        triggerPrefetch(pageIndex)

        // 如果本地磁盘 L2 缓存不存在，则执行同步下载
        if (!cacheFile.exists()) {
            downloadPageSync(pageIndex, cacheFile)
        }

        // 解析尺寸并应用增强
        if (cacheFile.exists()) {
            val bitmap = decodeFileSafely(cacheFile.absolutePath, width, height)
            if (bitmap != null) {
                val res = bitmap.width to bitmap.height
                dimensionsCache[pageIndex] = res
                return@withContext processEnhancement(bitmap)
            }
        }
        null
    }

    private fun downloadPageSync(pageIndex: Int, targetFile: File) {
        val normalizedBaseUrl = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
        val pageNumber = pageIndex + 1
        val url = "${normalizedBaseUrl}api/comics/$comicId/page/$pageNumber"
        
        val tmpFile = File(targetFile.absolutePath + ".tmp")
        try {
            val request = Request.Builder().url(url).build()
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val responseBody = response.body ?: return
                    tmpFile.outputStream().use { out ->
                        responseBody.byteStream().copyTo(out)
                    }
                    if (!tmpFile.renameTo(targetFile)) {
                        tmpFile.delete()
                    }
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("RemoteLoader", "Download failed for index $pageIndex: ${e.message}")
            if (tmpFile.exists()) tmpFile.delete()
            if (targetFile.exists()) targetFile.delete()
        }
    }

    private var prefetchJob: kotlinx.coroutines.Job? = null
    private fun triggerPrefetch(currentIndex: Int) {
        prefetchJob?.cancel()
        prefetchJob = sessionScope.launch {
            // 微延迟防止在进度条滑动时产生短时间的网络请求冲击
            kotlinx.coroutines.delay(400)
            val start = (currentIndex + 1).coerceAtMost(totalPages - 1)
            val end = (currentIndex + 10).coerceAtMost(totalPages - 1)
            
            for (idx in start..end) {
                ensureActive()
                val file = File(cacheDir, "p$idx.webp")
                if (!file.exists()) {
                    android.util.Log.v("RemoteLoader", "Prefetching cloud page $idx")
                    downloadPageSync(idx, file)
                }
            }
        }
    }

    private fun processEnhancement(bitmap: Bitmap): Bitmap {
        return if (isSharpenEnabled) {
            ImageEnhanceEngine.enhance(bitmap) { w, h, cfg -> Bitmap.createBitmap(w, h, cfg) }
        } else bitmap
    }

    override fun releasePageData(data: Any?) {
        if (data is Bitmap && !data.isRecycled) {
            data.recycle()
        }
    }

    fun evictPageCache(pageIndex: Int) {
        try {
            val file = File(cacheDir, "p$pageIndex.webp")
            if (file.exists()) {
                file.delete()
            }
            dimensionsCache.remove(pageIndex)
        } catch (e: Exception) {
            android.util.Log.e("RemoteLoader", "Evict cache failed: ${e.message}")
        }
    }

    private fun decodeFileSafely(path: String, reqWidth: Int, reqHeight: Int): Bitmap? {
        try {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, opts)
            
            val sampleSize = calculateInSampleSize(opts, reqWidth, reqHeight)
            val decodeOpts = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            return BitmapFactory.decodeFile(path, decodeOpts)
        } catch (e: Throwable) {
            android.util.Log.e("RemoteLoader", "Safely decoding bitmap failed for $path", e)
            return null
        }
    }

    private fun calculateInSampleSize(options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
        val height = options.outHeight
        val width = options.outWidth
        if (height <= 0 || width <= 0) return 1
        var inSampleSize = 1

        // 1. 根据请求的宽度和高度做缩放计算
        if (height > reqHeight || width > reqWidth) {
            val halfHeight: Int = height / 2
            val halfWidth: Int = width / 2
            while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
                inSampleSize *= 2
            }
        }

        // 2. [核心防御] 强行保障解压后的位图大小不超过 80MB (20,971,520 像素)，防止 Canvas 绘图抛出 Too Large 崩溃
        val maxSizeBytes = 80 * 1024 * 1024L
        while ((width.toLong() * height.toLong() * 4L) / (inSampleSize * inSampleSize) > maxSizeBytes) {
            inSampleSize *= 2
        }

        return inSampleSize
    }

    override fun close() {
        sessionScope.cancel()
    }
}
