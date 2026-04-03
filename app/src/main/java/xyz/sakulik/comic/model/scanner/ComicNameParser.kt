package xyz.sakulik.comic.model.scanner

import xyz.sakulik.comic.model.db.ComicFormat
import xyz.sakulik.comic.model.db.ComicRegion

data class ParsedComicName(
    val seriesName: String,
    val region: ComicRegion,
    val format: ComicFormat,
    val issueNumber: Float?,
    val volumeNumber: Float?,
    val year: String? = null
)

object ComicNameParser {

    fun parse(originalFilename: String): ParsedComicName {
        // 先脱去底层封套后缀名
        var workingName = originalFilename.replace(Regex("\\.(cbz|cbr|pdf|zip|rar)$", RegexOption.IGNORE_CASE), "")

        var region = ComicRegion.UNKNOWN
        var format = ComicFormat.UNKNOWN
        var issueNumber: Float? = null
        var volumeNumber: Float? = null
        var year: String? = null

        // 提取年份信息 (例如: 2011, (2011), [2011])
        val yearPattern = Regex("(?i)[\\(\\[]\\s*(\\d{4})\\s*[\\)\\]]|\\b(\\d{4})\\b")
        yearPattern.find(workingName)?.let { match ->
            year = match.groupValues.getOrNull(1)?.ifEmpty { match.groupValues.getOrNull(2) }
        }

        // 步骤一：识别漫画类型特征（日漫/美漫）
        
        // 抓取日漫“话”特征
        val mangaIssuePattern = Regex("(?i)(第\\s*(\\d+(\\.\\d+)?)\\s*话|Ch\\.?\\s*(\\d+(\\.\\d+)?)|Ep\\s*(\\d+(\\.\\d+)?)|Extra\\s*(\\d+)?|番外(\\d+)?|附录)")
        // 抓取日漫“卷”特征，包括有的时候混淆成 Vol 
        val mangaVolPattern = Regex("(?i)(第\\s*(\\d+)\\s*卷|Vol\\.?\\s*(\\d+(\\.\\d+)?)|Book\\s*(\\d+)|Part\\s*(\\d+))")
        
        // 捕获美漫特定的期号与版本标识
        val comicIssuePattern = Regex("(?i)(#\\s*(\\d+(\\.\\d+)?)|Issue\\s*(\\d+(\\.\\d+)?))")
        val hcPattern = Regex("(?i)(HC|Hardcover|Deluxe Edition|Library Edition)")
        val tpbPattern = Regex("(?i)(TPB|Trade Paperback)")
        val omnibusPattern = Regex("(?i)Omnibus|Compendium")

        // 尝试捕获日漫单集
        if (mangaIssuePattern.containsMatchIn(workingName)) {
            val match = mangaIssuePattern.find(workingName)
            val groups = match?.groupValues
            val numStr = groups?.getOrNull(2)?.ifEmpty { groups.getOrNull(4) }?.ifEmpty { groups.getOrNull(6) }?.ifEmpty { groups.getOrNull(8) }?.ifEmpty { groups.getOrNull(9) }
            issueNumber = numStr?.toFloatOrNull()
            region = ComicRegion.MANGA
            format = ComicFormat.CHAPTER
        }
        
        // 尝试捕获含有“卷”或 "Vol" 字样的书籍
        if (mangaVolPattern.containsMatchIn(workingName)) {
            val match = mangaVolPattern.find(workingName)
            val groups = match?.groupValues
            val vStr = groups?.getOrNull(2)?.ifEmpty { groups.getOrNull(3) }?.ifEmpty { groups.getOrNull(5) }?.ifEmpty { groups.getOrNull(6) }
            volumeNumber = vStr?.toFloatOrNull() ?: volumeNumber
            if (format == ComicFormat.UNKNOWN || format == ComicFormat.CHAPTER) { 
                format = ComicFormat.TANKOBON 
            }
            if (!Regex("[\u4e00-\u9fa5]").containsMatchIn(originalFilename)) {
                format = ComicFormat.TPB
                region = ComicRegion.COMIC
            } else {
                region = ComicRegion.MANGA
            }
        }

        // 捕获带有 # 号或明确 Issue 标识的期号
        if (comicIssuePattern.containsMatchIn(workingName)) {
            val match = comicIssuePattern.find(workingName)
            val issueStr = match?.groupValues?.getOrNull(2)?.ifEmpty { match.groupValues.getOrNull(4) }
            issueNumber = issueStr?.toFloatOrNull()
            region = ComicRegion.COMIC
            format = ComicFormat.ISSUE
        }

        // 捕获精装本(HC)或合订本(TPB/Omnibus)等格式
        if (hcPattern.containsMatchIn(workingName)) {
            region = ComicRegion.COMIC
            format = ComicFormat.HC
        } else if (tpbPattern.containsMatchIn(workingName)) {
            region = ComicRegion.COMIC
            format = ComicFormat.TPB
        } else if (omnibusPattern.containsMatchIn(workingName)) {
            region = ComicRegion.COMIC
            format = ComicFormat.OMNIBUS
        }

        // 漏网之鱼检测
        if (region == ComicRegion.UNKNOWN && Regex("(汉化|组|掃圖|扫图|个人|贴吧)").containsMatchIn(workingName)) {
            region = ComicRegion.MANGA
        }

        // 步骤二：如果没有识别到明确的期号，尝试直接提取纯数字作为期号
        if (issueNumber == null) {
            val fallbackIssuePattern = Regex("\\b(\\d{1,3})\\b")
            // 找寻所有 matches，排除掉已经被识别为年份的那个数字
            fallbackIssuePattern.findAll(workingName).forEach { match ->
                val numStr = match.groupValues[1]
                if (numStr != year) {
                    issueNumber = numStr.toFloatOrNull()
                }
            }
        }

        // 步骤三：清洗文件名，提取系列标题
        
        workingName = workingName.replace(Regex("\\[.*?]"), "")
        workingName = workingName.replace(Regex("\\(.*?\\)"), "")
        workingName = workingName.replace(Regex("【.*?】"), "")
        workingName = workingName.replace(Regex("（.*?）"), "")

        // 清除已匹配的正则特征，避免干扰系列名称识别
        workingName = workingName.replace(mangaIssuePattern, "")
        workingName = workingName.replace(mangaVolPattern, "")
        workingName = workingName.replace(comicIssuePattern, "")
        workingName = workingName.replace(hcPattern, "")
        workingName = workingName.replace(tpbPattern, "")
        workingName = workingName.replace(omnibusPattern, "")

        // 处理残留的特殊符号与年份标记 (如 2022)
        workingName = workingName.replace(Regex("\\(\\s*\\d{4}\\s*\\)"), "")
        workingName = workingName.replace(Regex("\\d{4}"), "") // 纯年份

        // 清除常用状态或质量相关的后缀
        workingName = workingName.replace(Regex("(?i)(完结|连载中|全彩|扫图版|个人单扫|高清|1080p|RAW|Digital|C2C)"), "")
        workingName = workingName.replace(Regex("[\\-_~·.]"), " ")   // 变通符抹去，增加点号和中点
        workingName = workingName.replace(Regex("\\s+"), " ")     // 回车或连环空格归于一个

        // 最终提取出的系列名称
        val cleanSeriesName = workingName.trim().ifEmpty { originalFilename.substringBeforeLast('.') }

        return ParsedComicName(
            seriesName = cleanSeriesName,
            region = region,
            format = format,
            issueNumber = issueNumber,
            volumeNumber = volumeNumber,
            year = year
        )
    }
}
