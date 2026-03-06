package com.example.agenthost.automation

import android.graphics.Bitmap
import android.media.Image
import android.util.Base64
import java.io.ByteArrayOutputStream

class VdObservationProvider : ObservationProvider {
    companion object {
        private const val POLL_COUNT = 6
        private const val POLL_SLEEP_MS = 40L
    }

    override fun capture(request: ObservationRequest): ObservationResult {
        val reader = request.imageReader ?: return ObservationResult.Failed(
            code = "OBSERVATION_READER_MISSING",
            message = "ImageReader is not available"
        )

        var image: Image? = null
        for (index in 0 until POLL_COUNT) {
            image = try {
                reader.acquireLatestImage()
            } catch (_: Throwable) {
                null
            }
            if (image != null) {
                break
            }
            if (index < POLL_COUNT - 1) {
                try {
                    Thread.sleep(POLL_SLEEP_MS)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return ObservationResult.Failed(
                        code = "OBSERVATION_INTERRUPTED",
                        message = "Capture interrupted"
                    )
                }
            }
        }

        val captured = image ?: return ObservationResult.Failed(
            code = "OBSERVATION_EMPTY_FRAME",
            message = "No frame available"
        )

        return try {
            val encoded = imageToBase64Png(captured)
            ObservationResult.Captured(
                base64Png = encoded,
                width = captured.width,
                height = captured.height
            )
        } catch (t: Throwable) {
            ObservationResult.Failed(
                code = "OBSERVATION_ENCODE_FAILED",
                message = t.message ?: "Encode failed"
            )
        } finally {
            captured.close()
        }
    }

    private fun imageToBase64Png(image: Image): String {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val width = image.width
        val height = image.height
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * width
        val paddedWidth = width + rowPadding / pixelStride

        val paddedBitmap = Bitmap.createBitmap(paddedWidth, height, Bitmap.Config.ARGB_8888)
        paddedBitmap.copyPixelsFromBuffer(buffer)
        val croppedBitmap = Bitmap.createBitmap(paddedBitmap, 0, 0, width, height)

        return try {
            val output = ByteArrayOutputStream()
            croppedBitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
            Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP)
        } finally {
            paddedBitmap.recycle()
            croppedBitmap.recycle()
        }
    }
}
