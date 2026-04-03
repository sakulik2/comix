package xyz.sakulik.comic.model

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipFile

/**
 * 使用 java.util.zip 处理 CBZ 文件
 */
class CbzReader(
    private val file: File,
    private val cacheDir: File
) : ComicBook {

    private val zipFile = ZipFile(file)
    private val entries = zipFile.entries().asSequence()
        .filter { !it.isDirectory && isImage(it.name) }
        .sortedBy { it.name }
        .toList()

    init {
        if (!cacheDir.exists()) {
            cacheDir.mkdirs()
        }
    }

    override val pageCount: Int
        get() = entries.size

    override suspend fun getPage(index: Int): PageResult = withContext(Dispatchers.IO) {
        val entry = entries[index]
        val outFile = File(cacheDir, "cbz_page_$index.img")

        // 简易缓存策略，如果文件不存在则提取
        // （为防止磁盘占用过大，应当由外部 ViewModel 负责清理过去的提取项）
        if (!outFile.exists()) {
            zipFile.getInputStream(entry).use { input ->
                FileOutputStream(outFile).use { output ->
                    input.copyTo(output)
                }
            }
        }
        PageResult.ImageFile(outFile)
    }

    override fun close() {
        zipFile.close()
    }

    private fun isImage(name: String): Boolean {
        val lower = name.lowercase()
        return lower.endsWith(".jpg") || lower.endsWith(".jpeg") ||
               lower.endsWith(".png") || lower.endsWith(".webp")
    }
}
