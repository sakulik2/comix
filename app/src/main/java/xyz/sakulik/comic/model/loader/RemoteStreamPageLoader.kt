package xyz.sakulik.comic.model.loader

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import xyz.sakulik.comic.model.processor.ImageEnhanceEngine
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream

/**
 * 云端流媒体实现 v2.0：具备 L2 磁盘缓存与图像增强
 */
class RemoteStreamPageLoader(
    private val context: Context,
    private val comicId: String,
    private val totalPages: Int,
    private val baseUrl: String
) : ComicPageLoader {

    private val client = OkHttpClient()
    private val cacheDir = File(context.cacheDir, "remote_l2/$comicId").apply { mkdirs() }
    private var isSharpenEnabled = false

    fun setSharpenEnabled(enabled: Boolean) { isSharpenEnabled = enabled }

    override suspend fun getPageCount(): Int = totalPages

    override suspend fun getPageSize(pageIndex: Int): Pair<Int, Int>? = null

    override suspend fun getPageData(pageIndex: Int, width: Int, height: Int): Any? {
        val cacheFile = File(cacheDir, "p$pageIndex.webp")
        
        //\ 1 尝试从 L2 物理缓存读取
        if (!cacheFile.exists()) {
            val normalizedBaseUrl = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
            val url = "${normalizedBaseUrl}api/comics/$comicId/page/$pageIndex"
            
            try {
                val request = Request.Builder().url(url).build()
                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val responseBody = response.body ?: return null
                        //\ 2 存入 L2 缓存 (保存原始数据，避免重编码带来的损耗)
                        FileOutputStream(cacheFile).use { out ->
                            responseBody.byteStream().copyTo(out)
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                return null
            }
        }

        //\ 3 读取并应用滤镜
        return decodeAndApplyFilters(cacheFile)
    }

    private fun decodeAndApplyFilters(file: File): Bitmap? {
        val bitmap = BitmapFactory.decodeFile(file.absolutePath) ?: return null
        return if (isSharpenEnabled) {
            ImageEnhanceEngine.enhance(bitmap) { w, h, cfg -> Bitmap.createBitmap(w, h, cfg) }
        } else bitmap
    }

    // 已迁移至 xyz.sakulik.comic.model.processor.ImageEnhanceEngine

    override fun releasePageData(data: Any?) {
        // 云端模式下目前主要依赖 Coil 自身缓存生命周期进行管理，
        // 如后续引入本地 BitmapPool，可在此处扩展
    }

    override fun close() {
        // 清理缓存交由系统或统一策略
    }
}
