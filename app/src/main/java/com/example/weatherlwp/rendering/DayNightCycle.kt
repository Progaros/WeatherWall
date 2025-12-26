package com.example.weatherlwp.rendering

import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import java.util.Calendar

/**
 * Handles sky color based on time of day.
 * Uses smooth gradients for dawn, day, dusk, and night.
 */
class DayNightCycle {

    enum class TimeOfDay {
        NIGHT,      // 00:00 - 05:00
        DAWN,       // 05:00 - 07:00
        DAY,        // 07:00 - 18:00
        DUSK,       // 18:00 - 21:00
        NIGHT_LATE  // 21:00 - 24:00
    }

    // Pre-allocated paint (no per-frame allocations)
    private val skyPaint = Paint()
    private var lastWidth = 0
    private var lastHeight = 0
    private var lastTimeOfDay: TimeOfDay? = null

    // Color constants (as int arrays to avoid allocations)
    private val nightColors = intArrayOf(0xFF0a0a1a.toInt(), 0xFF1a1a3a.toInt())
    private val dawnColors = intArrayOf(0xFF2d1b4e.toInt(), 0xFFff6b6b.toInt(), 0xFFffcc00.toInt())
    private val dayColors = intArrayOf(0xFF87CEEB.toInt(), 0xFFb0e0e6.toInt())
    private val duskColors = intArrayOf(0xFF4a3060.toInt(), 0xFFff7f50.toInt(), 0xFFffd700.toInt())

    fun getTimeOfDay(): TimeOfDay {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        return when (hour) {
            in 0..4 -> TimeOfDay.NIGHT
            in 5..6 -> TimeOfDay.DAWN
            in 7..17 -> TimeOfDay.DAY
            in 18..20 -> TimeOfDay.DUSK
            else -> TimeOfDay.NIGHT_LATE
        }
    }

    fun render(canvas: Canvas, enabled: Boolean) {
        val width = canvas.width
        val height = canvas.height
        val timeOfDay = getTimeOfDay()

        // Only recreate gradient if dimensions or time changed
        if (width != lastWidth || height != lastHeight || timeOfDay != lastTimeOfDay) {
            lastWidth = width
            lastHeight = height
            lastTimeOfDay = timeOfDay

            val colors = when (timeOfDay) {
                TimeOfDay.NIGHT, TimeOfDay.NIGHT_LATE -> nightColors
                TimeOfDay.DAWN -> dawnColors
                TimeOfDay.DAY -> dayColors
                TimeOfDay.DUSK -> duskColors
            }

            val positions = if (colors.size == 3) floatArrayOf(0f, 0.5f, 1f) else null

            skyPaint.shader = LinearGradient(
                0f, 0f, 0f, height.toFloat(),
                colors, positions,
                Shader.TileMode.CLAMP
            )
        }

        if (enabled) {
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), skyPaint)
        }
    }

    /**
     * Get a brightness factor for adjusting other layer colors (0.3 = night, 1.0 = day)
     */
    fun getBrightness(): Float {
        return when (getTimeOfDay()) {
            TimeOfDay.NIGHT, TimeOfDay.NIGHT_LATE -> 0.3f
            TimeOfDay.DAWN -> 0.6f
            TimeOfDay.DAY -> 1.0f
            TimeOfDay.DUSK -> 0.5f
        }
    }
}
