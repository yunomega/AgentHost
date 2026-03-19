package com.example.agenthost.automation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ObservationChangeDetectorTest {
    @Test
    fun localPopupLikeChangeCountsAsMeaningfulVisualChange() {
        val before = frame(width = 24, height = 24) { _, _ -> 240 }
        val after = frame(width = 24, height = 24) { x, y ->
            if (x in 16..23 && y in 0..5) 150 else 240
        }

        assertTrue(ObservationChangeDetector.hasMeaningfulVisualChange(before, after))
    }

    @Test
    fun tinyNoiseDoesNotCountAsMeaningfulVisualChange() {
        val before = frame(width = 24, height = 24) { _, _ -> 240 }
        val after = frame(width = 24, height = 24) { x, y ->
            if ((x == 3 && y == 4) || (x == 8 && y == 10)) 230 else 240
        }

        assertFalse(ObservationChangeDetector.hasMeaningfulVisualChange(before, after))
    }

    private fun frame(
        width: Int,
        height: Int,
        pixel: (x: Int, y: Int) -> Int
    ): ObservationChangeDetector.SampledLumaFrame {
        val pixels = IntArray(width * height)
        var index = 0
        for (y in 0 until height) {
            for (x in 0 until width) {
                pixels[index] = pixel(x, y)
                index += 1
            }
        }
        return ObservationChangeDetector.SampledLumaFrame(
            width = width,
            height = height,
            pixels = pixels
        )
    }
}
