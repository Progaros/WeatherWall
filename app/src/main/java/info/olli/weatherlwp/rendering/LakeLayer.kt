package info.olli.weatherlwp.rendering

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import androidx.preference.PreferenceManager
import info.olli.weatherlwp.PreferenceDefaults
import info.olli.weatherlwp.parallax.ParallaxCalculator
import info.olli.weatherlwp.simulation.SimulationSettings
import info.olli.weatherlwp.simulation.SimulationState
import info.olli.weatherlwp.util.WaterColorUtil
import kotlin.math.sin

/**
 * Renders a lake (still body of water) on the terrain.
 *
 * The lake sits at a configurable position and depth layer, reacting to
 * weather conditions (water level from river/rain history, freezing in winter).
 *
 * It is drawn within the terrain layer loop (via callback) so that trees and
 * hills in front correctly occlude the water surface.
 *
 * Settings:
 * - **Position**: horizontal placement (0=left, 100=right)
 * - **Size**: width of the lake as percentage of screen
 * - **Depth layer**: which terrain hill layer (0=foreground, 100=background)
 * - **Opacity**: water transparency
 */
class LakeLayer(private val context: Context) : SceneLayer {
    override val layerType = LayerType.LAKE

    companion object {
        private const val TAG = "LakeLayer"
    }

    /** Callback to query terrain Y position at a given (xRatio, depthFactor). */
    @Deprecated("Use terrainYWorldProvider (worldX-based) instead")
    var terrainYProvider: ((xRatio: Float, depthFactor: Float) -> Float)? = null

    /** World-space callback to query terrain Y position at a given (worldX, depthFactor). */
    var terrainYWorldProvider: ((worldX: Float, depthFactor: Float) -> Float)? = null

    /** Callback to get the sorted list of hill depth factors. */
    var hillDepthProvider: (() -> List<Float>)? = null

    /** When true, draw() is skipped; TerrainLayer calls drawAtDepthLayer() instead. */
    var drawViaCallback = false

    // Pre-allocated drawing objects
    private val lakePaint = Paint().apply {
        style = Paint.Style.FILL
        isAntiAlias = true
    }
    private val shimmerPaint = Paint().apply {
        style = Paint.Style.FILL
        isAntiAlias = true
    }
    private val lakePath = Path()

    // Pre-allocated terrain sampling array (numSamples=30 is constant)
    private val terrainYsArray = FloatArray(31)

    // Pre-allocated gradient arrays to avoid per-segment allocation
    private val gradientColors = IntArray(3)
    private val gradientStops = floatArrayOf(0f, 0.3f, 1f)
    private val simpleGradientStops = floatArrayOf(0f, 0.35f, 1f)

    // Cached gradients to avoid per-frame allocation
    private var cachedSimpleGradient: LinearGradient? = null
    private var cachedSimpleGradientKey = 0L
    private var cachedSegmentGradient: LinearGradient? = null
    private var cachedSegmentGradientKey = 0L

    // Dimensions
    private var width = 0f
    private var height = 0f
    private var extendedWidth = 0f
    private var extendedOffset = 0f

    // Cached transform: rebuilt on resize only
    private var cachedTransform = WorldViewportTransform(1f, 1f)

    // Settings
    private var enabled = PreferenceDefaults.LAKE_ENABLED
    private var positionPercent = PreferenceDefaults.LAKE_POSITION / 100f
    private var sizePercent = PreferenceDefaults.LAKE_SIZE / 100f
    private var depthLayerPercent = PreferenceDefaults.LAKE_DEPTH_LAYER / 100f
    private var opacity = PreferenceDefaults.LAKE_OPACITY / 100f

    // Animation
    private var shimmerPhase = 0f

    // Lake water palette — increased saturation/brightness for visibility
    private val waterPalette = WaterColorUtil.WaterPalette(
        day   = Color.rgb(40, 110, 170),   // Deeper blue for readability
        dawn  = Color.rgb(110, 100, 140),
        dusk  = Color.rgb(100, 65, 85),
        night = Color.rgb(12, 22, 50),
        deep  = Color.rgb(15, 40, 70),     // Much darker deep for contrast
        ice   = Color.rgb(195, 215, 235)
    )

