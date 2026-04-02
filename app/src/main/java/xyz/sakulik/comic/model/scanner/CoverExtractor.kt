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
        pfd.use {
            PdfRenderer(pfd).use { renderer ->
                if (renderer.pageCount > 0) {
                    val page = renderer.openPage(0)
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
            }
        }
        return outPath.exists()
    }

    private fun extractCbzCover(context: Context, uri: Uri, outPath: File): Boolean {
        // [1] 找出字典序最靠前的图片文件名
        var bestName: String? = null
        context.contentResolver.openInputStream(uri)?.use { input ->
            val zis = ZipInputStream(input)
            var entry = zis.nextEntry
            while (entry != null) {
                if (!entry.isDirectory && isImage(entry.name)) {
                    if (bestName == null || entry.name.lowercase() < bestName!!.lowercase()) {
                        bestName = entry.name
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        } ?: return false

        if (bestName == null) return false

        // [2] 提取该图片数据
        context.contentResolver.openInputStream(uri)?.use { input ->
            val zis = ZipInputStream(input)
            var entry = zis.nextEntry
            while (entry != null) {
                if (entry.name == bestName) {
                    val bitmap = decodeStreamWithThreshold(context, zis)
                    if (bitmap != null) {
                        saveBitmapToWebp(bitmap, outPath)
                        bitmap.recycle()
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
        val sessionId = java.util.UUID.randomUUID().toString()
        val tempFile = File(context.cacheDir, "scan_temp_$sessionId.cbr")
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(tempFile).use { output ->
                    input.copyTo(output)
                }
            }
            
            Archive(tempFile).use { archive ->
                val firstImage = archive.fileHeaders
                    .filter { !it.isDirectory && isImage(it.fileName) }
                    .minByOrNull { it.fileName.lowercase() }
                
                if (firstImage != null) {
                    archive.getInputStream(firstImage).use { imgIn ->
                        val bitmap = decodeStreamWithThreshold(context, imgIn)
                        if (bitmap != null) {
                            saveBitmapToWebp(bitmap, outPath)
                            bitmap.recycle()
                        }
                    }
                }
            }
        } finally {
            tempFile.delete()
        }
        return outPath.exists()
    }

    private fun decodeStreamWithThreshold(context: Context, input: java.io.InputStream): Bitmap? {
        val MAX_MEMORY_IMG_SIZE = 15 * 1024 * 1024 
        val baos = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(16384)
        var totalRead = 0
        var exceeded = false
        
        while (true) {
            val read = input.read(buffer)
            if (read == -1) break
            totalRead += read
            if (totalRead > MAX_MEMORY_IMG_SIZE) {
                exceeded = true
                break
            }
            baos.write(buffer, 0, read)
        }

        if (!exceeded) {
            val bytes = baos.toByteArray()
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            options.inSampleSize = calculateInSampleSize(options, 400, 600)
            options.inJustDecodeBounds = false
            options.inPreferredConfig = Bitmap.Config.RGB_565
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        } else {
            val tempImg = File(context.cacheDir, "scan_fallback_${java.util.UUID.randomUUID()}.tmp")
            try {
                FileOutputStream(tempImg).use { out ->
                    baos.writeTo(out)
                    input.copyTo(out)
                }
                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(tempImg.absolutePath, options)
                options.inSampleSize = calculateInSampleSize(options, 400, 600)
                options.inJustDecodeBounds = false
                options.inPreferredConfig = Bitmap.Config.RGB_565
                return BitmapFactory.decodeFile(tempImg.absolutePath, options)
            } finally {
                tempImg.delete()
            }
        }
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
}
