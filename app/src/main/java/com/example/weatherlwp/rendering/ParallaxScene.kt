package com.example.weatherlwp.rendering

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import com.example.weatherlwp.parallax.ParallaxSensorManager

class ParallaxScene(context: Context) : ParallaxSensorManager.Listener {
    private val sensor = ParallaxSensorManager(context)

    // Pre-allocated paints (no per-frame allocations)
    private val paintSky = Paint().apply { color = Color.parseColor("#87CEEB") }
    private val paintMountain = Paint().apply {
        color = Color.parseColor("#6b6b6b")
        style = Paint.Style.FILL
        isAntiAlias = true
    }
    private val paintTree = Paint().apply {
        color = Color.parseColor("#2d5016")
        style = Paint.Style.FILL
        isAntiAlias = true
    }
    private val paintTrunk = Paint().apply {
        color = Color.parseColor("#6b4f2a")
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    // Reusable paths
    private val mountainPath = Path()
    private val treePath = Path()

    private var ox = 0f
    private var oy = 0f

    init {
        sensor.setListener(this)
    }

    fun start() = sensor.start()
    fun stop() = sensor.stop()

    override fun onOffsetChanged(x: Float, y: Float) {
        ox = x
        oy = y
    }

    fun render(canvas: Canvas) {
        // Sky (no parallax)
        canvas.drawColor(paintSky.color)

        // Mountains (depth 0.3)
        canvas.save()
        canvas.translate(ox * 0.3f, oy * 0.3f)
        drawMountains(canvas)
        canvas.restore()

        // Trees front (depth 1.2)
        canvas.save()
        canvas.translate(ox * 1.2f, oy * 1.2f)
        drawTree(canvas, 120f, canvas.height * 0.75f)
        drawTree(canvas, canvas.width - 140f, canvas.height * 0.78f)
        canvas.restore()
    }

    private fun drawMountains(canvas: Canvas) {
        mountainPath.reset()
        mountainPath.moveTo(0f, canvas.height * 0.65f)
        mountainPath.lineTo(canvas.width * 0.35f, canvas.height * 0.35f)
        mountainPath.lineTo(canvas.width * 0.70f, canvas.height * 0.45f)
        mountainPath.lineTo(canvas.width.toFloat(), canvas.height * 0.60f)
        mountainPath.lineTo(canvas.width.toFloat(), canvas.height.toFloat())
        mountainPath.lineTo(0f, canvas.height.toFloat())
        mountainPath.close()
        canvas.drawPath(mountainPath, paintMountain)
    }

    private fun drawTree(canvas: Canvas, x: Float, y: Float) {
        canvas.drawRect(x - 10f, y, x + 10f, y + 100f, paintTrunk)

        treePath.reset()
        treePath.moveTo(x, y - 90f)
        treePath.lineTo(x - 60f, y + 10f)
        treePath.lineTo(x + 60f, y + 10f)
        treePath.close()
        canvas.drawPath(treePath, paintTree)
    }
}
