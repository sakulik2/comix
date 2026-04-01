package xyz.sakulik.comic.model

import com.github.junrar.Archive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * 使用 junrar 处理 CBR 文件
 */
class CbrReader(
    private val file: File,
    private val cacheDir: File
) : ComicBook {

    private val archive = Archive(file)
    private val fileHeaders = archive.fileHeaders
        .filter { !it.isDirectory && isImage(it.fileName) }
        .sortedBy { it.fileName }

    init {
        if (!cacheDir.exists()) {
            cacheDir.mkdirs()
        }
    }

    override val pageCount: Int
        get() = fileHeaders.size

    override suspend fun getPage(index: Int): PageResult = withContext(Dispatchers.IO) {
        if (index < 0 || index >= fileHeaders.size) {
            throw IndexOutOfBoundsException("Page index $index out of range [0, ${fileHeaders.size})")
        }
        val header = fileHeaders[index]
        val outFile = File(cacheDir, "cbr_page_$index.img")

        // 提取加锁：junrar 处理同一归档可能并发冲突
        synchronized(archive) {
            if (!outFile.exists()) {
                FileOutputStream(outFile).use { output ->
                    archive.extractFile(header, output)
                }
            }
        }
        PageResult.ImageFile(outFile)
    }

    override fun close() {
        archive.close()
    }

    private fun isImage(name: String): Boolean {
        val lower = name.lowercase()
        return lower.endsWith(".jpg") || lower.endsWith(".jpeg") ||
               lower.endsWith(".png") || lower.endsWith(".webp")
    }
}
