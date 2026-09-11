package xyz.sakulik.comic.model.loader

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import xyz.sakulik.comic.viewmodel.ReaderMode
import xyz.sakulik.comic.R
import xyz.sakulik.comic.utils.UiText

/**
 * 渲染区块定义
 */
sealed class RenderBlock {
    /**
     * 单页显示
     * @param pageIndex 原始页码
     * @param isSpread 是否为宽图（跨页）
     */
    data class Single(val pageIndex: Int, val isSpread: Boolean) : RenderBlock()

    /**
     * 双页并排显示
     */
    data class Pair(val leftIndex: Int, val rightIndex: Int) : RenderBlock()
}

/**
 * 阅读器布局管理器
 * 负责解析页面尺寸、生成渲染区块列表，并处理页码映射
 */
class ReaderLayoutManager(
    private val loader: ComicPageLoader
) {
    private var layoutBlocks: List<RenderBlock> = emptyList()
    private var pageToBlockMap: IntArray? = null
    private var usesIdentityMapping = false

    /**
     * 计算并生成布局
     * 仅在 DUAL_PAGE 模式下需要调用
     */
    suspend fun computeLayout(pageCount: Int, readerMode: ReaderMode) = withContext(Dispatchers.IO) {
        if (pageCount !in 0..RemoteResourceLimits.MAX_TOTAL_PAGES) {
            throw RemoteResourceLimitException(
                UiText.Res(
                    R.string.error_remote_total_pages,
                    listOf(pageCount, RemoteResourceLimits.MAX_TOTAL_PAGES)
                ),
                "page count out of range: $pageCount"
            )
        }

        if (readerMode != ReaderMode.DUAL_PAGE) {
            layoutBlocks = object : AbstractList<RenderBlock>() {
                override val size: Int = pageCount

                override fun get(index: Int): RenderBlock {
                    if (index !in 0 until size) throw IndexOutOfBoundsException("Page index: $index")
                    return RenderBlock.Single(index, false)
                }
            }
            pageToBlockMap = null
            usesIdentityMapping = true
            return@withContext
        }

        val blocks = ArrayList<RenderBlock>((pageCount + 1) / 2)
        val originalToIndex = IntArray(pageCount)
        
        // [用户需求] 强制第 0 页（封面）单页读取并显示
        if (pageCount > 0) {
            originalToIndex[0] = 0
            blocks.add(RenderBlock.Single(0, false))
        }

        var i = 1
        while (i < pageCount) {
            val currentDim = getPageDimensions(i)
            val currentIsSpread = currentDim.isLandscape()

            if (currentIsSpread) {
                // 当前是宽图，独立占据一页
                originalToIndex[i] = blocks.size
                blocks.add(RenderBlock.Single(i, true))
                i++
            } else {
                // 当前是窄图，尝试检查下一页
                if (i + 1 < pageCount) {
                    val nextDim = getPageDimensions(i + 1)
                    if (nextDim.isLandscape()) {
                        // 下一页是宽图，当前页只能单走
                        originalToIndex[i] = blocks.size
                        blocks.add(RenderBlock.Single(i, false))
                        i++
                    } else {
                        // 两页都是窄图，合并显示
                        originalToIndex[i] = blocks.size
                        originalToIndex[i + 1] = blocks.size
                        blocks.add(RenderBlock.Pair(i, i + 1))
                        i += 2
                    }
                } else {
                    // 最后一页（如果封面后奇数页）单走
                    originalToIndex[i] = blocks.size
                    blocks.add(RenderBlock.Single(i, false))
                    i++
                }
            }
        }
        
        layoutBlocks = blocks
        pageToBlockMap = originalToIndex
        usesIdentityMapping = false
    }

    fun getBlocks(): List<RenderBlock> = layoutBlocks

    fun getBlockCount(): Int = layoutBlocks.size

    /**
     * 根据原始页码获取 Pager 索引
     */
    fun getBlockIndexForPage(pageIndex: Int): Int {
        if (usesIdentityMapping) {
            return pageIndex.takeIf { it in layoutBlocks.indices } ?: 0
        }
        return pageToBlockMap?.getOrNull(pageIndex) ?: 0
    }

    /**
     * 根据 Pager 索引获取（起始）原始页码
     */
    fun getFirstPageForBlock(blockIndex: Int): Int {
        if (blockIndex !in layoutBlocks.indices) return 0
        return when (val block = layoutBlocks[blockIndex]) {
            is RenderBlock.Single -> block.pageIndex
            is RenderBlock.Pair -> block.leftIndex
        }
    }

    private suspend fun getPageDimensions(index: Int): PageSize {
        val size = loader.getPageSize(index)
        return if (size != null) {
            PageSize(size.first, size.second)
        } else {
            PageSize(1000, 1400) // 兜底
        }
    }

    private data class PageSize(val width: Int, val height: Int) {
        fun isLandscape(): Boolean = width > height * 1.1f
    }
}
