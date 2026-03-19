package com.example.agenthost.automation

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import java.security.MessageDigest
import java.util.Base64

object ObservationChangeDetector {
    private const val TARGET_MAX_SAMPLE_DIMENSION = 24
    private const val MAX_BLACK_LUMA = 12
    private const val MAX_SETTLE_MEAN_LUMA_DIFF = 6.0
    private const val LOCAL_DIFF_GRID_DIMENSION = 4
    private const val SIGNIFICANT_PIXEL_DIFF_THRESHOLD = 18
    private const val MAX_LOCAL_TILE_MEAN_LUMA_DIFF = 12.0
    private const val MAX_SIGNIFICANT_DIFF_RATIO = 0.035

    internal data class SampledLumaFrame(
        val width: Int,
        val height: Int,
        val pixels: IntArray
    )

    fun isLikelyNearBlackSingleColorFrame(observation: ObservationResult.Captured): Boolean {
        val bitmap = tryDecodeBitmap(observation) ?: return false
        return try {
            if (bitmap.width <= 0 || bitmap.height <= 0) {
                return false
            }
            val firstColor = bitmap.getPixel(0, 0)
            if (luma(firstColor) > MAX_BLACK_LUMA) {
                return false
            }
            for (y in 0 until bitmap.height) {
                for (x in 0 until bitmap.width) {
                    val color = bitmap.getPixel(x, y)
                    if (color != firstColor || luma(color) > MAX_BLACK_LUMA) {
                        return false
                    }
                }
            }
            true
        } finally {
            bitmap.recycle()
        }
    }

    fun areNearlyUnchanged(
        before: ObservationResult.Captured,
        after: ObservationResult.Captured
    ): Boolean {
        if (before.width != after.width || before.height != after.height) {
            return false
        }
        val beforeBitmap = tryDecodeBitmap(before)
        val afterBitmap = tryDecodeBitmap(after)
        if (beforeBitmap == null || afterBitmap == null) {
            beforeBitmap?.recycle()
            afterBitmap?.recycle()
            return before.base64Png == after.base64Png
        }
        return try {
            val beforeFrame = sampleLumaFrame(beforeBitmap)
            val afterFrame = sampleLumaFrame(afterBitmap)
            if (beforeFrame == null || afterFrame == null) {
                return false
            }
            !hasMeaningfulVisualChange(beforeFrame, afterFrame)
        } finally {
            beforeBitmap.recycle()
            afterBitmap.recycle()
        }
    }

    fun fingerprint(observation: ObservationResult): ObservationFingerprint? {
        val captured = observation as? ObservationResult.Captured ?: return null
        val digest = runCatching {
            val bytes = MessageDigest.getInstance("SHA-256")
                .digest(captured.base64Png.toByteArray(Charsets.UTF_8))
            bytes.joinToString(separator = "") { "%02x".format(it) }.take(24)
        }.getOrElse {
            captured.base64Png.hashCode().toString()
        }
        return ObservationFingerprint(
            width = captured.width,
            height = captured.height,
            hash = digest
        )
    }

    private fun tryDecodeBitmap(observation: ObservationResult.Captured): Bitmap? {
        return try {
            val pngBytes = Base64.getDecoder().decode(observation.base64Png)
            if (pngBytes.isEmpty()) {
                return null
            }
            BitmapFactory.decodeByteArray(
                pngBytes,
                0,
                pngBytes.size,
                BitmapFactory.Options().apply {
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                    inSampleSize = calculateSampleSize(observation.width, observation.height)
                }
            )
        } catch (_: Throwable) {
            null
        }
    }

    private fun calculateSampleSize(width: Int, height: Int): Int {
        val maxDimension = maxOf(width, height)
        if (maxDimension <= TARGET_MAX_SAMPLE_DIMENSION) {
            return 1
        }
        var sampleSize = 1
        while (maxDimension / sampleSize > TARGET_MAX_SAMPLE_DIMENSION) {
            sampleSize *= 2
        }
        return sampleSize
    }

    private fun luma(color: Int): Int {
        return (Color.red(color) * 299 + Color.green(color) * 587 + Color.blue(color) * 114) / 1000
    }

    private fun sampleLumaFrame(bitmap: Bitmap): SampledLumaFrame? {
        if (bitmap.width <= 0 || bitmap.height <= 0) {
            return null
        }
        val pixels = IntArray(bitmap.width * bitmap.height)
        var index = 0
        for (y in 0 until bitmap.height) {
            for (x in 0 until bitmap.width) {
                pixels[index] = luma(bitmap.getPixel(x, y))
                index += 1
            }
        }
        return SampledLumaFrame(
            width = bitmap.width,
            height = bitmap.height,
            pixels = pixels
        )
    }

    internal fun hasMeaningfulVisualChange(
        before: SampledLumaFrame,
        after: SampledLumaFrame
    ): Boolean {
        if (before.width != after.width || before.height != after.height) {
            return true
        }
        val pixelCount = before.pixels.size
        if (pixelCount <= 0 || pixelCount != after.pixels.size) {
            return true
        }
        val tileRows = minOf(LOCAL_DIFF_GRID_DIMENSION, before.height)
        val tileCols = minOf(LOCAL_DIFF_GRID_DIMENSION, before.width)
        val tileCount = tileRows * tileCols
        val tileDiffSums = LongArray(tileCount)
        val tilePixelCounts = IntArray(tileCount)
        var totalDiff = 0L
        var significantPixelCount = 0
        for (y in 0 until before.height) {
            val rowBase = y * before.width
            val tileRow = (y * tileRows) / before.height
            for (x in 0 until before.width) {
                val index = rowBase + x
                val diff = kotlin.math.abs(before.pixels[index] - after.pixels[index])
                totalDiff += diff
                if (diff >= SIGNIFICANT_PIXEL_DIFF_THRESHOLD) {
                    significantPixelCount += 1
                }
                val tileCol = (x * tileCols) / before.width
                val tileIndex = tileRow * tileCols + tileCol
                tileDiffSums[tileIndex] += diff
                tilePixelCounts[tileIndex] += 1
            }
        }
        val meanDiff = totalDiff.toDouble() / pixelCount.toDouble()
        var maxTileMeanDiff = 0.0
        for (index in 0 until tileCount) {
            val tilePixels = tilePixelCounts[index]
            if (tilePixels <= 0) {
                continue
            }
            val tileMeanDiff = tileDiffSums[index].toDouble() / tilePixels.toDouble()
            if (tileMeanDiff > maxTileMeanDiff) {
                maxTileMeanDiff = tileMeanDiff
            }
        }
        val significantPixelRatio = significantPixelCount.toDouble() / pixelCount.toDouble()
        return meanDiff > MAX_SETTLE_MEAN_LUMA_DIFF ||
            maxTileMeanDiff > MAX_LOCAL_TILE_MEAN_LUMA_DIFF ||
            significantPixelRatio > MAX_SIGNIFICANT_DIFF_RATIO
    }
}