    override fun resize(w: Float, h: Float) {
        width = w
        height = h
        extendedWidth = w
        extendedOffset = 0f
        cachedTransform = WorldViewportTransform(w, h)
    }

    override fun update(state: SimulationState, deltaTimeMs: Long) {
        if (!enabled) return
        shimmerPhase += deltaTimeMs / 1000f * 0.4f
        if (shimmerPhase > 1000f) shimmerPhase -= 1000f
    }

    override fun draw(canvas: Canvas, state: SimulationState, parallaxCalculator: ParallaxCalculator) {
        // Drawing is handled via drawAtDepthLayer callback when integrated with terrain
        if (drawViaCallback || !enabled || width <= 0 || height <= 0) return
    }

    /**
     * Determine which terrain depth layer index the lake should be drawn at.
     * Returns -1 if lake should not be drawn.
     */
    fun getTargetLayerIndex(totalLayers: Int): Int {
        if (!enabled || totalLayers <= 0) return -1
        // Settings semantics: 0=foreground, 100=background.
        // Terrain layer index semantics: 0=background, last=foreground.
        val clampedDepthPercent = depthLayerPercent.coerceIn(0f, 1f)
        return ((1f - clampedDepthPercent) * (totalLayers - 1)).toInt().coerceIn(0, totalLayers - 1)
    }

    /**
     * Draw the lake at a specific depth layer. Called by TerrainLayer via callback.
     */
    fun drawAtDepthLayer(
        canvas: Canvas, state: SimulationState, parallaxCalculator: ParallaxCalculator,
        depthFactor: Float, layerIndex: Int, totalLayers: Int
    ) {
        if (!enabled || width <= 0 || height <= 0) return

        // Only draw at the target depth layer
        val targetLayer = getTargetLayerIndex(totalLayers)
        if (layerIndex != targetLayer) return

        val waterLevel = state.riverLevel.coerceIn(0f, 1f)
        // Lake shows even with very low water level (it holds water)
        val effectiveLevel = (waterLevel * 1.5f + 0.3f).coerceIn(0.3f, 1f)

        val isFrozen = WaterColorUtil.isFrozen(state, waterPalette.frozenThreshold)

        val waterColor = WaterColorUtil.getWaterColor(state, isFrozen, waterPalette)
        val worldProvider = terrainYWorldProvider
        @Suppress("DEPRECATION")
        val legacyProvider = terrainYProvider
        if (worldProvider == null && legacyProvider == null) return

        val transform = if (worldProvider != null)
            cachedTransform
        else null

        val px = parallaxCalculator.getOffsetXForDepth(depthFactor)
        val py = parallaxCalculator.getOffsetYForDepth(depthFactor)

        canvas.save()
        canvas.translate(px, py)

        // Calculate lake bounds in screen coordinates using viewport transform
        val lakeWidthPx = width * sizePercent * 1.2f  // slightly wider for natural feel
        // Map position percent to world-space X: 0.0 = left edge, 0.5 = center, 1.0 = right edge
        val lakeTransform = transform ?: cachedTransform
        val lakeWorldX = (positionPercent - 0.5f) * lakeTransform.worldWidth
        val lakeCenterX = lakeTransform.worldToScreenX(lakeWorldX)
        val lakeLeft = lakeCenterX - lakeWidthPx / 2f
        val lakeRight = lakeCenterX + lakeWidthPx / 2f

        // Sample terrain Y to find the local valley in the lake area
        val numSamples = 30
        var minTerrainY = Float.MAX_VALUE
        var maxTerrainY = Float.MIN_VALUE
        for (i in 0..numSamples) {
            val screenX = lakeLeft + (lakeRight - lakeLeft) * i.toFloat() / numSamples
            val terrainY = if (worldProvider != null && transform != null) {
                worldProvider(transform.screenToWorldX(screenX), depthFactor) * height
            } else {
                // Legacy fallback: convert screen X to xRatio via viewport
                val xRatio = (screenX / width).coerceIn(0f, 1f)
                legacyProvider!!.invoke(xRatio, depthFactor) * height
            }
            terrainYsArray[i] = terrainY
            if (terrainY < minTerrainY) minTerrainY = terrainY
            if (terrainY > maxTerrainY) maxTerrainY = terrainY
        }

        val valleyRange = maxTerrainY - minTerrainY
        if (valleyRange < 2f) {
            // Flat terrain - draw a simple oval lake
            val lakeY = maxTerrainY
            val lakeDepthPx = height * 0.025f * effectiveLevel
            drawSimpleLake(canvas, state, lakeCenterX, lakeY, lakeWidthPx, lakeDepthPx, waterColor, isFrozen)
            canvas.restore()
            return
        }

        // Water surface fills the valley
        val waterSurfaceY = maxTerrainY - valleyRange * effectiveLevel * 0.5f

        // Build lake path: water fills below waterSurfaceY within lake bounds
        lakePath.reset()
        var segmentStart = -1
        val sampleStepX = (lakeRight - lakeLeft) / numSamples

        for (i in 0..numSamples) {
            val terrainY = terrainYsArray[i]

            if (terrainY > waterSurfaceY) {
                if (segmentStart < 0) segmentStart = i
            } else if (segmentStart >= 0) {
                buildAndDrawLakeSegment(
                    canvas, state, segmentStart, i - 1, lakeLeft, sampleStepX,
                    waterSurfaceY, isFrozen, waterColor, effectiveLevel, maxTerrainY
                )
                segmentStart = -1
            }
        }

        if (segmentStart >= 0) {
            buildAndDrawLakeSegment(
                canvas, state, segmentStart, numSamples, lakeLeft, sampleStepX,
                waterSurfaceY, isFrozen, waterColor, effectiveLevel, maxTerrainY
            )
        }

        // Draw shimmer/sparkles on lake surface
        if (!isFrozen && effectiveLevel > 0.3f) {
            drawShimmer(canvas, state, lakeCenterX, waterSurfaceY, lakeWidthPx)
        }

        canvas.restore()
    }

