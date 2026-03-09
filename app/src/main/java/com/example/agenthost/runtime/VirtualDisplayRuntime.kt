package com.example.agenthost.runtime

import android.content.Context
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.util.Log
import android.view.Surface

class VirtualDisplayRuntime(
    private val context: Context
) {
    companion object {
        private const val TAG = "VirtualDisplayRuntime"
        private const val DEFAULT_WIDTH = 1080
        private const val DEFAULT_HEIGHT = 1920
        private const val DEFAULT_MAX_IMAGES = 2
    }

    data class RuntimeHandle(
        val vdDisplayId: Int,
        val virtualDisplay: VirtualDisplay,
        val imageReader: ImageReader,
        val surface: Surface
    ) {
        fun release() {
            imageReader.close()
            virtualDisplay.release()
            Log.i(
                "VirtualDisplayRuntime",
                "release virtualDisplayId=$vdDisplayId"
            )
        }
    }

    fun create(): RuntimeHandle? {
        val dm = context.getSystemService(DisplayManager::class.java)
        val densityDpi = context.resources.displayMetrics.densityDpi
        val imageReader = ImageReader.newInstance(
            DEFAULT_WIDTH,
            DEFAULT_HEIGHT,
            PixelFormat.RGBA_8888,
            DEFAULT_MAX_IMAGES
        )
        val surface = imageReader.surface
        val flags = DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY or
            DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION
        val vd = dm.createVirtualDisplay(
            "session-vd-${System.currentTimeMillis()}",
            DEFAULT_WIDTH,
            DEFAULT_HEIGHT,
            densityDpi,
            surface,
            flags
        )

        if (vd == null || vd.display == null) {
            imageReader.close()
            Log.e(TAG, "create failed vd=null")
            return null
        }

        val handle = RuntimeHandle(
            vdDisplayId = vd.display.displayId,
            virtualDisplay = vd,
            imageReader = imageReader,
            surface = surface
        )
        Log.i(
            TAG,
            "create success virtualDisplayId=${handle.vdDisplayId} densityDpi=$densityDpi"
        )
        return handle
    }
}
