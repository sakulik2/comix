package xyz.sakulik.comic.model.loader

import android.content.Context
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import xyz.sakulik.comic.viewmodel.ReaderMode

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
    private var pageToBlockMap: Map<Int, Int> = emptyMap()

    /**
     * 计算并生成布局
     * 仅在 DUAL_PAGE 模式下需要调用
     */
    suspend fun computeLayout(pageCount: Int, readerMode: ReaderMode) = withContext(Dispatchers.IO) {
        if (readerMode != ReaderMode.DUAL_PAGE) {
            // 非双页模式：1:1 映射
            val blocks = (0 until pageCount).map { RenderBlock.Single(it, false) }
            layoutBlocks = blocks
            pageToBlockMap = (0 until pageCount).associateWith { it }
            return@withContext
        }

        // [核心性能优化] 使用协程并行获取所有页面的尺寸，代替串行 loop 阻塞 I/O
        val dimensionResults = coroutineScope {
            (1 until pageCount).map { index: Int ->
                async { index to getPageDimensions(index) }
            }.awaitAll().toMap()
        }

        val blocks = mutableListOf<RenderBlock>()
        val originalToIndex = mutableMapOf<Int, Int>()
        
        // [用户需求] 强制第 0 页（封面）单页读取并显示
        if (pageCount > 0) {
            originalToIndex[0] = 0
            blocks.add(RenderBlock.Single(0, false))
        }

        var i = 1
        while (i < pageCount) {
            val currentDim = dimensionResults[i] ?: PageSize(1000, 1400)
            val currentIsSpread = currentDim.isLandscape()

            if (currentIsSpread) {
                // 当前是宽图，独立占据一页
                originalToIndex[i] = blocks.size
                blocks.add(RenderBlock.Single(i, true))
                i++
            } else {
                // 当前是窄图，尝试检查下一页
                if (i + 1 < pageCount) {
                    val nextDim = dimensionResults[i + 1] ?: PageSize(1000, 1400)
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
    }

    fun getBlocks(): List<RenderBlock> = layoutBlocks

    fun getBlockCount(): Int = layoutBlocks.size

    /**
     * 根据原始页码获取 Pager 索引
     */
    fun getBlockIndexForPage(pageIndex: Int): Int {
        return pageToBlockMap[pageIndex] ?: 0
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
        fun isLandscape(): Boolean = width > height * 1.2f
    }
}
