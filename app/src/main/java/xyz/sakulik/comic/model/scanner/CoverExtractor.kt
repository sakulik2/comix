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
import android.util.Log
import java.io.*
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipFile
import net.sf.sevenzipjbinding.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

object CoverExtractor {

    private const val AUTO_SCAN_BYTES_LIMIT = 40 * 1024 * 1024L 
    private const val AUTO_SCAN_ENTRIES_LIMIT = 50 

    suspend fun extractCover(context: Context, uri: Uri, extension: String, outPath: File): Boolean = withContext(Dispatchers.IO) {
        val ext = extension.lowercase()
        try {
            when (ext) {
                "pdf" -> extractPdfCover(context, uri, outPath)
                "cbz", "zip" -> extractCbzCover(context, uri, outPath)
                "cbr", "rar" -> extractCbrCover(context, uri, outPath)
                else -> false
            }
        } catch (e: Exception) {
            Log.e("CoverExtractor", "Extract ($ext) FAIL: ${e.message}")
            false
        }
    }

    suspend fun extractPageToCache(context: Context, uri: Uri, extension: String, pageIndex: Int, outPath: File): Boolean = withContext(Dispatchers.IO) {
        try {
            when (extension.lowercase()) {
                "pdf" -> {
                    context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                        PdfRenderer(pfd).use { renderer ->
                            if (pageIndex in 0 until renderer.pageCount) {
                                val page = renderer.openPage(pageIndex)
                                val scale = 400f / page.width.coerceAtLeast(1)
                                val bitmap = Bitmap.createBitmap((page.width * scale).toInt().coerceAtLeast(1), (page.height * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                                page.render(bitmap, null, Matrix().apply { postScale(scale, scale) }, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                page.close()
                                if (saveBitmapToWebp(bitmap, outPath)) {
                                    bitmap.recycle(); return@withContext true
                                }
                            }
                        }
                    }
                }
                "cbz", "zip" -> {
                    context.contentResolver.openInputStream(uri)?.use { fis ->
                        ZipInputStream(fis).use { zis ->
                            val names = mutableListOf<String>(); var entry: ZipEntry? = zis.nextEntry
                            while (entry != null) {
                                if (!entry.isDirectory && isImage(entry.name)) names.add(entry.name)
                                entry = zis.nextEntry
                            }
                            names.sort()
                            if (pageIndex in names.indices) return@withContext reOpenAndScanZip(context, uri, names[pageIndex], outPath)
                        }
                    }
                }
                "cbr", "rar" -> {
                    context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                        val channel = java.io.FileInputStream(pfd.fileDescriptor).channel
                        val version = getRarVersion(channel)
                        return@withContext if (version == 5) extractRarV5PageNio(channel, pageIndex, outPath) else extractRarV4PageNio(channel, pageIndex, outPath)
                    }
                }
            }
        } catch (e: Exception) { Log.e("CoverExtractor", "Manual Extract Error", e) }
        false
    }

    private fun extractPdfCover(context: Context, uri: Uri, outPath: File): Boolean {
        context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
            PdfRenderer(pfd).use { renderer ->
                if (renderer.pageCount > 0) {
                    val page = renderer.openPage(0)
                    val scale = 400f / page.width.coerceAtLeast(1)
                    val bitmap = Bitmap.createBitmap((page.width * scale).toInt().coerceAtLeast(1), (page.height * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                    page.render(bitmap, null, Matrix().apply { postScale(scale, scale) }, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    page.close()
                    saveBitmapToWebp(bitmap, outPath); bitmap.recycle(); return true
                }
            }
        }
        return false
    }

    private fun extractCbzCover(context: Context, uri: Uri, outPath: File): Boolean {
        if (uri.scheme == "file") {
            try {
                ZipFile(File(uri.path!!)).use { zip ->
                    val entry = zip.entries().asSequence()
                        .filter { !it.isDirectory && isImage(it.name) }
                        .minByOrNull { it.name }
                    if (entry != null) {
                        zip.getInputStream(entry).use { return decodeAndSave(it, outPath) }
                    }
                }
            } catch (e: Exception) {}
        }
        try {
            context.contentResolver.openInputStream(uri)?.use { fis ->
                var byteCount = 0L; var entryCount = 0
                ZipInputStream(fis).use { zis ->
                    var entry = zis.nextEntry
                    while (entry != null && byteCount < AUTO_SCAN_BYTES_LIMIT && entryCount < AUTO_SCAN_ENTRIES_LIMIT) {
                        if (!entry.isDirectory && isImage(entry.name)) {
                            Log.i("CoverExtractor", "Auto-Scan ZIP Matched: ${entry.name}")
                            return decodeAndSave(zis, outPath)
                        }
                        byteCount += entry.compressedSize.coerceAtLeast(0); entryCount++; entry = zis.nextEntry
                    }
                }
            }
        } catch (e: Exception) { Log.e("CoverExtractor", "ZIP Stream Error", e) }
        return false
    }

    private fun extractCbrCover(context: Context, uri: Uri, outPath: File): Boolean {
        try {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                val channel = java.io.FileInputStream(pfd.fileDescriptor).channel
                val version = getRarVersion(channel)
                Log.i("CoverExtractor", "Detecting CBR: Version=$version Size=${channel.size()}")
                return if (version == 5) extractRarV5PageNio(channel, 0, outPath) else extractRarV4PageNio(channel, 0, outPath)
            }
        } catch (e: Exception) { Log.e("CoverExtractor", "CBR NIO Open Error", e) }
        return false
    }

    private fun extractRarV5PageNio(channel: FileChannel, index: Int, outPath: File): Boolean {
        try {
            val inStream = SevenZipNioStream(channel)
            SevenZip.openInArchive(null, inStream).use { szArchive ->
                val images = mutableListOf<Pair<Int, String>>()
                for (i in 0 until szArchive.numberOfItems) {
                    val path = szArchive.getProperty(i, PropID.PATH) as? String ?: continue
                    val isFolder = szArchive.getProperty(i, PropID.IS_FOLDER) as? Boolean ?: false
                    if (!isFolder && isImage(path)) images.add(i to path)
                }
                images.sortBy { it.second }
                if (index in images.indices) {
                    val targetId = images[index].first
                    val bos = ByteArrayOutputStream()
                    szArchive.extract(intArrayOf(targetId), false, object : IArchiveExtractCallback {
                        override fun getStream(idx: Int, askMode: ExtractAskMode?): ISequentialOutStream? {
                            return if (askMode == ExtractAskMode.EXTRACT) ISequentialOutStream { data -> bos.write(data); data.size } else null
                        }
                        override fun prepareOperation(p0: ExtractAskMode?) {}
                        override fun setOperationResult(p0: ExtractOperationResult?) {}
                        override fun setCompleted(p0: Long) {}
                        override fun setTotal(p0: Long) {}
                    })
                    return decodeAndSave(ByteArrayInputStream(bos.toByteArray()), outPath)
                }
            }
        } catch (e: Exception) { Log.e("CoverExtractor", "SevenZip V5 Error", e) }
        return false
    }

    private fun extractRarV4PageNio(channel: FileChannel, index: Int, outPath: File): Boolean {
        try {
            val volumeManager = RarVolumeManager(RarNioChannel(channel))
            Archive(volumeManager, null, null).use { arc ->
                val images = arc.fileHeaders.filter { !it.isDirectory && isImage(it.fileName) }.sortedBy { it.fileName }
                if (index in images.indices) {
                    arc.getInputStream(images[index]).use { return decodeAndSave(it, outPath) }
                }
            }
        } catch (e: Exception) { Log.e("CoverExtractor", "Junrar V4 Error", e) }
        return false
    }

    private fun getRarVersion(channel: FileChannel): Int {
        return try {
            val buf = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
            channel.read(buf, 0); buf.flip()
            if (buf.remaining() >= 7 && buf.get() == 0x52.toByte() && buf.get() == 0x61.toByte() && buf.get() == 0x72.toByte() &&
                buf.get() == 0x21.toByte() && buf.get() == 0x1A.toByte() && buf.get() == 0x07.toByte()) {
                if (buf.get() == 0x01.toByte()) 5 else 4
            } else 4
        } catch (e: Exception) { 4 }
    }

    private fun reOpenAndScanZip(context: Context, uri: Uri, target: String, outPath: File): Boolean {
        context.contentResolver.openInputStream(uri)?.use { fis ->
            ZipInputStream(fis).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    if (entry.name == target) return decodeAndSave(zis, outPath)
                    entry = zis.nextEntry
                }
            }
        }
        return false
    }

