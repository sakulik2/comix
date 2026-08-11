package xyz.sakulik.comic.model.loader

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
            throw ArchiveLimitExceededException("压缩包条目数超出限制: $count")
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
                throw ArchiveLimitExceededException("解压数据超出 ${maxBytes / 1024 / 1024}MB 限制")
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
                    if (total > maxBytes) {
                        throw ArchiveLimitExceededException("读取数据超出 ${maxBytes / 1024 / 1024}MB 限制")
                    }
                }
                return skipped
            }

            private fun account(read: Int) {
                total += read
                if (total > maxBytes) {
                    throw ArchiveLimitExceededException("读取数据超出 ${maxBytes / 1024 / 1024}MB 限制")
                }
            }
        }
    }
}

class ArchiveLimitExceededException(message: String) : IOException(message)

class ArchiveRandomAccessRequiredException(message: String) : IOException(message)