    /**
     * Simple oval lake for flat terrain.
     */
    @Suppress("UNUSED_PARAMETER")
    private fun drawSimpleLake(
        canvas: Canvas, state: SimulationState,
        cx: Float, cy: Float, w: Float, h: Float,
        color: Int, isFrozen: Boolean
    ) {
        // Boost alpha: ensure lake is always clearly visible
        val alphaInt = (opacity * 255).toInt().coerceIn(100, 255)
        val deepColor = waterPalette.deep

        // 3-stop gradient: shallow edge → mid water → deep bottom
        val shallowColor = RenderConstants.lerpColor(color, Color.rgb(120, 200, 220), 0.3f)
        val topY = cy - h * 0.3f
        val botY = cy + h

        val yBucket = (topY.toInt() / 4).toLong()
        val hBucket = (botY.toInt() / 4).toLong()
        val key = yBucket * 100003L + hBucket * 31L + color.toLong() + alphaInt
        if (cachedSimpleGradient == null || cachedSimpleGradientKey != key) {
            gradientColors[0] = Color.argb(alphaInt, Color.red(shallowColor), Color.green(shallowColor), Color.blue(shallowColor))
            gradientColors[1] = Color.argb(alphaInt, Color.red(color), Color.green(color), Color.blue(color))
            gradientColors[2] = Color.argb(alphaInt, Color.red(deepColor), Color.green(deepColor), Color.blue(deepColor))
            cachedSimpleGradient = LinearGradient(
                0f, topY, 0f, botY,
                gradientColors,
                simpleGradientStops,
                Shader.TileMode.CLAMP
            )
            cachedSimpleGradientKey = key
        }
        lakePaint.shader = cachedSimpleGradient

        canvas.drawOval(cx - w / 2f, topY, cx + w / 2f, botY, lakePaint)
        lakePaint.shader = null

        // Shoreline highlight rim
        shimmerPaint.color = Color.argb((50 * opacity).toInt().coerceIn(15, 80), 200, 230, 255)
        shimmerPaint.style = Paint.Style.STROKE
        shimmerPaint.strokeWidth = 2f
        canvas.drawOval(cx - w / 2f, topY, cx + w / 2f, botY, shimmerPaint)
        shimmerPaint.style = Paint.Style.FILL

        // Horizontal ripple strokes
        if (!isFrozen) {
            val rippleAlpha = (30 * opacity).toInt().coerceIn(8, 50)
            shimmerPaint.color = Color.argb(rippleAlpha, 200, 220, 255)
            for (r in 0..2) {
                val ry = topY + (botY - topY) * (0.2f + r * 0.25f)
                val rw = w * (0.3f - r * 0.06f)
                val waveOff = sin((shimmerPhase * 0.6f + r * 1.2f).toDouble()).toFloat() * w * 0.02f
                canvas.drawLine(cx - rw + waveOff, ry, cx + rw + waveOff, ry, shimmerPaint)
            }
        }
    }