    private fun decodeAndSave(input: InputStream, outPath: File): Boolean {
        try {
            val bytes = input.readBytes()
            if (bytes.isEmpty()) return false
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
            opts.inSampleSize = calculateInSampleSize(opts, 400, 600)
            opts.inJustDecodeBounds = false
            opts.inPreferredConfig = Bitmap.Config.RGB_565
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: return false
            val success = saveBitmapToWebp(bitmap, outPath); bitmap.recycle(); return success
        } catch (e: Exception) { return false }
    }

    private fun saveBitmapToWebp(bitmap: Bitmap, outPath: File): Boolean {
        return try {
            FileOutputStream(outPath).use { b -> bitmap.compress(Bitmap.CompressFormat.WEBP, 75, b) }; true
        } catch (e: Exception) { false }
    }

    private fun isImage(n: String): Boolean {
        val l = n.lowercase(); return l.endsWith(".jpg") || l.endsWith(".jpeg") || l.endsWith(".png") || l.endsWith(".webp") || l.endsWith(".gif") || l.endsWith(".bmp")
    }

    private fun calculateInSampleSize(options: BitmapFactory.Options, rW: Int, rH: Int): Int {
        val (h, w) = options.outHeight to options.outWidth; var s = 1
        if (h > rH || w > rW) {
            val hh = h / 2; val hw = w / 2
            while (hh / s >= rH && hw / s >= rW) s *= 2
        }
        return s
    }

