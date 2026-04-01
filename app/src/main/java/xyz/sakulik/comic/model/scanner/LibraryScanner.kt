package xyz.sakulik.comic.model.scanner

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import xyz.sakulik.comic.model.db.AppDatabase
import xyz.sakulik.comic.model.db.ComicEntity
import java.io.File
import java.util.UUID

class LibraryScanner(private val context: Context) {
    private val dao = AppDatabase.getDatabase(context).comicDao()
    private val coverDir = File(context.filesDir, "covers").apply { mkdirs() }

    private val _scanProgress = MutableStateFlow<String?>(null)
    val scanProgress = _scanProgress.asStateFlow()

    suspend fun scanDirectory(treeUri: Uri) = withContext(Dispatchers.IO) {
        val rootDoc = DocumentFile.fromTreeUri(context, treeUri) ?: return@withContext
        traverseAndAdd(rootDoc)
        _scanProgress.value = null // Done
    }

    private suspend fun traverseAndAdd(dir: DocumentFile) {
        val files = dir.listFiles()
        for (file in files) {
            if (file.isDirectory) {
                traverseAndAdd(file)
            } else {
                val name = file.name ?: continue
                val ext = name.substringAfterLast('.', "").lowercase()
                if (ext in listOf("cbz", "cbr", "pdf", "zip", "rar")) {
                    val uri = file.uri.toString()
                    val lastMod = file.lastModified()
                    val size = file.length()
                    val existing = dao.getComicByUri(uri)
                    
                    if (existing == null || existing.lastModified != lastMod || existing.fileSize != size) {
                        _scanProgress.value = "正在${if (existing == null) "扫描" else "更新"}: $name"
                        val coverFileName = UUID.randomUUID().toString() + ".webp"
                        val coverFile = File(coverDir, coverFileName)
                        val success = CoverExtractor.extractCover(context, file.uri, ext, coverFile)
                        
                        var defaultTitle = name.substringBeforeLast('.')
                        val parsedNameInfo = xyz.sakulik.comic.model.scanner.ComicNameParser.parse(name)
                        val cleanTitle = parsedNameInfo.seriesName
                        
                        var series: String? = parsedNameInfo.seriesName
                        var region = parsedNameInfo.region
                        var format = parsedNameInfo.format
                        var issueNum = parsedNameInfo.issueNumber
                        var volNum = parsedNameInfo.volumeNumber

                        var authors: String? = null
                        var summary: String? = null
                        var genres: String? = null
                        var publisher: String? = null
                        var rating: Float? = null

                        // 给本地的 XML 外挂兜底权限（假如它存在的话）
                        if (ext == "cbz" || ext == "zip") {
                            xyz.sakulik.comic.model.metadata.LocalComicInfoParser.parseFromZip(context, file.uri)?.let { local ->
                                series = local.series ?: series
                                authors = local.authors
                                summary = local.summary
                                genres = local.genres
                                publisher = local.publisher
                                rating = local.rating
                            }
                        }

                        // 为了排版好看与精准检索，title 被从杂乱名称中剥离后再基于解析数据重新格式组装！
                        val betterTitle = buildString {
                            append(series ?: cleanTitle)
                            if (volNum != null) append(" Vol.${volNum}")
                            if (issueNum != null) append(" #${issueNum}")
                        }.trim()

                        val entity = ComicEntity(
                            title = betterTitle.ifEmpty { defaultTitle },
                            uri = uri,
                            extension = ext,
                            coverCachePath = if (success) coverFile.absolutePath else null,
                            seriesName = series ?: cleanTitle,
                            region = region,
                            format = format,
                            issueNumber = issueNum,
                            volumeNumber = volNum,
                            authors = authors,
                            summary = summary,
                            genres = genres,
                            publisher = publisher,
                            rating = rating,
                            lastModified = lastMod,
                            fileSize = size
                        )
                        if (existing == null) {
                            dao.insert(entity)
                        } else {
                            dao.update(entity.copy(id = existing.id))
                        }
                    }
                }
            }
        }
    }
}