    /**
     * Build proper lake polygon for one contiguous valley segment and draw it.
     * Top edge traces the animated water surface; bottom edge follows terrain contour.
     */
    private fun buildAndDrawLakeSegment(
        canvas: Canvas, state: SimulationState,
        startIdx: Int, endIdx: Int,
        lakeLeft: Float, sampleStepX: Float,
        waterSurfaceY: Float, isFrozen: Boolean,
        waterColor: Int, effectiveLevel: Float, maxTerrainY: Float
    ) {
        lakePath.reset()
        val waveAmp = if (isFrozen) 0f else 1.5f * effectiveLevel

        // Phase 1: Trace water surface left-to-right (top edge)
        for (i in startIdx..endIdx) {
            val screenX = lakeLeft + i * sampleStepX
            val wave = if (isFrozen) 0f
                       else sin((screenX * 0.03f + shimmerPhase * 0.8f).toDouble()).toFloat() * waveAmp
            if (i == startIdx) {
                lakePath.moveTo(screenX, waterSurfaceY + wave)
            } else {
                lakePath.lineTo(screenX, waterSurfaceY + wave)
            }
        }

        // Phase 2: Trace terrain contour right-to-left (bottom edge)
        for (i in endIdx downTo startIdx) {
            val screenX = lakeLeft + i * sampleStepX
            lakePath.lineTo(screenX, terrainYsArray[i])
        }

        lakePath.close()
        drawLakeSegment(canvas, state, waterColor, isFrozen, waterSurfaceY, maxTerrainY)
    }

    /**
     * Draw a filled lake segment (valley).
     */
    @Suppress("UNUSED_PARAMETER")
    private fun drawLakeSegment(
        canvas: Canvas, state: SimulationState,
        color: Int, isFrozen: Boolean,
        surfaceY: Float, bottomY: Float
    ) {
        // Boost alpha for visibility
        val alphaInt = (opacity * 255).toInt().coerceIn(100, 255)
        val shallowColor = RenderConstants.lerpColor(color, Color.rgb(120, 200, 220), 0.3f)
        val deepColor = waterPalette.deep

        // 3-stop gradient: shallow near surface → main color → deep at bottom
        val sBucket = (surfaceY.toInt() / 4).toLong()
        val bBucket = (bottomY.toInt() / 4).toLong()
        val key = sBucket * 100003L + bBucket * 31L + color.toLong() + alphaInt
        if (cachedSegmentGradient == null || cachedSegmentGradientKey != key) {
            gradientColors[0] = Color.argb(alphaInt, Color.red(shallowColor), Color.green(shallowColor), Color.blue(shallowColor))
            gradientColors[1] = Color.argb(alphaInt, Color.red(color), Color.green(color), Color.blue(color))
            gradientColors[2] = Color.argb(alphaInt, Color.red(deepColor), Color.green(deepColor), Color.blue(deepColor))
            cachedSegmentGradient = LinearGradient(
                0f, surfaceY, 0f, bottomY,
                gradientColors,
                gradientStops,
                Shader.TileMode.CLAMP
            )
            cachedSegmentGradientKey = key
        }
        lakePaint.shader = cachedSegmentGradient
        canvas.drawPath(lakePath, lakePaint)
        lakePaint.shader = null

        // Shoreline highlight — thin bright line along the water edge path
        shimmerPaint.color = Color.argb((45 * opacity).toInt().coerceIn(12, 70), 180, 220, 255)
        shimmerPaint.style = Paint.Style.STROKE
        shimmerPaint.strokeWidth = 1.5f
        canvas.drawPath(lakePath, shimmerPaint)
        shimmerPaint.style = Paint.Style.FILL

        // Ice sheen overlay
        if (isFrozen) {
            lakePaint.color = Color.argb((alphaInt * 0.3f).toInt(), 220, 235, 250)
            canvas.drawPath(lakePath, lakePaint)
        }
    }

