package xyz.sakulik.comic.model.loader

import xyz.sakulik.comic.R
import xyz.sakulik.comic.utils.LocalizedThrowable
import xyz.sakulik.comic.utils.UiText
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
            throw RemoteResourceLimitException(
                UiText.Res(R.string.error_remote_id_invalid),
                "invalid remote comic id"
            )
        }
        return comicId
    }

    fun validatePageCount(totalPages: Int, allowZero: Boolean = false): Int {
        val minimum = if (allowZero) 0 else 1
        if (totalPages !in minimum..MAX_TOTAL_PAGES) {
            throw RemoteResourceLimitException(
                UiText.Res(R.string.error_remote_total_pages, listOf(totalPages, MAX_TOTAL_PAGES)),
                "remote page count out of range: $totalPages"
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
            throw RemoteResourceLimitException(
                UiText.Res(R.string.error_remote_cache_path_invalid),
                "remote cache path escaped its root"
            )
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
                    UiText.Res(
                        R.string.error_remote_page_limit,
                        listOf((MAX_PAGE_BYTES / 1024 / 1024).toInt())
                    ),
                    "remote page exceeds $MAX_PAGE_BYTES bytes"
                )
            }
            output.write(buffer, 0, read)
        }
    }
}

/**
 * 只携带 UiText，由 UI 层解析成用户语言；
 * 传给 IOException 的 message 是给 logcat 看的英文技术串。
 */
class RemoteResourceLimitException(
    override val uiText: UiText,
    technicalMessage: String
) : IOException(technicalMessage), LocalizedThrowable
