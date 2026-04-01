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
        val comicDao = AppDatabase.getDatabase(applicationContext).comicDao()
        val contentResolver = applicationContext.contentResolver

        // 1. 获取要扫描的范围。如果有传入指定的 URI，则只扫这个；如果没有，则扫所有历史授权挂载点 (全量自动化)
        val uriString = inputData.getString(KEY_URI)
        val targetUris = if (uriString != null) {
            listOf(Uri.parse(uriString))
        } else {
            contentResolver.persistedUriPermissions.map { it.uri }
        }

        if (targetUris.isEmpty()) {
            setProgress(workDataOf(PROGRESS_MSG to "未找到授权扫描的目录，请先添加挂载点。"))
            return@withContext Result.success()
        }

        setProgress(workDataOf(PROGRESS_MSG to "准备扫描 ${targetUris.size} 个授权目录..."))

        // 存活的独立文件列表（防重入哈希）
        val aliveUriStrings = mutableSetOf<String>()
        var foundCount = 0

        // 阶段一：新资源入库与已存在验证
        for (treeUri in targetUris) {
            val rootTree = DocumentFile.fromTreeUri(applicationContext, treeUri) ?: continue
            val allFiles = traverseTree(rootTree)
            
            allFiles.forEachIndexed { index, file ->
                val fileUriStr = file.uri.toString()
                aliveUriStrings.add(fileUriStr)
                foundCount++

                setProgress(workDataOf(PROGRESS_MSG to "探测中 ($foundCount) : ${file.name}"))

                // [性能锁与自愈矩阵] 检查是否已入库，或其封面是否被旧版机制留在 cacheDir 遭系统清场
                val existing = comicDao.getComicByUri(fileUriStr)
                // 触发条件：新书，或者是老书但曾有封面物理文件却离奇蒸发了
                val needExtract = existing == null || (existing.coverCachePath != null && !File(existing.coverCachePath).exists())
                
                if (needExtract) {
                    val originalName = file.name ?: "Unknown"
                    val parsed = ComicNameParser.parse(originalName)
                    val extension = originalName.substringAfterLast('.', "").lowercase()

                    // [防丢改] 强制存入 filesDir 持久保存，而不是用随时被杀的 cacheDir
                    val coverDir = File(applicationContext.filesDir, "covers").apply { mkdirs() }
                    val coverFile = File(coverDir, "${java.util.UUID.randomUUID()}.webp")
                    
                    val extractSuccess = CoverExtractor.extractCover(applicationContext, file.uri, extension, coverFile)
                    val newCoverPath = if (extractSuccess && coverFile.exists()) coverFile.absolutePath else null

                    if (existing == null) {
                        val entity = ComicEntity(
                            title = originalName,
                            uri = fileUriStr,
                            extension = extension,
                            coverCachePath = newCoverPath,
                            seriesName = parsed.seriesName,
                            region = parsed.region,
                            format = parsed.format,
                            issueNumber = parsed.issueNumber,
                            volumeNumber = parsed.volumeNumber,
                            addedTime = System.currentTimeMillis(),
                            source = xyz.sakulik.comic.model.db.ComicSource.LOCAL,
                            location = fileUriStr
                        )
                        val insertedId = comicDao.insert(entity)
                        // 【自愈触发：入库即刮削】
                        if (insertedId != -1L) {
                            xyz.sakulik.comic.model.metadata.MetadataScraper.autoScrape(applicationContext, entity.copy(id = insertedId))
                        }
                    } else {
                        // [无损抢救] 如果书本早就在库里，只是封面被系统吞了，那么只换封面，绝不干扰辛苦留下的阅读进度！
                        comicDao.update(existing.copy(coverCachePath = newCoverPath))
                    }
                }
            }
        }

        // 阶段二：废墟自动清理（严格镜像剔除）
        setProgress(workDataOf(PROGRESS_MSG to "正在比对数据，清理物理剥离项..."))
        val allDbComics = comicDao.getAllComicsUnordered()
        var deletedCount = 0

        for (comic in allDbComics) {
            // [安全判定]：如果漫画的 URI 不属于本次全量扫描探测到的活体 URI，
            // 并且我们物理检查它确实不存在，则证明它被外部工具移除了。无情销毁！
            if (!aliveUriStrings.contains(comic.uri)) {
                // 退路检查：也许是在一个我们没有扫描权限的孤儿路径？
                // 如果是用户全扫，并且文件确实不存在，就杀。
                val doc = DocumentFile.fromSingleUri(applicationContext, Uri.parse(comic.uri))
                if (doc == null || !doc.exists()) {
                    // 同时删除无用的本地封面残躯
                    comic.coverCachePath?.let { File(it).delete() }
                    comicDao.delete(comic)
                    deletedCount++
                }
            }
        }

        val deleteMsg = if(deletedCount > 0) "，移除了 $deletedCount 本死链" else ""
        setProgress(workDataOf(PROGRESS_MSG to "同步结束！共校验 $foundCount 本图鉴$deleteMsg。"))
        return@withContext Result.success()
    }

    private fun traverseTree(doc: DocumentFile): List<DocumentFile> {
        val result = mutableListOf<DocumentFile>()
        doc.listFiles()?.forEach { child ->
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