    /**
     * Draw shimmer/sparkle effect on lake surface.
     */
    private fun drawShimmer(
        canvas: Canvas, state: SimulationState,
        centerX: Float, surfaceY: Float, lakeWidth: Float
    ) {
        val lightLevel = state.getLightLevel()
        if (lightLevel < 0.15f && state.moonIllumination < 0.2f) return

        // Continuous blend: day sparkles from sun, night sparkles from moon
        val dayFactor = ((lightLevel - 0.15f) / 0.85f).coerceIn(0f, 1f)
        val intensity = dayFactor * lightLevel * 0.8f + (1f - dayFactor) * state.moonIllumination * 0.5f

        // Horizontal ripple lines
        val rippleAlpha = (intensity * 60 * opacity).toInt().coerceIn(10, 80)
        shimmerPaint.color = Color.argb(rippleAlpha, 200, 225, 255)
        for (r in 0..3) {
            val ry = surfaceY + height * 0.005f * (r + 1)
            val rw = lakeWidth * (0.35f - r * 0.06f)
            val waveOff = sin((shimmerPhase * 0.5f + r * 1.5f).toDouble()).toFloat() * lakeWidth * 0.015f
            canvas.drawLine(centerX - rw + waveOff, ry, centerX + rw + waveOff, ry, shimmerPaint)
        }

        // Sparkle dots
        val sparkleAlpha = (intensity * 200 * opacity).toInt().coerceIn(0, 200)
        shimmerPaint.color = Color.argb(sparkleAlpha, 255, 255, 240)

        val numSparkles = (8 * intensity).toInt().coerceIn(2, 12)
        val seed = (shimmerPhase * 80).toInt()

        for (i in 0 until numSparkles) {
            val hash = ((i * 6271 + seed) % 10000) / 10000f
            val hash2 = ((i * 7919 + seed * 2) % 10000) / 10000f
            val x = centerX - lakeWidth * 0.4f + hash * lakeWidth * 0.8f
            val y = surfaceY + hash2 * height * 0.02f
            val twinkle = sin((shimmerPhase * 4f + i * 1.7f).toDouble()).toFloat()
            if (twinkle > 0.3f) {
                canvas.drawCircle(x, y, 1.5f + twinkle * 2f, shimmerPaint)
            }
        }
    }

    // ========================= Color Helpers =========================



    // ========================= Settings =========================

    override fun reloadSettings() {
        val snapshot = PreferenceManager.getDefaultSharedPreferences(context).all
        reloadSettings(SimulationSettings.fromSnapshot(snapshot))
    }

    override fun reloadSettings(settings: SimulationSettings) {
        enabled = settings.getBoolean("pref_lake_enabled", PreferenceDefaults.LAKE_ENABLED)
        positionPercent = settings.getInt("pref_lake_position", PreferenceDefaults.LAKE_POSITION) / 100f
        sizePercent = settings.getInt("pref_lake_size", PreferenceDefaults.LAKE_SIZE) / 100f
        depthLayerPercent = settings.getInt("pref_lake_depth", PreferenceDefaults.LAKE_DEPTH_LAYER) / 100f
        opacity = settings.getInt("pref_lake_opacity", PreferenceDefaults.LAKE_OPACITY) / 100f
    }

    override fun destroy() {
        // No resources to clean up
    }

    /** Check if the lake is currently visible. */
    fun isVisible(): Boolean = enabled
}
