package com.example.weatherlwp.rendering

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import androidx.preference.PreferenceManager
import com.example.weatherlwp.parallax.ParallaxSensorManager
import java.util.Calendar
import kotlin.random.Random

/**
 * Low-poly parallax scene with layered mountains and trees.
 * Supports day/night/rain themes with dynamic sky gradients.
 */
class ParallaxScene(private val context: Context) : ParallaxSensorManager.Listener {
    private val sensor = ParallaxSensorManager(context)
    private val prefs: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)

    // Theme-based colors (set in updateTheme)
    private var skyTopColor = 0
    private var skyBottomColor = 0
    private var mountainFarColor = 0
    private var mountainMidColor = 0
    private var mountainNearColor = 0
    private var treeFarColor = 0
    private var treeMidColor = 0
    private var treeNearColor = 0

    // Pre-allocated paints
    private val skyPaint = Paint()
    private val mountainFarPaint = Paint().apply { style = Paint.Style.FILL; isAntiAlias = true }
    private val mountainMidPaint = Paint().apply { style = Paint.Style.FILL; isAntiAlias = true }
    private val mountainNearPaint = Paint().apply { style = Paint.Style.FILL; isAntiAlias = true }
    private val treePaint = Paint().apply { style = Paint.Style.FILL; isAntiAlias = true }
    private val starPaint = Paint().apply { color = Color.WHITE; isAntiAlias = true }

    // Reusable paths
    private val mountainPath = Path()
    private val treePath = Path()

    private var ox = 0f
    private var oy = 0f
    private var parallaxStrength = 1.0f
    private var treeCount = 12
    private var treeScale = 1.0f

    private var lastWidth = 0
    private var lastHeight = 0
    private var lastTheme = ""

    // Star positions (for night sky)
    private val stars = Array(50) { floatArrayOf(Random.nextFloat(), Random.nextFloat(), Random.nextFloat() * 2f + 1f) }

    // Tree data (x ratio, y ratio, scale, shade 0-2, depth)
    private data class TreeData(val xRatio: Float, val yRatio: Float, val scale: Float, val shade: Int, val depth: Float)
    private val trees = mutableListOf<TreeData>()

    init {
        sensor.setListener(this)
        loadSettings()
        generateTrees()
    }

    fun loadSettings() {
        parallaxStrength = prefs.getInt("pref_parallax_strength", 50) / 50f
        treeCount = prefs.getInt("pref_tree_count", 12)
        treeScale = prefs.getInt("pref_tree_size", 100) / 100f

        if (trees.size != treeCount) {
            generateTrees()
        }
    }

    private fun generateTrees() {
        trees.clear()
        val random = Random(42)

        // Far trees (small, back)
        repeat(treeCount / 3) {
            trees.add(TreeData(
                xRatio = random.nextFloat(),
                yRatio = 0.70f + random.nextFloat() * 0.05f,
                scale = 0.3f + random.nextFloat() * 0.2f,
                shade = 0,  // Lightest
                depth = 0.4f + random.nextFloat() * 0.2f
            ))
        }

        // Mid trees
        repeat(treeCount / 3) {
            trees.add(TreeData(
                xRatio = random.nextFloat(),
                yRatio = 0.73f + random.nextFloat() * 0.07f,
                scale = 0.5f + random.nextFloat() * 0.3f,
                shade = 1,  // Medium
                depth = 0.7f + random.nextFloat() * 0.3f
            ))
        }

        // Near trees (large, front)
        repeat(treeCount / 3 + treeCount % 3) {
            trees.add(TreeData(
                xRatio = random.nextFloat(),
                yRatio = 0.78f + random.nextFloat() * 0.1f,
                scale = 0.8f + random.nextFloat() * 0.5f,
                shade = 2,  // Darkest
                depth = 1.0f + random.nextFloat() * 0.4f
            ))
        }

        trees.sortBy { it.depth }
    }

    private fun getThemeName(): String {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val weather = prefs.getString("pref_weather_type", "none") ?: "none"

        return when {
            weather == "rain" || weather == "snow" -> "rain"
            hour in 21..23 || hour in 0..4 -> "night"
            hour in 5..6 || hour in 18..20 -> "sunset"
            else -> "day"
        }
    }

    private fun updateTheme(theme: String) {
        when (theme) {
            "night" -> {
                skyTopColor = Color.parseColor("#0a0a1a")
                skyBottomColor = Color.parseColor("#1a1a3a")
                mountainFarColor = Color.parseColor("#2d2d4a")
                mountainMidColor = Color.parseColor("#1f1f3a")
                mountainNearColor = Color.parseColor("#121228")
                treeFarColor = Color.parseColor("#1a1a30")
                treeMidColor = Color.parseColor("#0f0f20")
                treeNearColor = Color.parseColor("#050510")
            }
            "sunset" -> {
                skyTopColor = Color.parseColor("#4a148c")
                skyBottomColor = Color.parseColor("#ff6f00")
                mountainFarColor = Color.parseColor("#5c2a9d")
                mountainMidColor = Color.parseColor("#4a1f7a")
                mountainNearColor = Color.parseColor("#311b60")
                treeFarColor = Color.parseColor("#3d2b60")
                treeMidColor = Color.parseColor("#2a1f4a")
                treeNearColor = Color.parseColor("#1a1030")
            }
            "rain" -> {
                skyTopColor = Color.parseColor("#455a64")
                skyBottomColor = Color.parseColor("#607d8b")
                mountainFarColor = Color.parseColor("#546e7a")
                mountainMidColor = Color.parseColor("#455a64")
                mountainNearColor = Color.parseColor("#37474f")
                treeFarColor = Color.parseColor("#4a5f5a")
                treeMidColor = Color.parseColor("#3a4f4a")
                treeNearColor = Color.parseColor("#2a3f3a")
            }
            else -> { // day
                skyTopColor = Color.parseColor("#4fc3f7")
                skyBottomColor = Color.parseColor("#b3e5fc")
                mountainFarColor = Color.parseColor("#7986cb")
                mountainMidColor = Color.parseColor("#5c6bc0")
                mountainNearColor = Color.parseColor("#3f51b5")
                treeFarColor = Color.parseColor("#4caf50")
                treeMidColor = Color.parseColor("#388e3c")
                treeNearColor = Color.parseColor("#2e7d32")
            }
        }

        mountainFarPaint.color = mountainFarColor
        mountainMidPaint.color = mountainMidColor
        mountainNearPaint.color = mountainNearColor
    }

    fun start() = sensor.start()
    fun stop() = sensor.stop()

    override fun onOffsetChanged(x: Float, y: Float) {
        ox = x * parallaxStrength
        oy = y * parallaxStrength
    }

    fun render(canvas: Canvas) {
        val w = canvas.width.toFloat()
        val h = canvas.height.toFloat()
        val theme = getThemeName()

        // Update theme colors if changed
        if (theme != lastTheme || w.toInt() != lastWidth || h.toInt() != lastHeight) {
            updateTheme(theme)
            lastTheme = theme
            lastWidth = w.toInt()
            lastHeight = h.toInt()

            skyPaint.shader = LinearGradient(
                0f, 0f, 0f, h,
                skyTopColor, skyBottomColor,
                Shader.TileMode.CLAMP
            )
        }

        // Layer 0: Sky gradient
        canvas.drawRect(0f, 0f, w, h, skyPaint)

        // Stars (night only)
        if (theme == "night") {
            val time = System.currentTimeMillis()
            for (star in stars) {
                val alpha = (155 + 100 * kotlin.math.sin((time * 0.001 + star[0] * 10).toDouble())).toInt().coerceIn(100, 255)
                starPaint.alpha = alpha
                canvas.drawCircle(star[0] * w, star[1] * h * 0.5f, star[2], starPaint)
            }
        }

        // Layer 1: Far mountains (depth 0.15)
        canvas.save()
        canvas.translate(ox * 0.15f, oy * 0.08f)
        drawMountainLayer(canvas, w, h, 0.50f, 0.25f, 0.30f, 0.45f, mountainFarPaint)
        canvas.restore()

        // Layer 2: Mid mountains (depth 0.3)
        canvas.save()
        canvas.translate(ox * 0.3f, oy * 0.15f)
        drawMountainLayer(canvas, w, h, 0.55f, 0.30f, 0.38f, 0.50f, mountainMidPaint)
        canvas.restore()

        // Layer 3: Near mountains (depth 0.5)
        canvas.save()
        canvas.translate(ox * 0.5f, oy * 0.25f)
        drawMountainLayer(canvas, w, h, 0.62f, 0.38f, 0.45f, 0.58f, mountainNearPaint)
        canvas.restore()

        // Layer 4: Trees (sorted by depth)
        for (tree in trees) {
            val color = when (tree.shade) {
                0 -> treeFarColor
                1 -> treeMidColor
                else -> treeNearColor
            }
            treePaint.color = color

            canvas.save()
            canvas.translate(ox * tree.depth, oy * tree.depth * 0.3f)
            drawTree(canvas, w * tree.xRatio, h * tree.yRatio, tree.scale * treeScale)
            canvas.restore()
        }
    }

    private fun drawMountainLayer(canvas: Canvas, w: Float, h: Float,
                                   startY: Float, peak1Y: Float, peak2Y: Float, endY: Float,
                                   paint: Paint) {
        mountainPath.reset()
        mountainPath.moveTo(-50f, h * startY)
        mountainPath.lineTo(w * 0.2f, h * peak1Y)
        mountainPath.lineTo(w * 0.35f, h * (peak1Y + 0.1f))
        mountainPath.lineTo(w * 0.5f, h * (peak1Y + 0.02f))
        mountainPath.lineTo(w * 0.7f, h * peak2Y)
        mountainPath.lineTo(w * 0.85f, h * (peak2Y + 0.08f))
        mountainPath.lineTo(w + 50f, h * endY)
        mountainPath.lineTo(w + 50f, h)
        mountainPath.lineTo(-50f, h)
        mountainPath.close()
        canvas.drawPath(mountainPath, paint)
    }

    private fun drawTree(canvas: Canvas, x: Float, baseY: Float, scale: Float) {
        val treeHeight = 160f * scale
        val treeWidth = 50f * scale

        // Three-tier pine tree (no trunk, just triangles)
        // Bottom tier
        treePath.reset()
        treePath.moveTo(x, baseY - treeHeight * 0.35f)
        treePath.lineTo(x - treeWidth, baseY + treeHeight * 0.05f)
        treePath.lineTo(x + treeWidth, baseY + treeHeight * 0.05f)
        treePath.close()
        canvas.drawPath(treePath, treePaint)

        // Middle tier
        treePath.reset()
        treePath.moveTo(x, baseY - treeHeight * 0.65f)
        treePath.lineTo(x - treeWidth * 0.75f, baseY - treeHeight * 0.18f)
        treePath.lineTo(x + treeWidth * 0.75f, baseY - treeHeight * 0.18f)
        treePath.close()
        canvas.drawPath(treePath, treePaint)

        // Top tier
        treePath.reset()
        treePath.moveTo(x, baseY - treeHeight)
        treePath.lineTo(x - treeWidth * 0.5f, baseY - treeHeight * 0.5f)
        treePath.lineTo(x + treeWidth * 0.5f, baseY - treeHeight * 0.5f)
        treePath.close()
        canvas.drawPath(treePath, treePaint)
    }
}

