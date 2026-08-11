package xyz.sakulik.comic.model.processor

import android.graphics.Bitmap
import kotlin.math.roundToInt

/**
 * 漫画专用画质增强引擎
 * 提供分块局部锐化、对比度与色彩增强算法
 */
object ImageEnhanceEngine {

    private const val TILE_HEIGHT = 48
    private const val SHARPEN_AMOUNT = 0.85f
    private const val CONTRAST = 1.16f
    private const val SATURATION = 1.08f
    private const val TONE_OFFSET = -2f

    /**
     * 对 Bitmap 进行综合增强：局部锐化、黑白场拉伸、对比度与饱和度提升
     * @param src 原始位图
     * @param reuseBitmap 可选的回调，用于从池中获取目标位图以减少分配
     */
    fun enhance(src: Bitmap, reuseBitmap: (Int, Int, Bitmap.Config) -> Bitmap): Bitmap {
        val width = src.width
        val height = src.height
        val config = if (src.config == Bitmap.Config.RGB_565) {
            Bitmap.Config.RGB_565
        } else {
            Bitmap.Config.ARGB_8888
        }
        
        val requiredBytes = width.toLong() * height.toLong() *
            (if (config == Bitmap.Config.RGB_565) 2L else 4L)
        var dest = reuseBitmap(width, height, config)
        if (
            dest === src ||
            dest.isRecycled ||
            !dest.isMutable ||
            dest.allocationByteCount.toLong() < requiredBytes
        ) {
            if (dest !== src && !dest.isRecycled) dest.recycle()
            dest = Bitmap.createBitmap(width, height, config)
        } else if (dest.width != width || dest.height != height || dest.config != config) {
            dest.reconfigure(width, height, config)
        }

        dest.density = src.density
        dest.setHasAlpha(src.hasAlpha())

        val sourcePixels = IntArray(width * (TILE_HEIGHT + 2))
        val outputPixels = IntArray(width * TILE_HEIGHT)
        var tileTop = 0

        while (tileTop < height) {
            val outputRows = minOf(TILE_HEIGHT, height - tileTop)
            val sourceTop = maxOf(0, tileTop - 1)
            val sourceBottom = minOf(height, tileTop + outputRows + 1)
            val sourceRows = sourceBottom - sourceTop
            src.getPixels(sourcePixels, 0, width, 0, sourceTop, width, sourceRows)

            for (tileRow in 0 until outputRows) {
                val imageY = tileTop + tileRow
                val centerRow = imageY - sourceTop
                val upperRow = maxOf(0, centerRow - 1)
                val lowerRow = minOf(sourceRows - 1, centerRow + 1)
                val outputOffset = tileRow * width

                for (x in 0 until width) {
                    val leftX = maxOf(0, x - 1)
                    val rightX = minOf(width - 1, x + 1)
                    val center = sourcePixels[centerRow * width + x]
                    val upper = sourcePixels[upperRow * width + x]
                    val lower = sourcePixels[lowerRow * width + x]
                    val left = sourcePixels[centerRow * width + leftX]
                    val right = sourcePixels[centerRow * width + rightX]

                    outputPixels[outputOffset + x] = enhancePixel(
                        center = center,
                        upper = upper,
                        lower = lower,
                        left = left,
                        right = right
                    )
                }
            }

            dest.setPixels(outputPixels, 0, width, 0, tileTop, width, outputRows)
            tileTop += outputRows
        }

        return dest
    }

    private fun enhancePixel(
        center: Int,
        upper: Int,
        lower: Int,
        left: Int,
        right: Int
    ): Int {
        val alpha = center ushr 24
        val red = enhanceChannel(center, upper, lower, left, right, 16)
        val green = enhanceChannel(center, upper, lower, left, right, 8)
        val blue = enhanceChannel(center, upper, lower, left, right, 0)
        val luminance = (red * 77 + green * 150 + blue * 29 + 128) ushr 8
        val saturatedRed = saturate(red, luminance)
        val saturatedGreen = saturate(green, luminance)
        val saturatedBlue = saturate(blue, luminance)
        return (alpha shl 24) or
            (saturatedRed shl 16) or
            (saturatedGreen shl 8) or
            saturatedBlue
    }

    private fun enhanceChannel(
        center: Int,
        upper: Int,
        lower: Int,
        left: Int,
        right: Int,
        shift: Int
    ): Int {
        val centerChannel = center ushr shift and 0xFF
        val localAverage = (
            (upper ushr shift and 0xFF) +
                (lower ushr shift and 0xFF) +
                (left ushr shift and 0xFF) +
                (right ushr shift and 0xFF) +
                centerChannel * 4 +
                4
            ) / 8
        val sharpened = centerChannel + (centerChannel - localAverage) * SHARPEN_AMOUNT
        return ((sharpened - 128f) * CONTRAST + 128f + TONE_OFFSET)
            .roundToInt()
            .coerceIn(0, 255)
    }

    private fun saturate(channel: Int, luminance: Int): Int {
        return (luminance + (channel - luminance) * SATURATION)
            .roundToInt()
            .coerceIn(0, 255)
    }
}
