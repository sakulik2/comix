package xyz.sakulik.comic.model.loader

import android.content.Context
import xyz.sakulik.comic.R
import xyz.sakulik.comic.utils.UiText
import java.io.File

object RemoteCacheManager {
    @Synchronized
    fun trim(
        context: Context,
        protectedDirectory: File? = null,
        protectedFiles: Set<File> = emptySet()
    ) {
        val root = File(context.cacheDir, "remote_l2").canonicalFile
        if (!root.exists() || !root.isDirectory) return

        val protectedDirectoryPath = protectedDirectory?.canonicalFile
        val protectedPaths = protectedFiles.mapTo(mutableSetOf()) { it.canonicalFile }
        val comicDirectories = root.listFiles()?.filter { it.isDirectory } ?: return

        comicDirectories.forEach { directory ->
            trimDirectory(
                directory = directory,
                maxBytes = RemoteResourceLimits.MAX_COMIC_CACHE_BYTES,
                protectedFiles = if (directory.canonicalFile == protectedDirectoryPath) protectedPaths else emptySet()
            )
            if (directorySize(directory) > RemoteResourceLimits.MAX_COMIC_CACHE_BYTES) {
                throw RemoteResourceLimitException(
                    UiText.Res(R.string.error_remote_cache_quota_single),
                    "cannot shrink single comic cache into quota"
                )
            }
        }

        var totalSize = comicDirectories.sumOf { directorySize(it) }
        if (totalSize <= RemoteResourceLimits.MAX_GLOBAL_CACHE_BYTES) return

        comicDirectories
            .filter { it.canonicalFile != protectedDirectoryPath }
            .sortedBy { it.lastModified() }
            .forEach { directory ->
                if (totalSize <= RemoteResourceLimits.MAX_GLOBAL_CACHE_BYTES) return@forEach
                val size = directorySize(directory)
                if (directory.deleteRecursively()) {
                    totalSize = (totalSize - size).coerceAtLeast(0L)
                }
            }

        if (totalSize > RemoteResourceLimits.MAX_GLOBAL_CACHE_BYTES) {
            throw RemoteResourceLimitException(
                UiText.Res(R.string.error_remote_cache_quota_total),
                "cannot shrink global remote cache into quota"
            )
        }
    }

    private fun trimDirectory(directory: File, maxBytes: Long, protectedFiles: Set<File>) {
        val files = directory.listFiles()?.filter { it.isFile } ?: return
        files.filter {
            it.length() > RemoteResourceLimits.MAX_PAGE_BYTES && it.canonicalFile !in protectedFiles
        }.forEach { it.delete() }
        val remainingFiles = directory.listFiles()?.filter { it.isFile } ?: return
        var totalSize = remainingFiles.sumOf { it.length().coerceAtLeast(0L) }
        if (totalSize <= maxBytes) return

        remainingFiles.sortedBy { it.lastModified() }.forEach { file ->
            if (totalSize <= maxBytes || file.canonicalFile in protectedFiles) return@forEach
            val size = file.length().coerceAtLeast(0L)
            if (file.delete()) {
                totalSize = (totalSize - size).coerceAtLeast(0L)
            }
        }
    }

    private fun directorySize(directory: File): Long {
        return directory.listFiles()?.sumOf { file ->
            if (file.isDirectory) directorySize(file) else file.length().coerceAtLeast(0L)
        } ?: 0L
    }
}
