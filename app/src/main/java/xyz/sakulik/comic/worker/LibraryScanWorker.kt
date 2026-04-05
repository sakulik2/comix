package xyz.sakulik.comic.worker

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import androidx.work.CoroutineWorker
import androidx.work.Data
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import xyz.sakulik.comic.model.db.AppDatabase
import xyz.sakulik.comic.model.db.ComicDao
import xyz.sakulik.comic.model.db.ComicEntity
import xyz.sakulik.comic.model.scanner.ComicNameParser
import xyz.sakulik.comic.model.scanner.CoverExtractor
import xyz.sakulik.comic.model.preferences.SettingsDataStore
import kotlinx.coroutines.flow.first
import java.io.File

/**
 * WorkManager 后台扫描任务
 *
 * 扫描指定文件夹（或全库）中的漫画档案文件，并将结果同步到数据库
 * 对每个新入库的书目自动触发元数据扫描
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

        //\ 1 获取要扫描的范围如果有传入指定的 URI，则只扫这个；如果没有，则扫所有历史授权挂载点 (全量自动化)
        val uriString = inputData.getString(KEY_URI)
        val targetUris = if (uriString != null) {
            listOf(Uri.parse(uriString))
        } else {
            contentResolver.persistedUriPermissions.map { it.uri }
        }

        if (targetUris.isEmpty()) {
            setProgress(workDataOf(PROGRESS_MSG to "未找到授权扫描的目录，请先添加挂载点"))
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
            
            // 并发限制信号量，防止大批量读取 archive 造成内存/句柄压力过大
            val semaphore = Semaphore(4)
            
            val deferreds = allFiles.mapIndexed { index, file ->
                async {
                    semaphore.withPermit {
                        val fileUriStr = file.uri.toString()
                        aliveUriStrings.add(fileUriStr)
                        
                        // 为了 UI 丝滑，每处理 10% 或 10 本书上报一次进度，而不是疯狂刷新
                        if (index % 10 == 0 || index == allFiles.size - 1) {
                            setProgress(workDataOf(PROGRESS_MSG to "同步进度: ${index + 1}/${allFiles.size} - ${file.name}"))
                        }

                        val existing = comicDao.getComicByUri(fileUriStr)
                        val needExtract = existing == null || existing.coverCachePath == null || !File(existing.coverCachePath).exists()
                        if (needExtract) {
                            Log.i("LibraryScanWorker", "Extracting cover for: ${file.name}")
                        }
                        
                        if (needExtract) {
                            val originalName = file.name ?: "Unknown"
                            val parsed = ComicNameParser.parse(originalName)
                            val extension = originalName.substringAfterLast('.', "").lowercase()

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
                                
                                // 入库后触发元数据嗅探（仅在用户开启“元数据匹配”时执行）
                                if (insertedId != -1L) {
                                    val metadataEnabled = SettingsDataStore.getMetadataEnabledFlow(applicationContext).first()
                                    if (metadataEnabled) {
                                        xyz.sakulik.comic.model.metadata.MetadataScraper.autoScrape(applicationContext, entity.copy(id = insertedId))
                                    }
                                }
                            } else {
                                comicDao.update(existing.copy(coverCachePath = newCoverPath))
                            }
                        }
                    }
                }
            }
            deferreds.awaitAll()
            foundCount += allFiles.size
        }

        // 阶段二：废墟自动清理（严格镜像剔除）
        // [极重要修复]：如果是局部扫描（uriString != null），则只清理该目录下的死链，不可动全局！
        setProgress(workDataOf(PROGRESS_MSG to "正在比对数据，清理物理剥离项..."))
        val allDbComics = comicDao.getAllComicsUnordered()
        var deletedCount = 0

        for (comic in allDbComics) {
            // 如果是指定的单目录扫描，先判定该漫画是否位于该目录下
            val isWithinCurrentScanScope = if (uriString != null) {
                comic.uri.startsWith(uriString)
            } else {
                // 如果是全量扫描，作用域覆盖所有历史授权目录
                targetUris.any { comic.uri.startsWith(it.toString()) }
            }

            if (isWithinCurrentScanScope && !aliveUriStrings.contains(comic.uri)) {
                // 仅对在扫描范围内但未探测到活体的记录进行物理验证
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
        setProgress(workDataOf(PROGRESS_MSG to "同步结束！共校验 $foundCount 本图鉴$deleteMsg"))
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