    private class SevenZipNioStream(private val channel: FileChannel) : IInStream {
        override fun seek(offset: Long, origin: Int): Long {
            val t = when (origin) {
                IInStream.SEEK_SET -> offset
                IInStream.SEEK_CUR -> channel.position() + offset
                IInStream.SEEK_END -> channel.size() + offset
                else -> channel.position()
            }
            channel.position(t); return t
        }
        override fun read(data: ByteArray): Int {
            val b = ByteBuffer.wrap(data); val r = channel.read(b); return if (r < 0) 0 else r
        }
        override fun close() {}
    }

    private class RarNioChannel(private val channel: FileChannel) : com.github.junrar.io.SeekableReadOnlyByteChannel {
        private var p: Long = 0; private val tb = ByteBuffer.allocate(8192); private var bp: Long = -1L
        val size: Long get() = try { channel.size() } catch (e: Exception) { 0L }
        override fun getPosition() = p; override fun setPosition(pos: Long) { p = pos }
        override fun read(): Int {
            if (bp == -1L || p < bp || p >= bp + tb.limit()) {
                tb.clear(); val r = channel.read(tb, p); if (r <= 0) return -1
                tb.flip(); bp = p
            }
            val b = tb.get((p - bp).toInt()).toInt() and 0xFF; p++; return b
        }
        override fun read(buffer: ByteArray, off: Int, count: Int): Int {
            val bb = ByteBuffer.wrap(buffer, off, count); val r = channel.read(bb, p); if (r > 0) { p += r; bp = -1L }; return r
        }
        override fun readFully(buffer: ByteArray, count: Int): Int {
            val bb = ByteBuffer.wrap(buffer, 0, count); var t = 0
            while (t < count) { val r = channel.read(bb, p + t); if (r <= 0) break; t += r }
            p += t; return t
        }
        override fun close() {}
    }

    private class RarVolume(private val nio: RarNioChannel, private var arc: com.github.junrar.Archive?) : com.github.junrar.volume.Volume {
        override fun getChannel() = nio; override fun getLength() = nio.size; override fun getArchive() = arc!!
    }

    private class RarVolumeManager(private val nio: RarNioChannel) : com.github.junrar.volume.VolumeManager {
        override fun nextVolume(arc: com.github.junrar.Archive?, last: com.github.junrar.volume.Volume?) = if (last == null) RarVolume(nio, arc) else null
    }
}
