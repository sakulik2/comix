package xyz.sakulik.comic.model.metadata

import android.content.Context
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * 本地规范外挂元数据解析器
 * 用于提取 CBZ/ZIP 压缩包内部可能包含的 ComicInfo.xml
 */
object LocalComicInfoParser {

    /**
     * @return 映射出的元数据集合；如果在 Zip 内没发现 xml 则返回 null
     */
    suspend fun parseFromZip(context: Context, uri: Uri): LocalMetadata? = withContext(Dispatchers.IO) {
        try {
            val contentResolver = context.contentResolver
            contentResolver.openInputStream(uri)?.use { stream ->
                ZipInputStream(stream).use { zis ->
                    while (true) {
                        val entry = zis.nextEntry ?: break
                        if (entry.name.equals("ComicInfo.xml", ignoreCase = true)) {
                            return@withContext parseXml(zis)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("LocalComicInfoParser", "Failed to parse ComicInfo.xml", e)
        }
        return@withContext null
    }

    private fun parseXml(inputStream: InputStream): LocalMetadata {
        val factory = XmlPullParserFactory.newInstance()
        factory.isNamespaceAware = false
        val parser = factory.newPullParser()
        // 千万不要关闭 stream，因为目前处于 ZipInputStream 的游标提取状态，提前关闭会损坏外部循环
        parser.setInput(inputStream, "UTF-8")

        var series: String? = null
        var summary: String? = null
        var publisher: String? = null
        var writer: String? = null
        var penciller: String? = null
        var genre: String? = null
        var rating: Float? = null

        var eventType = parser.eventType
        var currentTag = ""

        while (eventType != XmlPullParser.END_DOCUMENT) {
            when (eventType) {
                XmlPullParser.START_TAG -> currentTag = parser.name
                XmlPullParser.TEXT -> {
                    val text = parser.text?.trim()
                    if (!text.isNullOrEmpty()) {
                        when (currentTag) {
                            "Series" -> series = text
                            "Summary" -> summary = text
                            "Publisher" -> publisher = text
                            "Writer" -> writer = text
                            "Penciller" -> penciller = text
                            "Genre" -> genre = text
                            "CommunityRating" -> rating = text.toFloatOrNull()
                        }
                    }
                }
                XmlPullParser.END_TAG -> currentTag = ""
            }
            eventType = parser.next()
        }

        val authors = mutableListOf<String>()
        writer?.let { authors.add(it) }
        penciller?.let { if (it != writer) authors.add(it) }

        return LocalMetadata(
            series = series,
            authors = if (authors.isNotEmpty()) authors.joinToString(", ") else null,
            summary = summary,
            genres = genre,
            publisher = publisher,
            rating = rating
        )
    }

    data class LocalMetadata(
        val series: String?,
        val authors: String?,
        val summary: String?,
        val genres: String?,
        val publisher: String?,
        val rating: Float?
    )
}
