package com.example.agenthost.automation

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import java.security.MessageDigest
import java.util.Base64

object ObservationChangeDetector {
    private const val TARGET_MAX_SAMPLE_DIMENSION = 24
    private const val TARGET_LOCAL_ROI_MAX_SAMPLE_DIMENSION = 96
    private const val MAX_BLACK_LUMA = 12
    private const val MAX_SETTLE_MEAN_LUMA_DIFF = 6.0
    private const val LOCAL_DIFF_GRID_DIMENSION = 4
    private const val SIGNIFICANT_PIXEL_DIFF_THRESHOLD = 18
    private const val MAX_LOCAL_TILE_MEAN_LUMA_DIFF = 12.0
    private const val MAX_SIGNIFICANT_DIFF_RATIO = 0.035
    private const val LOCAL_ROI_SIGNIFICANT_PIXEL_DIFF_THRESHOLD = 10
    private const val MIN_LOCAL_ROI_MEAN_LUMA_DIFF = 3.0
    private const val MIN_LOCAL_ROI_SIGNIFICANT_RATIO = 0.035

    internal data class SampledLumaFrame(
        val width: Int,
        val height: Int,
        val pixels: IntArray
    )

    data class LocalRoiChange(
        val confirmed: Boolean,
        val meanDiff: Double = 0.0,
        val significantPixelRatio: Double = 0.0,
        val changedPixelCount: Int = 0,
        val roiPixelCount: Int = 0,
        val sampleRadiusPx: Int = 0
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

    fun detectLocalRoiChangeNearPoint(
        before: ObservationResult.Captured,
        after: ObservationResult.Captured,
        point: CanonicalPoint,
        radiusPx: Int = defaultLocalRoiRadiusPx(before.width, before.height)
    ): LocalRoiChange {
        if (before.width != after.width || before.height != after.height) {
            return LocalRoiChange(confirmed = false)
        }
        val beforeBitmap = tryDecodeBitmap(before, targetMaxDimension = TARGET_LOCAL_ROI_MAX_SAMPLE_DIMENSION)
        val afterBitmap = tryDecodeBitmap(after, targetMaxDimension = TARGET_LOCAL_ROI_MAX_SAMPLE_DIMENSION)
        if (beforeBitmap == null || afterBitmap == null) {
            beforeBitmap?.recycle()
            afterBitmap?.recycle()
            return LocalRoiChange(confirmed = false)
        }
        return try {
            val beforeFrame = sampleLumaFrame(beforeBitmap)
            val afterFrame = sampleLumaFrame(afterBitmap)
            if (beforeFrame == null || afterFrame == null) {
                return LocalRoiChange(confirmed = false)
            }
            detectLocalRoiChangeOnFrames(
                before = beforeFrame,
                after = afterFrame,
                point = point,
                sourceWidth = before.width,
                sourceHeight = before.height,
                radiusPx = radiusPx
            )
        } finally {
            beforeBitmap.recycle()
            afterBitmap.recycle()
        }
    }

    private fun tryDecodeBitmap(
        observation: ObservationResult.Captured,
        targetMaxDimension: Int = TARGET_MAX_SAMPLE_DIMENSION
    ): Bitmap? {
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
                    inSampleSize = calculateSampleSize(
                        width = observation.width,
                        height = observation.height,
                        targetMaxDimension = targetMaxDimension
                    )
                }
            )
        } catch (_: Throwable) {
            null
        }
    }

    private fun calculateSampleSize(width: Int, height: Int, targetMaxDimension: Int): Int {
        val maxDimension = maxOf(width, height)
        if (maxDimension <= targetMaxDimension) {
            return 1
        }
        var sampleSize = 1
        while (maxDimension / sampleSize > targetMaxDimension) {
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

    internal fun detectLocalRoiChangeOnFrames(
        before: SampledLumaFrame,
        after: SampledLumaFrame,
        point: CanonicalPoint,
        sourceWidth: Int,
        sourceHeight: Int,
        radiusPx: Int
    ): LocalRoiChange {
        if (before.width != after.width || before.height != after.height) {
            return LocalRoiChange(confirmed = false)
        }
        if (before.width <= 0 || before.height <= 0 || sourceWidth <= 0 || sourceHeight <= 0) {
            return LocalRoiChange(confirmed = false)
        }
        val centerX = ((point.x.toDouble() / sourceWidth.toDouble()) * before.width.toDouble())
            .toInt()
            .coerceIn(0, before.width - 1)
        val centerY = ((point.y.toDouble() / sourceHeight.toDouble()) * before.height.toDouble())
            .toInt()
            .coerceIn(0, before.height - 1)
        val scaledRadiusX = maxOf(
            1,
            ((radiusPx.toDouble() / sourceWidth.toDouble()) * before.width.toDouble()).toInt()
        )
        val scaledRadiusY = maxOf(
            1,
            ((radiusPx.toDouble() / sourceHeight.toDouble()) * before.height.toDouble()).toInt()
        )
        val left = (centerX - scaledRadiusX).coerceAtLeast(0)
        val right = (centerX + scaledRadiusX).coerceAtMost(before.width - 1)
        val top = (centerY - scaledRadiusY).coerceAtLeast(0)
        val bottom = (centerY + scaledRadiusY).coerceAtMost(before.height - 1)

        var totalDiff = 0L
        var changedPixelCount = 0
        var roiPixelCount = 0
        for (y in top..bottom) {
            val rowBase = y * before.width
            for (x in left..right) {
                val index = rowBase + x
                val diff = kotlin.math.abs(before.pixels[index] - after.pixels[index])
                totalDiff += diff
                if (diff >= LOCAL_ROI_SIGNIFICANT_PIXEL_DIFF_THRESHOLD) {
                    changedPixelCount += 1
                }
                roiPixelCount += 1
            }
        }
        if (roiPixelCount <= 0) {
            return LocalRoiChange(confirmed = false)
        }
        val meanDiff = totalDiff.toDouble() / roiPixelCount.toDouble()
        val significantPixelRatio = changedPixelCount.toDouble() / roiPixelCount.toDouble()
        val minChangedPixels = maxOf(4, roiPixelCount / 40)
        val confirmed = (meanDiff >= MIN_LOCAL_ROI_MEAN_LUMA_DIFF &&
            significantPixelRatio >= MIN_LOCAL_ROI_SIGNIFICANT_RATIO) ||
            changedPixelCount >= minChangedPixels
        return LocalRoiChange(
            confirmed = confirmed,
            meanDiff = meanDiff,
            significantPixelRatio = significantPixelRatio,
            changedPixelCount = changedPixelCount,
            roiPixelCount = roiPixelCount,
            sampleRadiusPx = maxOf(scaledRadiusX, scaledRadiusY)
        )
    }

    private fun defaultLocalRoiRadiusPx(width: Int, height: Int): Int {
        val shortEdge = minOf(width, height).coerceAtLeast(1)
        return maxOf(48, (shortEdge * 0.06f).toInt())
    }
}
