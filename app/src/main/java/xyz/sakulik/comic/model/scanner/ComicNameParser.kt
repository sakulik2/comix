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

        // ==== [核心优化] 预捕获年份信息 (如 2011, (2011), [2011]) ====
        val yearPattern = Regex("(?i)[\\(\\[]\\s*(\\d{4})\\s*[\\)\\]]|\\b(\\d{4})\\b")
        yearPattern.find(workingName)?.let { match ->
            year = match.groupValues.getOrNull(1)?.ifEmpty { match.groupValues.getOrNull(2) }
        }

        // ==== 步骤一：针对日漫/欧美的分流特征极高密度雷达锁定 ====
        
        // 抓取日漫“话”特征
        val mangaIssuePattern = Regex("(?i)(第\\s*(\\d+(\\.\\d+)?)\\s*话|Ch\\.?\\s*(\\d+(\\.\\d+)?)|Ep\\s*(\\d+(\\.\\d+)?)|Extra\\s*(\\d+)?|番外(\\d+)?|附录)")
        // 抓取日漫“卷”特征，包括有的时候混淆成 Vol 
        val mangaVolPattern = Regex("(?i)(第\\s*(\\d+)\\s*卷|Vol\\.?\\s*(\\d+(\\.\\d+)?)|Book\\s*(\\d+)|Part\\s*(\\d+))")
        
        // 【关键】捕捉极度细分的美漫复杂版号标识
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

        // 抓捕带 # 号或明确发行的期物
        if (comicIssuePattern.containsMatchIn(workingName)) {
            val match = comicIssuePattern.find(workingName)
            val issueStr = match?.groupValues?.getOrNull(2)?.ifEmpty { match.groupValues.getOrNull(4) }
            issueNumber = issueStr?.toFloatOrNull()
            region = ComicRegion.COMIC
            format = ComicFormat.ISSUE
        }

        // 抓捕高级精装与合订部头大包
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

        // ==== 步骤三：【兜底拾遗】针对无符号期号的暴力抓取 (如 Justice League 01) ====
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

        // ==== 步骤四：彻底粉碎切割标记物，剥离提取物 ====
        
        workingName = workingName.replace(Regex("\\[.*?]"), "")
        workingName = workingName.replace(Regex("\\(.*?\\)"), "")
        workingName = workingName.replace(Regex("【.*?】"), "")
        workingName = workingName.replace(Regex("（.*?）"), "")

        // 粉碎所有高威能捕获过的正则表达式雷达阵群残渣！防止污染 SerieName
        workingName = workingName.replace(mangaIssuePattern, "")
        workingName = workingName.replace(mangaVolPattern, "")
        workingName = workingName.replace(comicIssuePattern, "")
        workingName = workingName.replace(hcPattern, "")
        workingName = workingName.replace(tpbPattern, "")
        workingName = workingName.replace(omnibusPattern, "")

        // 处理残留的特殊符号与年份标记 (如 2022)
        workingName = workingName.replace(Regex("\\(\\s*\\d{4}\\s*\\)"), "")
        workingName = workingName.replace(Regex("\\d{4}"), "") // 纯年份

        // 粉碎无聊的尾缀
        workingName = workingName.replace(Regex("(?i)(完结|连载中|全彩|扫图版|个人单扫|高清|1080p|RAW|Digital|C2C)"), "")
        workingName = workingName.replace(Regex("[\\-_~·.]"), " ")   // 变通符抹去，增加点号和中点
        workingName = workingName.replace(Regex("\\s+"), " ")     // 回车或连环空格归于一个

        // 干瘪纯正的母序列名
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
