package xyz.sakulik.comic.model.scanner

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import com.github.junrar.Archive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipInputStream

object CoverExtractor {

    suspend fun extractCover(context: Context, uri: Uri, extension: String, outPath: File): Boolean = withContext(Dispatchers.IO) {
        try {
            when (extension) {
                "pdf" -> extractPdfCover(context, uri, outPath)
                "cbz", "zip" -> extractCbzCover(context, uri, outPath)
                "cbr", "rar" -> extractCbrCover(context, uri, outPath)
                else -> false
            }
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    private fun extractPdfCover(context: Context, uri: Uri, outPath: File): Boolean {
        val pfd = context.contentResolver.openFileDescriptor(uri, "r") ?: return false
        val renderer = PdfRenderer(pfd)
        if (renderer.pageCount > 0) {
            val page = renderer.openPage(0)
            // 生成缩略图尺寸
            val scale = 400f / page.width.coerceAtLeast(1)
            val w = (page.width * scale).toInt().coerceAtLeast(1)
            val h = (page.height * scale).toInt().coerceAtLeast(1)
            
            val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(android.graphics.Color.WHITE)
            
            val matrix = Matrix().apply { postScale(scale, scale) }
            page.render(bitmap, null, matrix, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            page.close()
            
            saveBitmapToWebp(bitmap, outPath)
            bitmap.recycle()
        }
        renderer.close()
        pfd.close()
        return outPath.exists()
    }

    private fun extractCbzCover(context: Context, uri: Uri, outPath: File): Boolean {
        // 使用流式读取，避免将完整的 CBZ 文件拷贝到本地
        context.contentResolver.openInputStream(uri)?.use { input ->
            val zis = ZipInputStream(input)
            var entry = zis.nextEntry
            // 记录字典树以便找到首字母最小的图片（但不现实，流式只能读当前，如果要找真正的第一页应该收集所有 entry 再做决断。为了极速扫描，我们直接提取流中出现的第一个合规图片）
            while (entry != null) {
                if (!entry.isDirectory && isImage(entry.name)) {
                    val bitmap = BitmapFactory.decodeStream(zis)
                    if (bitmap != null) {
                        saveBitmapToWebp(bitmap, outPath)
                        bitmap.recycle()
                        zis.closeEntry()
                        return true
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
        return false
    }

    private fun extractCbrCover(context: Context, uri: Uri, outPath: File): Boolean {
        // 由于 junrar 对流的支持有限，后台扫描时建立临时文件
        val tempFile = File(context.cacheDir, "scan_temp.cbr")
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(tempFile).use { output ->
                    input.copyTo(output)
                }
            }
            
            val archive = Archive(tempFile)
            val firstImage = archive.fileHeaders
                .filter { !it.isDirectory && isImage(it.fileName) }
                .minByOrNull { it.fileName }
            
            if (firstImage != null) {
                val tempImg = File(context.cacheDir, "scan_temp_img")
                FileOutputStream(tempImg).use { out ->
                    archive.extractFile(firstImage, out)
                }
                val bitmap = BitmapFactory.decodeFile(tempImg.absolutePath)
                if (bitmap != null) {
                    saveBitmapToWebp(bitmap, outPath)
                    bitmap.recycle()
                }
                tempImg.delete()
            }
            archive.close()
        } finally {
            tempFile.delete()
        }
        return outPath.exists()
    }

    private fun saveBitmapToWebp(bitmap: Bitmap, outPath: File) {
        FileOutputStream(outPath).use { out ->
            // Android 11+ 可以用 WEBP_LOSSY, 这里处于兼容用 WEBP
            @Suppress("DEPRECATION")
            bitmap.compress(Bitmap.CompressFormat.WEBP, 75, out)
        }
    }

    private fun isImage(name: String): Boolean {
        val lower = name.lowercase()
        return lower.endsWith(".jpg") || lower.endsWith(".jpeg") ||
               lower.endsWith(".png") || lower.endsWith(".webp")
    }
}
