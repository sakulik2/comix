package xyz.sakulik.comic.model.metadata

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import xyz.sakulik.comic.model.network.RetrofitClient
import xyz.sakulik.comic.model.network.bangumi.BangumiService
import xyz.sakulik.comic.model.network.comicvine.ComicVineService

/**
 * 元数据中央仓库枢纽：全权负责调度多核引擎刮削
 * 处理 API 调用异常，确保服务永远可用，且为 UI 层提供全自动化替补接口
 */
class MetadataRepository(private val context: Context) {

    // 延迟惰性装载，由于其绑定了 DataStore 密钥流提取逻辑，按需建立最佳
    private val comicVineService by lazy {
        RetrofitClient.createService(context, "https://comicvine.gamespot.com/", ComicVineService::class.java)
    }

    private val bangumiService by lazy {
        RetrofitClient.createService(context, "https://api.bgm.tv/", BangumiService::class.java)
    }

    /**
     * @param keyword 最简净的书名搜索短尾词
     * @param strategy 强制、或采用容灾策略的拦截过滤规则
     */
    suspend fun searchComic(keyword: String, strategy: ScrapeStrategy): List<ScrapedComicInfo> = withContext(Dispatchers.IO) {
        val results = mutableListOf<ScrapedComicInfo>()

        suspend fun fetchVine() {
            try {
                // 如果用户不填 Token 这里也会照常抓取，只不过极大概率被后台返回空或者未授权
                val vineResponse = comicVineService.searchMetadata(query = keyword)
                vineResponse.results?.forEach { results.add(it.toDomainModel()) }
            } catch (e: Exception) { 
                e.printStackTrace()
                // 我们吞噬这个异常是因为主系统必须要稳定向下游流动传递并给降级库放行
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
                fetchVine() // 先打主力站
                if (results.isEmpty()) { 
                    fetchBangumi() // 如果主力扑街或者没有搜索到，拉取高容错性的副库 Bangumi
                }
            }
        }
        
        return@withContext results
    }

    /**
     * 【分层打击】 针对特定 Volume 下的特定 Issue 进行精准元数据抓取
     * @param volumeId ComicVine 的 Volume ID
     * @param issueNumber 期号
     */
    suspend fun searchIssue(volumeId: String, issueNumber: Float): ScrapedComicInfo? = withContext(Dispatchers.IO) {
        try {
            // 过滤条件格式: volume:ID,issue_number:NUM
            // 注意：issue_number 在 ComicVine API 中通常是字符串，但 filter 支持数字匹配
            val numInt = if (issueNumber % 1f == 0f) issueNumber.toInt().toString() else issueNumber.toString()
            val response = comicVineService.getIssues(filter = "volume:$volumeId,issue_number:$numInt")
            return@withContext response.results?.firstOrNull()?.toDomainModel()
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * 【下钻打击】 拉取指定系列下的全部分期（用于手动精准匹配）
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
