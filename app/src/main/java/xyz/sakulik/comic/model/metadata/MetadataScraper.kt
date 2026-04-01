package xyz.sakulik.comic.model.metadata

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import okhttp3.OkHttpClient
import okhttp3.Request
import xyz.sakulik.comic.model.db.AppDatabase
import xyz.sakulik.comic.model.db.ComicEntity
import xyz.sakulik.comic.model.db.ComicFormat
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/**
 * 核心刮削调度引擎 —— 解耦自 ViewModel，允许在 Worker 中异步静默执行。
 */
object MetadataScraper {
    private val client = OkHttpClient()

    suspend fun autoScrape(context: Context, comic: ComicEntity) {
        val dao = AppDatabase.getDatabase(context).comicDao()
        val repository = MetadataRepository(context)
        val keyword = FilenameCleaner.clean(comic.title)

        try {
            val results = repository.searchComic(keyword, ScrapeStrategy.SMART_FALLBACK)
            val best = results.firstOrNull() ?: return

            var finalCoverPath = comic.coverCachePath
            best.coverUrl?.let { url ->
                val request = Request.Builder().url(url).build()
                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val bytes = response.body?.bytes()
                        if (bytes != null) {
                            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                            if (bitmap != null) {
                                val file = File(context.filesDir, "covers/${UUID.randomUUID()}.webp")
                                file.parentFile?.mkdirs()
                                FileOutputStream(file).use { out ->
                                    bitmap.compress(Bitmap.CompressFormat.WEBP, 85, out)
                                }
                                bitmap.recycle()
                                finalCoverPath = file.absolutePath
                            }
                        }
                    }
                }
            }

            val updated = comic.copy(
                title = best.title + buildString {
                    if (comic.volumeNumber != null) append(" Vol.${comic.volumeNumber}")
                    if (comic.issueNumber != null) append(" #${comic.issueNumber}")
                },
                coverCachePath = finalCoverPath,
                seriesName = best.title,
                region = best.region,
                authors = best.authors.joinToString(", ").takeIf { it.isNotEmpty() } ?: comic.authors,
                summary = best.summary ?: comic.summary,
                genres = best.genres.joinToString(", ").takeIf { it.isNotEmpty() } ?: comic.genres,
                publisher = best.publisher ?: comic.publisher,
                rating = best.rating ?: comic.rating
            )
            dao.update(updated)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
