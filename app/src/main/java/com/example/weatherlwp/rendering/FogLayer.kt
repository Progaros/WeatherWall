package com.example.weatherlwp.rendering

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Color

class FogLayer {
    private val paint = Paint().apply {
        color = Color.WHITE
        alpha = 25
    }

    fun render(canvas: Canvas, enabled: Boolean) {
        if (!enabled) return
        // Simple overlay fog (placeholder): replace with noise/gradient later
        canvas.drawRect(0f, 0f, canvas.width.toFloat(), canvas.height.toFloat(), paint)
    }
}
