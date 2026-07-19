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
                if (android.provider.DocumentsContract.isDocumentUri(context, uri)) {
                    val documentId = android.provider.DocumentsContract.getDocumentId(uri)
                    val parentDocumentId = if (documentId.contains("/")) {
                        documentId.substringBeforeLast("/")
                    } else {
                        null
                    }
                    
                    val persistedUris = context.contentResolver.persistedUriPermissions.map { it.uri }
                    var treeUri: Uri? = null
                    var matchedParentDocId: String? = parentDocumentId
                    
                    for (persistedUri in persistedUris) {
                        if (persistedUri.authority == uri.authority) {
                            try {
                                val treeDocId = android.provider.DocumentsContract.getTreeDocumentId(persistedUri)
                                if (documentId.startsWith(treeDocId)) {
                                    treeUri = persistedUri
                                    if (matchedParentDocId == null) {
                                        matchedParentDocId = treeDocId
                                    }
                                    break
                                }
                            } catch (e: Exception) {}
                        }
                    }
                    
                    if (treeUri != null && matchedParentDocId != null) {
                        val childrenUri = android.provider.DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, matchedParentDocId)
                        var existingXmlUri: Uri? = null
                        context.contentResolver.query(
                            childrenUri,
                            arrayOf(android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID, android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                            null, null, null
                        )?.use { cursor ->
                            val idIndex = cursor.getColumnIndex(android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                            val nameIndex = cursor.getColumnIndex(android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                            while (cursor.moveToNext()) {
                                val name = cursor.getString(nameIndex)
                                if (name.equals("ComicInfo.xml", ignoreCase = true)) {
                                    val docId = cursor.getString(idIndex)
                                    existingXmlUri = android.provider.DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
                                    break
                                }
                            }
                        }
                        
                        val targetUri = existingXmlUri ?: android.provider.DocumentsContract.createDocument(
                            context.contentResolver,
                            android.provider.DocumentsContract.buildDocumentUriUsingTree(treeUri, matchedParentDocId),
                            "text/xml",
                            "ComicInfo.xml"
                        )
                        
                        targetUri?.let {
                            context.contentResolver.openOutputStream(it)?.use { out ->
                                out.write(xml.toByteArray())
                            }
                            return@withContext true
                        }
                    }
                }
                return@withContext false
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
