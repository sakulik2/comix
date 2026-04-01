package xyz.sakulik.comic.model.loader

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
import java.util.zip.ZipInputStream

/**
 * 本地档案实现：支持 .cbr, .cbz, .pdf。
 * 使用流式解压与下采样技术，确保最低内存占用。
 */
class LocalArchivePageLoader(
    private val context: Context,
    private val uri: Uri,
    private val extension: String
) : ComicPageLoader {

    private val archiveMutex = Mutex()
    private var currentCbrFile: File? = null
    private var activeArchive: Archive? = null
    private var cachedCbrHeaders: List<com.github.junrar.rarfile.FileHeader>? = null

    override suspend fun getPageCount(): Int = withContext(Dispatchers.IO) {
        try {
            when (extension.lowercase()) {
                "pdf" -> {
                    context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                        PdfRenderer(pfd).use { it.pageCount }
                    } ?: 0
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
                    ensureCbrExtracted()
                    cachedCbrHeaders?.size ?: 0
                }
                else -> 0
            }
        } catch (e: Exception) {
            e.printStackTrace()
            0
        }
    }

    override suspend fun getPageData(pageIndex: Int, width: Int, height: Int): Any? = withContext(Dispatchers.IO) {
        archiveMutex.withLock {
            try {
                when (extension.lowercase()) {
                    "pdf" -> loadPdfPage(pageIndex, width, height)
                    "cbz", "zip" -> loadCbzPage(pageIndex, width, height)
                    "cbr", "rar" -> loadCbrPage(pageIndex, width, height)
                    else -> null
                }
            } catch (e: Exception) {
                e.printStackTrace()
                null
            }
        }
    }

    private fun loadPdfPage(pageIndex: Int, reqWidth: Int, reqHeight: Int): Bitmap? {
        val pfd = context.contentResolver.openFileDescriptor(uri, "r") ?: return null
        val renderer = PdfRenderer(pfd)
        if (pageIndex < 0 || pageIndex >= renderer.pageCount) {
            renderer.close()
            pfd.close()
            return null
        }
        val page = renderer.openPage(pageIndex)
        val scaleW = reqWidth.toFloat() / page.width.toFloat()
        val scaleH = reqHeight.toFloat() / page.height.toFloat()
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

    private fun loadCbzPage(targetIndex: Int, reqWidth: Int, reqHeight: Int): Bitmap? {
        context.contentResolver.openInputStream(uri)?.use { input ->
            val zis = ZipInputStream(input)
            var currentImageIndex = 0
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

    private fun loadCbrPage(targetIndex: Int, reqWidth: Int, reqHeight: Int): Bitmap? {
        try {
            ensureCbrExtracted()
            val archive = activeArchive ?: return null
            val imageHeaders = cachedCbrHeaders ?: return null

            if (targetIndex in imageHeaders.indices) {
                val targetHeader = imageHeaders[targetIndex]
                archive.getInputStream(targetHeader).use { imgInput ->
                    return decodeSampledBitmapFromStream(imgInput, reqWidth, reqHeight)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return null
    }

    private fun ensureCbrExtracted() {
        if (activeArchive != null) return
        
        val tempFile = File(context.cacheDir, "reader_${uri.hashCode()}.cbr")
        if (!tempFile.exists()) {
            context.contentResolver.openInputStream(uri)?.use { input ->
                tempFile.outputStream().use { output -> input.copyTo(output) }
            }
        }
        currentCbrFile = tempFile
        activeArchive = Archive(tempFile)
        cachedCbrHeaders = activeArchive!!.fileHeaders
            .filter { !it.isDirectory && isImage(it.fileName) }
            .sortedBy { it.fileName }
    }

    private fun decodeSampledBitmapFromStream(input: java.io.InputStream, reqWidth: Int, reqHeight: Int): Bitmap? {
        val bytes = input.readBytes()
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        
        options.inSampleSize = calculateInSampleSize(options, reqWidth, reqHeight)
        options.inJustDecodeBounds = false
        options.inPreferredConfig = Bitmap.Config.RGB_565 
        
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
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
        try {
            activeArchive?.close()
            activeArchive = null
            currentCbrFile?.let { if (it.exists()) it.delete() }
            currentCbrFile = null
            cachedCbrHeaders = null
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
