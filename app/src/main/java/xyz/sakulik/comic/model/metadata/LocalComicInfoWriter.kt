package xyz.sakulik.comic.model.metadata

import android.content.Context
import android.net.Uri
import android.util.Log
import android.util.Xml
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import xyz.sakulik.comic.model.db.ComicEntity
import java.io.File
import java.io.FileOutputStream
import java.io.StringWriter

/**
 * 负责将 ComicEntity 的元数据导出为标准的 ComicInfo.xml
 */
object LocalComicInfoWriter {

    /**
     * 生成 XML 字符串
     */
    fun generateXml(comic: ComicEntity): String {
        val writer = StringWriter()
        val serializer = Xml.newSerializer()
        try {
            serializer.setOutput(writer)
            serializer.startDocument("UTF-8", true)
            serializer.setFeature("http://xmlpull.org/v1/doc/features.html#indent-output", true)
            
            serializer.startTag(null, "ComicInfo")
            
            writeTag(serializer, "Title", comic.issueTitle ?: comic.title)
            writeTag(serializer, "Series", comic.seriesName)
            writeTag(serializer, "Number", comic.issueNumber?.toString())
            writeTag(serializer, "Writer", comic.authors)
            writeTag(serializer, "Publisher", comic.publisher)
            writeTag(serializer, "Genre", comic.genres)
            writeTag(serializer, "Summary", comic.summary)
            writeTag(serializer, "Year", comic.year)
            writeTag(serializer, "CommunityRating", comic.rating?.toString())
            writeTag(serializer, "Manga", if (comic.region == xyz.sakulik.comic.model.db.ComicRegion.MANGA) "Yes" else "No")

            // 写入 Pages 信息，标记封面
            if (comic.customCoverPage != null || comic.totalPages > 0) {
                serializer.startTag(null, "Pages")
                val total = if (comic.totalPages > 0) comic.totalPages else 1
                for (i in 0 until total) {
                    serializer.startTag(null, "Page")
                    serializer.attribute(null, "Image", i.toString())
                    if (i == (comic.customCoverPage ?: 0)) {
                        serializer.attribute(null, "Type", "FrontCover")
                    }
                    serializer.endTag(null, "Page")
                }
                serializer.endTag(null, "Pages")
            }

            serializer.endTag(null, "ComicInfo")
            serializer.endDocument()
            return writer.toString()
        } catch (e: Exception) {
            Log.e("ComicInfoWriter", "Failed to generate XML", e)
            return ""
        }
    }

    private fun writeTag(serializer: org.xmlpull.v1.XmlSerializer, tag: String, value: String?) {
        if (!value.isNullOrEmpty()) {
            serializer.startTag(null, tag)
            serializer.text(value)
            serializer.endTag(null, tag)
        }
    }

    /**
     * 尝试在漫画文件同级目录下写入 .xml 文件（主要针对 PDF/CBR）
     */
    suspend fun writeCompanionXml(context: Context, comic: ComicEntity): Boolean = withContext(Dispatchers.IO) {
        try {
            val xml = generateXml(comic)
            if (xml.isEmpty()) return@withContext false

            val uri = Uri.parse(comic.location)
            if (uri.scheme == "content") {
                val docFile = DocumentFile.fromSingleUri(context, uri) ?: return@withContext false
                val parent = docFile.parentFile ?: return@withContext false
                
                val baseName = docFile.name?.substringBeforeLast(".") ?: "ComicInfo"
                val xmlName = "ComicInfo.xml" // 规范写法，或者用 basename.xml
                
                // 查找是否已存在
                var xmlFile = parent.findFile(xmlName)
                if (xmlFile == null) {
                    xmlFile = parent.createFile("text/xml", xmlName)
                }
                
                xmlFile?.let {
                    context.contentResolver.openOutputStream(it.uri)?.use { out ->
                        out.write(xml.toByteArray())
                    }
                    return@withContext true
                }
            } else {
                // 普通文件路径
                val file = File(comic.location)
                val xmlFile = File(file.parent, "ComicInfo.xml")
                xmlFile.writeText(xml)
                return@withContext true
            }
        } catch (e: Exception) {
            Log.e("ComicInfoWriter", "Failed to write companion XML", e)
        }
        false
    }
}
