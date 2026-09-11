package xyz.sakulik.comic.model.loader

import xyz.sakulik.comic.R
import xyz.sakulik.comic.utils.LocalizedThrowable
import xyz.sakulik.comic.utils.UiText
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

object ArchiveResourceLimits {
    const val MAX_ARCHIVE_ENTRIES = 10_000
    const val MAX_CENTRAL_DIRECTORY_BYTES = 64L * 1024 * 1024
    const val MAX_MIRROR_BYTES = 650L * 1024 * 1024
    const val MAX_PAGE_BYTES = 96L * 1024 * 1024
    const val MAX_COVER_SOURCE_BYTES = 64L * 1024 * 1024
    const val MAX_METADATA_BYTES = 2L * 1024 * 1024
    const val MAX_IMAGE_DIMENSION = 200_000
    const val MAX_SOURCE_IMAGE_PIXELS = 1_000_000_000L
    const val MAX_DECODED_COVER_PIXELS = 16_000_000L

    fun requireEntryCount(count: Long) {
        if (count < 0 || count > MAX_ARCHIVE_ENTRIES) {
            throw ArchiveLimitExceededException(
                UiText.Res(R.string.error_archive_too_many_entries, listOf(count.toInt())),
                "archive entry count out of range: $count"
            )
        }
    }

    fun copyWithLimit(input: InputStream, output: OutputStream, maxBytes: Long): Long {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) return total
            total += read
            if (total > maxBytes) {
                throw ArchiveLimitExceededException(
                    UiText.Res(R.string.error_archive_extract_limit, listOf((maxBytes / 1024 / 1024).toInt())),
                    "extracted data exceeds $maxBytes bytes"
                )
            }
            output.write(buffer, 0, read)
        }
    }

    fun limitedInputStream(input: InputStream, maxBytes: Long): InputStream {
        return object : FilterInputStream(input) {
            private var total = 0L

            override fun read(): Int {
                val value = super.read()
                if (value >= 0) account(1)
                return value
            }

            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                val read = super.read(buffer, offset, length)
                if (read > 0) account(read)
                return read
            }

            override fun skip(byteCount: Long): Long {
                val skipped = super.skip(byteCount)
                if (skipped > 0) {
                    total += skipped
                    if (total > maxBytes) throw readLimitExceeded(maxBytes)
                }
                return skipped
            }

            private fun account(read: Int) {
                total += read
                if (total > maxBytes) throw readLimitExceeded(maxBytes)
            }
        }
    }

    private fun readLimitExceeded(maxBytes: Long) = ArchiveLimitExceededException(
        UiText.Res(R.string.error_archive_read_limit, listOf((maxBytes / 1024 / 1024).toInt())),
        "read data exceeds $maxBytes bytes"
    )
}

/**
 * 只携带 UiText，由 UI 层解析成用户语言；
 * 传给 IOException 的 message 是给 logcat 看的英文技术串。
 */
class ArchiveLimitExceededException(
    override val uiText: UiText,
    technicalMessage: String
) : IOException(technicalMessage), LocalizedThrowable

class ArchiveRandomAccessRequiredException(
    override val uiText: UiText,
    technicalMessage: String
) : IOException(technicalMessage), LocalizedThrowable
