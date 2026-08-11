package xyz.sakulik.comic.model.loader

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

object RemoteResourceLimits {
    const val MAX_TOTAL_PAGES = 10_000
    const val MAX_LIBRARY_ITEMS = 20_000
    const val MAX_PAGE_BYTES = 64L * 1024 * 1024
    const val MAX_COMIC_CACHE_BYTES = 256L * 1024 * 1024
    const val MAX_GLOBAL_CACHE_BYTES = 500L * 1024 * 1024
    const val PREFETCH_PAGE_COUNT = 3

    fun validateComicId(comicId: String): String {
        val hasUnsafeCharacter = comicId.any { character ->
            character == '/' || character == '\\' || Character.isISOControl(character)
        }
        if (comicId.isBlank() || comicId.length > 256 || hasUnsafeCharacter) {
            throw RemoteResourceLimitException("远程漫画 ID 格式无效")
        }
        return comicId
    }

    fun validatePageCount(totalPages: Int, allowZero: Boolean = false): Int {
        val minimum = if (allowZero) 0 else 1
        if (totalPages !in minimum..MAX_TOTAL_PAGES) {
            throw RemoteResourceLimitException(
                "远程漫画页数超出限制: $totalPages（最多 $MAX_TOTAL_PAGES 页）"
            )
        }
        return totalPages
    }

    fun resolveComicCacheDirectory(cacheRoot: File, comicId: String): File {
        val safeComicId = validateComicId(comicId)
        val remoteRoot = File(cacheRoot, "remote_l2").canonicalFile
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(safeComicId.toByteArray(StandardCharsets.UTF_8))
            .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xFF) }
        val comicDirectory = File(remoteRoot, "comic_$digest").canonicalFile
        if (comicDirectory.parentFile != remoteRoot) {
            throw RemoteResourceLimitException("远程漫画缓存路径无效")
        }
        return comicDirectory
    }

    fun copyPageWithLimit(input: InputStream, output: OutputStream): Long {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) return total
            total += read
            if (total > MAX_PAGE_BYTES) {
                throw RemoteResourceLimitException(
                    "远程页面超过 ${MAX_PAGE_BYTES / 1024 / 1024}MB 限制"
                )
            }
            output.write(buffer, 0, read)
        }
    }
}

class RemoteResourceLimitException(message: String) : IOException(message)
