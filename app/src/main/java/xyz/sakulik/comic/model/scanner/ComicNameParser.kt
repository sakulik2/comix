package xyz.sakulik.comic.model.scanner

import xyz.sakulik.comic.model.db.ComicFormat
import xyz.sakulik.comic.model.db.ComicRegion

data class ParsedComicName(
    val seriesName: String,
    val region: ComicRegion,
    val format: ComicFormat,
    val issueNumber: Float?,
    val volumeNumber: Float?
)

object ComicNameParser {

    fun parse(originalFilename: String): ParsedComicName {
        // 先脱去底层封套后缀名
        var workingName = originalFilename.replace(Regex("\\.(cbz|cbr|pdf|zip|rar)$", RegexOption.IGNORE_CASE), "")

        var region = ComicRegion.UNKNOWN
        var format = ComicFormat.UNKNOWN
        var issueNumber: Float? = null
        var volumeNumber: Float? = null

        // ==== 步骤一：针对日漫/欧美的分流特征极高密度雷达锁定 ====
        
        // 抓取日漫“话”特征
        val mangaIssuePattern = Regex("(?i)(第\\s*(\\d+(\\.\\d+)?)\\s*话|Ch\\.?\\s*(\\d+(\\.\\d+)?))")
        // 抓取日漫“卷”特征，包括有的时候混淆成 Vol 
        val mangaVolPattern = Regex("(?i)(第\\s*(\\d+)\\s*卷|Vol\\.?\\s*(\\d+(\\.\\d+)?))")
        
        // 【关键】捕捉极度细分的美漫复杂版号标识
        val comicIssuePattern = Regex("(?i)(#\\s*(\\d+(\\.\\d+)?)|Issue\\s*(\\d+(\\.\\d+)?))")
        val hcPattern = Regex("(?i)(HC|Hardcover|Deluxe Edition)")
        val tpbPattern = Regex("(?i)(TPB|Trade Paperback)")
        val omnibusPattern = Regex("(?i)Omnibus")

        // 尝试捕获日漫单集
        if (mangaIssuePattern.containsMatchIn(workingName)) {
            val match = mangaIssuePattern.find(workingName)
            val numStr = match?.groupValues?.getOrNull(2)?.ifEmpty { match.groupValues.getOrNull(4) }
            issueNumber = numStr?.toFloatOrNull()
            region = ComicRegion.MANGA
            format = ComicFormat.CHAPTER // 对于日漫，话 = Chapter
        }
        
        // 尝试捕获含有“卷”或 "Vol" 字样的书籍
        if (mangaVolPattern.containsMatchIn(workingName)) {
            val match = mangaVolPattern.find(workingName)
            val vStr = match?.groupValues?.getOrNull(2)?.ifEmpty { match.groupValues.getOrNull(3) }
            volumeNumber = vStr?.toFloatOrNull() ?: volumeNumber
            if (format == ComicFormat.UNKNOWN || format == ComicFormat.CHAPTER) { 
                // 只带卷号，那就叫作日漫体系下的单行本
                format = ComicFormat.TANKOBON 
            }
            // 歧义点判断：如果它没有一个中文字（完全是 Vol.XX 组成的美式英名），将其划为美漫商合辑 TPB
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
            format = ComicFormat.ISSUE // 降服为单期特报
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

        // 漏网之鱼检测：如果还是无法归类，但含有汉化等标符
        if (region == ComicRegion.UNKNOWN && Regex("(汉化|组|掃圖|扫图|个人|贴吧)").containsMatchIn(workingName)) {
            region = ComicRegion.MANGA
        }

        // ==== 步骤二：彻底粉碎切割标记物，剥离提取物 ====
        
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

        // 粉碎无聊的尾缀
        workingName = workingName.replace(Regex("(?i)(完结|连载中|全彩|扫图版|个人单扫|高清|1080p)"), "")
        workingName = workingName.replace(Regex("[\\-_~]"), " ")   // 变通符抹去
        workingName = workingName.replace(Regex("\\s+"), " ")     // 回车或连环空格归于一个

        // 干瘪纯正的母序列名
        val cleanSeriesName = workingName.trim().ifEmpty { originalFilename.substringBeforeLast('.') }

        return ParsedComicName(
            seriesName = cleanSeriesName,
            region = region,
            format = format,
            issueNumber = issueNumber,
            volumeNumber = volumeNumber
        )
    }
}
