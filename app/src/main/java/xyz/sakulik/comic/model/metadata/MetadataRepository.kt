package xyz.sakulik.comic.model.metadata

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import xyz.sakulik.comic.model.network.RetrofitClient
import xyz.sakulik.comic.model.network.bangumi.BangumiService
import xyz.sakulik.comic.model.network.comicvine.ComicVineService

/**
 * 元数据仓库类；负责协调不同来源（如 ComicVine, Bangumi）的元数据刮削
 */
class MetadataRepository(private val context: Context) {

    // 延迟加载服务，只有在需要时才根据 DataStore 中的配置初始化
    private val comicVineService by lazy {
        RetrofitClient.createService(context, "https://comicvine.gamespot.com/", ComicVineService::class.java)
    }

    private val bangumiService by lazy {
        RetrofitClient.createService(context, "https://api.bgm.tv/", BangumiService::class.java)
    }
    
    /**
     * @param keyword 搜索关键词
     * @param strategy 刮削引擎选择策略
     */
    suspend fun searchComic(keyword: String, strategy: ScrapeStrategy): List<ScrapedComicInfo> = withContext(Dispatchers.IO) {
        val results = mutableListOf<ScrapedComicInfo>()

        suspend fun fetchVine() {
            try {
                // 如果用户未填写 API Key，API 可能返回空或 401 错误
                val vineResponse = comicVineService.searchMetadata(query = keyword)
                vineResponse.results?.forEach { results.add(it.toDomainModel()) }
            } catch (e: Exception) { 
                e.printStackTrace()
            }
        }

        suspend fun fetchBangumi() {
            try {
                val bangumiResponse = bangumiService.searchSubjects(keywords = keyword)
                bangumiResponse.list?.forEach { results.add(it.toDomainModel()) }
            } catch (e: Exception) { 
                e.printStackTrace() 
            }
        }

        when (strategy) {
            ScrapeStrategy.COMIC_VINE_ONLY -> fetchVine()
            ScrapeStrategy.BANGUMI_ONLY -> fetchBangumi()
            ScrapeStrategy.SMART_FALLBACK -> {
                fetchVine() 
                if (results.isEmpty()) { 
                    fetchBangumi() 
                }
            }
        }
        
        return@withContext results
    }

    /**
     * 针对特定系列 (Volume) 下的特定分期 (Issue) 进行精准抓取
     * @param volumeId ComicVine 的 Volume ID
     * @param issueNumber 期号
     */
    suspend fun searchIssue(volumeId: String, issueNumber: Float): ScrapedComicInfo? = withContext(Dispatchers.IO) {
        try {
            // 过滤格式: volume:ID,issue_number:NUM
            val numInt = if (issueNumber % 1f == 0f) issueNumber.toInt().toString() else issueNumber.toString()
            val response = comicVineService.getIssues(filter = "volume:$volumeId,issue_number:$numInt")
            return@withContext response.results?.firstOrNull()?.toDomainModel()
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * 获取指定系列下的所有分期列表
     */
    suspend fun getIssuesByVolumeId(volumeId: String): List<ScrapedComicInfo> = withContext(Dispatchers.IO) {
        try {
            val response = comicVineService.getIssues(filter = "volume:$volumeId")
            return@withContext response.results?.map { it.toDomainModel() } ?: emptyList()
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }
}
