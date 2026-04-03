package xyz.sakulik.comic.model.loader

/**
 * 混合驱动核心接口：抽象化漫画页面的获取行为
 * 支持本地流式内存解压与云端流媒体 API 加载
 */
interface ComicPageLoader {
    /**
     * 获取当前漫画的总页数
     */
    suspend fun getPageCount(): Int

    /**
     * 获取指定页码的数据
     * @param pageIndex 零基索引页码
     * @param width 目标显示的宽度（用于本地解码下采样）
     * @param height 目标显示的高度（用于本地解码下采样）
     * @return 返回类型为 Any：
     *         - LOCAL: 返回 [android.graphics.Bitmap] 或 [java.nio.ByteBuffer]
     *         - REMOTE: 返回 [String] (即图片的 HTTP 完整 URL)
     */
    suspend fun getPageData(pageIndex: Int, width: Int, height: Int): Any?

    /**
     * 生命周期释放钩子：当页面被滑出屏幕或回收时由 UI 调用
     * 允许加载引擎将显存资源归还池中进行复用
     */
    fun releasePageData(data: Any?)

    /**
     * 生命周期释放钩子：在阅读结束退出页面时调用
     */
    fun close()
}
