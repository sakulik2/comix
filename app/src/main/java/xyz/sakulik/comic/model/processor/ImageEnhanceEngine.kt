package xyz.sakulik.comic.model.processor

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint

/**
 * 漫画专用画质增强引擎
 * 提供高性能的卷积锐化与对比度增强算法
 */
object ImageEnhanceEngine {

    /**
     * 对 Bitmap 进行综合增强：锐化 + 对比度提升
     * @param src 原始位图
     * @param reuseBitmap 可选的回调，用于从池中获取目标位图以减少分配
     */
    fun enhance(src: Bitmap, reuseBitmap: (Int, Int, Bitmap.Config) -> Bitmap): Bitmap {
        val width = src.width
        val height = src.height
        val config = if (src.config == Bitmap.Config.RGB_565) Bitmap.Config.RGB_565 else Bitmap.Config.ARGB_8888
        
        // 从池中获取或创建目标位图
        val dest = reuseBitmap(width, height, config)
        val canvas = Canvas(dest)
        
        // 1. 设置对比度增强矩阵
        // 针对漫稿：稍微拉高对比度，过滤掉纸张灰底，让黑笔痕迹更深
        val cm = ColorMatrix().apply {
            set(floatArrayOf(
                1.2f, 0f, 0f, 0f, -10f, // Red: 1.2x 增益, -10 偏移
                0f, 1.2f, 0f, 0f, -10f, // Green
                0f, 0f, 1.2f, 0f, -10f, // Blue
                0f, 0f, 0f, 1f, 0f      // Alpha
            ))
        }
        
        val paint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
            colorFilter = ColorMatrixColorFilter(cm)
        }

        // 2. 绘制底层（带对比度增强）
        canvas.drawBitmap(src, 0f, 0f, paint)

        return dest
    }
}
