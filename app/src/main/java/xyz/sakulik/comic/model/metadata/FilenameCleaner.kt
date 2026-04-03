package xyz.sakulik.comic.model.metadata

/**
 * 用于清理漫画文件名的实用工具类将文件名转换为更适合 API 查询的关键词
 */
object FilenameCleaner {
    
    /**
     * 清理常见本地压缩包文件名的冗余信息
     * 例子：" [汉化组] 某科学的超电磁炮 Vol.01 (完) [1080p].cbz " 
     *      -> "某科学的超电磁炮"
     */
    fun clean(originalFilename: String): String {
        // 先去掉各类漫画常见后缀扩展名
        var cleaned = originalFilename.replace(Regex("\\.(cbz|cbr|pdf|zip|rar)$", RegexOption.IGNORE_CASE), "")
        
        // 步骤一：移除方括号、圆括号及其内部所有内容通常是 [汉化组] 或者 (分辨率)
        cleaned = cleaned.replace(Regex("\\[.*?]"), "")
        cleaned = cleaned.replace(Regex("\\(.*?\\)"), "")
        cleaned = cleaned.replace(Regex("【.*?】"), "") // 中文特殊括号
        cleaned = cleaned.replace(Regex("（.*?）"), "") // 中文特殊圆括号
        
        //\ 步骤二：移除 "Vol.01", "v1", "Chapter 2", "ch12" 以及类似字眼及其后的数字
        cleaned = cleaned.replace(Regex("(?i)(vol\\.|v|chapter|ch\\.)\\s*\\d+", RegexOption.IGNORE_CASE), "")
        
        // 步骤三：移除常见冗余中文特征字词如 "完结", "短篇", "单行本"
        val extraneousWords = listOf("完结", "全集", "短篇", "单行本", "扫图")
        extraneousWords.forEach { word ->
            cleaned = cleaned.replace(word, "")
        }
        
        // 最后阶段：合并多余空白，清除两端多余空格与横杠
        cleaned = cleaned.replace(Regex("[\\-_]"), " ")   // 将下划线或减号变为空格
        cleaned = cleaned.replace(Regex("\\s+"), " ")     // 合并相连的空白字符
        
        return cleaned.trim()
    }
}
