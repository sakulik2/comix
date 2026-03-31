package xyz.sakulik.comic.worker

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import androidx.work.CoroutineWorker
import androidx.work.Data
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import xyz.sakulik.comic.model.db.AppDatabase
import xyz.sakulik.comic.model.db.ComicDao
import xyz.sakulik.comic.model.db.ComicEntity
import xyz.sakulik.comic.model.scanner.ComicNameParser
import xyz.sakulik.comic.model.scanner.CoverExtractor
import java.io.File

/**
 * 【跨进程装甲扫描舰】—— WorkManager 后台任务节点 (原生态，免除一切花里胡哨且失效的插件注入)
 * 
 * 架构意图：
 * 绝不能在 ViewModel 甚至协程中解析以 GB 计数的图片包！
 */
class LibraryScanWorker(
    context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    companion object {
        const val KEY_URI = "SCAN_TARGET_URI"
        const val PROGRESS_MSG = "PROGRESS_MSG"
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val uriString = inputData.getString(KEY_URI) ?: return@withContext androidx.work.ListenableWorker.Result.failure()
        val uri = Uri.parse(uriString)

        val rootTree = DocumentFile.fromTreeUri(applicationContext, uri) ?: return@withContext androidx.work.ListenableWorker.Result.failure()
        
        val comicDao = AppDatabase.getDatabase(applicationContext).comicDao()

        // 传递初始通讯波
        setProgress(workDataOf(PROGRESS_MSG to "准备潜入目录层级: ${rootTree.name}"))

        try {
            val allFiles = traverseTree(rootTree)
            val total = allFiles.size
            if (total == 0) {
                setProgress(workDataOf(PROGRESS_MSG to "空雷达！这里面根本没有漫画。"))
                return@withContext Result.success()
            }

            allFiles.forEachIndexed { index, file ->
                // 发回地面的声纳回波（UI 层监听这里就能看到平滑进度条跑动！）
                setProgress(workDataOf(PROGRESS_MSG to "正在解析 ($index/$total) : ${file.name}"))

                // ========== 核心 3 步走架构 ========== //
                val originalName = file.name ?: "Unknown"
                
                // 1. 无情粉碎正则阵
                val parsed = ComicNameParser.parse(originalName)
                
                // 获取文件扩展名
                val extension = originalName.substringAfterLast('.', "").lowercase()

                // 2. 剥出 0 帧缩略图以 WebP 静止态保护显存
                val coverCacheFile = File(applicationContext.cacheDir, "covers_${System.nanoTime()}.webp")
                CoverExtractor.extractCover(applicationContext, file.uri, extension, coverCacheFile)

                // 3. 将其供奉入 Room 神龛
                val entity = ComicEntity(
                    title = originalName,
                    uri = file.uri.toString(),
                    extension = extension,
                    coverCachePath = if (coverCacheFile.exists()) coverCacheFile.absolutePath else null,
                    seriesName = parsed.seriesName,
                    region = parsed.region,
                    format = parsed.format,
                    issueNumber = parsed.issueNumber,
                    volumeNumber = parsed.volumeNumber,
                    addedTime = System.currentTimeMillis()
                )
                comicDao.insert(entity)
            }

            setProgress(workDataOf(PROGRESS_MSG to "大获全胜！共降准录入 ${total} 份图鉴。"))
            androidx.work.ListenableWorker.Result.success()

        } catch (e: Exception) {
            e.printStackTrace()
            androidx.work.ListenableWorker.Result.failure(workDataOf("error" to e.localizedMessage))
        }
    }

    /**
     * 递归潜水员
     */
    private fun traverseTree(doc: DocumentFile): List<DocumentFile> {
        val result = mutableListOf<DocumentFile>()
        doc.listFiles().forEach { child ->
            if (child.isDirectory) {
                result.addAll(traverseTree(child))
            } else {
                val name = child.name?.lowercase() ?: ""
                if (name.endsWith(".cbz") || name.endsWith(".cbr") || name.endsWith(".zip") || name.endsWith(".pdf") || name.endsWith(".rar")) {
                    result.add(child)
                }
            }
        }
        return result
    }
}
