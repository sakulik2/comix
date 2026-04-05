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
    suspend fun parseFromZip(context: Context, uri: Uri): ComicInfo? = withContext(Dispatchers.IO) {
        try {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                ZipInputStream(stream).use { zis ->
                    while (true) {
                        val entry = zis.nextEntry ?: break
                        if (entry.name.equals("ComicInfo.xml", ignoreCase = true)) {
                            return@withContext parseXml(zis)
                        }
                        zis.closeEntry()
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("LocalComicInfoParser", "Failed to parse ComicInfo.xml", e)
        }
        return@withContext null
    }

    fun parseXml(inputStream: InputStream): ComicInfo {
        val factory = XmlPullParserFactory.newInstance()
        factory.isNamespaceAware = false
        val parser = factory.newPullParser()
        parser.setInput(inputStream, "UTF-8")

        val info = ComicInfo()
        var eventType = parser.eventType
        var currentTag = ""

        while (eventType != XmlPullParser.END_DOCUMENT) {
            when (eventType) {
                XmlPullParser.START_TAG -> currentTag = parser.name
                XmlPullParser.TEXT -> {
                    val text = parser.text?.trim()
                    if (!text.isNullOrEmpty()) {
                        when (currentTag) {
                            "Title" -> info.title = text
                            "Series" -> info.series = text
                            "Number" -> info.number = text
                            "Count" -> info.count = text.toIntOrNull()
                            "Volume" -> info.volume = text.toIntOrNull()
                            "Year" -> info.year = text.toIntOrNull()
                            "Month" -> info.month = text.toIntOrNull()
                            "Day" -> info.day = text.toIntOrNull()
                            "Writer" -> info.writer = text
                            "Penciller" -> info.penciller = text
                            "Inker" -> info.inker = text
                            "Letterer" -> info.letterer = text
                            "CoverArtist" -> info.coverArtist = text
                            "Editor" -> info.editor = text
                            "Publisher" -> info.publisher = text
                            "Genre" -> info.genre = text
                            "Summary" -> info.summary = text
                            "Manga" -> info.manga = text
                            "CommunityRating" -> info.rating = text.toFloatOrNull()
                        }
                    }
                }
                XmlPullParser.END_TAG -> currentTag = ""
            }
            eventType = parser.next()
        }
        return info
    }
}
