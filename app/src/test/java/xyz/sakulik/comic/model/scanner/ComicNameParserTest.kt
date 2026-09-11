package xyz.sakulik.comic.model.scanner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import xyz.sakulik.comic.model.db.ComicFormat
import xyz.sakulik.comic.model.db.ComicRegion

/**
 * ComicNameParser 依赖中文正则识别文件名，i18n 时容易被误当成 UI 文案改掉。
 * 这里钉住几个真实文件名的解析结果，正则被翻译或删掉就会红。
 */
class ComicNameParserTest {

    @Test
    fun `第 N 话 识别为日漫单话`() {
        val parsed = ComicNameParser.parse("海贼王 第 1015 话.cbz")
        assertEquals("海贼王", parsed.seriesName)
        assertEquals(ComicRegion.MANGA, parsed.region)
        assertEquals(ComicFormat.CHAPTER, parsed.format)
        assertEquals(1015f, parsed.issueNumber)
    }

    @Test
    fun `第 N 卷 识别为日漫单行本`() {
        val parsed = ComicNameParser.parse("[汉化组] 进击的巨人 第 5 卷.cbz")
        assertEquals("进击的巨人", parsed.seriesName)
        assertEquals(ComicRegion.MANGA, parsed.region)
        assertEquals(ComicFormat.TANKOBON, parsed.format)
        assertEquals(5f, parsed.volumeNumber)
    }

    @Test
    fun `汉化组特征在无期号时兜底判定日漫`() {
        val parsed = ComicNameParser.parse("某部漫画 [个人扫图].cbz")
        assertEquals(ComicRegion.MANGA, parsed.region)
        assertNull(parsed.issueNumber)
    }

    @Test
    fun `完结等状态词不进系列名`() {
        val parsed = ComicNameParser.parse("拳愿阿修罗 第 12 卷 完结.cbz")
        assertEquals("拳愿阿修罗", parsed.seriesName)
        assertEquals(12f, parsed.volumeNumber)
    }

    @Test
    fun `纯英文文件名走美漫分支`() {
        val parsed = ComicNameParser.parse("Batman #12 (2011).cbz")
        assertEquals("Batman", parsed.seriesName)
        assertEquals(ComicRegion.COMIC, parsed.region)
        assertEquals(ComicFormat.ISSUE, parsed.format)
        assertEquals(12f, parsed.issueNumber)
        assertEquals("2011", parsed.year)
    }

    @Test
    fun `Vol 与 TPB 同时出现时按合订本处理`() {
        val parsed = ComicNameParser.parse("Saga Vol. 3 TPB.cbz")
        assertEquals(ComicRegion.COMIC, parsed.region)
        assertEquals(ComicFormat.TPB, parsed.format)
        assertEquals(3f, parsed.volumeNumber)
    }
}
