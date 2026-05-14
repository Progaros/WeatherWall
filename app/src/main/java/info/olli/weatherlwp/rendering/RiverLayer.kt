package info.olli.weatherlwp.rendering

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import androidx.preference.PreferenceManager
import info.olli.weatherlwp.PreferenceDefaults
import info.olli.weatherlwp.parallax.ParallaxCalculator
import info.olli.weatherlwp.simulation.SimulationSettings
import info.olli.weatherlwp.simulation.SimulationState
import info.olli.weatherlwp.util.WaterColorUtil
import kotlin.math.sin

/**
 * Renders a river/stream that flows through terrain valleys from
 * background to foreground, following hill contours.
 *
 * Water level is driven by weather history:
 * - Rainfall accumulates water
 * - Snowmelt contributes when temperatures rise
 * - Water drains naturally over time
 * - Can dry out completely after extended dry periods
 *
 * Three switchable rendering styles:
 * 1. **Flat** – simple colored band with subtle shimmer
 * 2. **Wavy** – animated sine-wave surface with foam
 * 3. **Reflective** – gradient with sky color reflection and sparkle
 *
 * The river follows the valleys of each hill layer, drawn after terrain.
 */
class RiverLayer(private val context: Context) : SceneLayer {
    override val layerType = LayerType.RIVER

    companion object {
        private const val TAG = "RiverLayer"
    }

    /** Callback to query terrain Y position at a given (xRatio, depthFactor). */
    @Deprecated("Use terrainYWorldProvider (worldX-based) instead")
    var terrainYProvider: ((xRatio: Float, depthFactor: Float) -> Float)? = null

    /** World-space callback to query terrain Y position at a given (worldX, depthFactor). */
    var terrainYWorldProvider: ((worldX: Float, depthFactor: Float) -> Float)? = null

    /** Callback to get the sorted list of hill depth factors. */
    var hillDepthProvider: (() -> List<Float>)? = null

    /** When true, draw() is skipped; TerrainLayer calls drawAtDepthLayer() instead for correct z-ordering. */
    var drawViaCallback = false

    /** River visual style. */
    enum class RiverStyle {
        FLAT, WAVY, REFLECTIVE;

        companion object {
            fun fromString(s: String): RiverStyle = when (s.lowercase()) {
                "flat" -> FLAT
                "wavy" -> WAVY
                "reflective" -> REFLECTIVE
                else -> WAVY
            }
        }
    }

    // Pre-allocated drawing objects
    private val riverPaint = Paint().apply {
        style = Paint.Style.FILL
        isAntiAlias = true
    }
    private val foamPaint = Paint().apply {
        style = Paint.Style.STROKE
        isAntiAlias = true
        strokeWidth = 2f
        color = Color.argb(120, 255, 255, 255)
    }
    private val sparkPaint = Paint().apply {
        style = Paint.Style.FILL
        isAntiAlias = true
    }
    private val riverPath = Path()
    private val foamPath = Path()
    private val segmentBounds = RectF()

    // Pre-allocated terrain sampling array (numSamples=50 is constant)
    private val terrainYsArray = FloatArray(51)

    // Pre-allocated gradient arrays to avoid per-segment allocation
    private val gradientColors = IntArray(3)
    private val gradientStops = floatArrayOf(0f, 0.3f, 1f)
    private val reflectiveGradientStops = floatArrayOf(0f, 0.4f, 1f)

    // Cached gradients to avoid per-frame allocation
    private var cachedWavyGradient: LinearGradient? = null
    private var cachedWavyGradientKey = 0L
    private var cachedReflectiveGradient: LinearGradient? = null
    private var cachedReflectiveGradientKey = 0L

    // Dimensions
    private var width = 0f
    private var height = 0f
    private var extendedWidth = 0f
    private var extendedOffset = 0f

    // Cached transform: rebuilt on resize only
    private var cachedTransform = WorldViewportTransform(1f, 1f)

    // Settings
    private var enabled = PreferenceDefaults.RIVER_ENABLED
    private var style = RiverStyle.WAVY
    private var riverWidthPercent = PreferenceDefaults.RIVER_WIDTH / 100f
    private var opacity = PreferenceDefaults.RIVER_OPACITY / 100f
    private var flowSpeed = PreferenceDefaults.RIVER_SPEED / 100f

    // Target depth layer percent: 0=foreground, 1=background. Default to foreground-ish.
    private var depthLayerPercent = PreferenceDefaults.RIVER_DEPTH_LAYER / 100f

    // Animation phase
    private var flowPhase = 0f

    // River water palette — increased saturation for visibility
    private val waterPalette = WaterColorUtil.WaterPalette(
        day   = Color.rgb(45, 115, 170),   // Stronger steel blue
        dawn  = Color.rgb(120, 105, 135),
        dusk  = Color.rgb(105, 70, 85),
        night = Color.rgb(12, 22, 45),
        deep  = Color.rgb(15, 40, 70),     // Darker for depth contrast
        ice   = Color.rgb(200, 220, 240)
    )

    override fun resize(w: Float, h: Float) {
        width = w
        height = h
        extendedWidth = w
        extendedOffset = 0f
        cachedTransform = WorldViewportTransform(w, h)
    }

    override fun update(state: SimulationState, deltaTimeMs: Long) {
        if (!enabled || state.riverLevel < 0.01f) return
        // Advance flow animation
        flowPhase += deltaTimeMs / 1000f * flowSpeed * 2f
        if (flowPhase > 1000f) flowPhase -= 1000f  // Prevent float overflow
    }

    override fun draw(canvas: Canvas, state: SimulationState, parallaxCalculator: ParallaxCalculator) {
        if (!enabled || width <= 0 || height <= 0) return

        val waterLevel = state.riverLevel
        if (waterLevel < 0.01f) return  // River is dry

        // When integrated with terrain layer for correct z-ordering,
        // per-depth drawing is handled via drawAtDepthLayer() callback
        if (drawViaCallback) return

        // Determine if river should be frozen
        val isFrozen = WaterColorUtil.isFrozen(state, waterPalette.frozenThreshold)

        // Get water color based on time of day
        val waterColor = WaterColorUtil.getWaterColor(state, isFrozen, waterPalette)

        // Get available hill layers from terrain
        val depthFactors = hillDepthProvider?.invoke() ?: emptyList()
        val worldProvider = terrainYWorldProvider
        @Suppress("DEPRECATION")
        val legacyProvider = terrainYProvider

        if (depthFactors.isEmpty() || (worldProvider == null && legacyProvider == null)) {
            // Fallback: simple horizontal river near bottom
            drawFallbackRiver(canvas, state, parallaxCalculator, waterLevel, waterColor, isFrozen)
            return
        }

        // Build transform for world-space sampling
        val transform = if (worldProvider != null)
            cachedTransform
        else null

        // Draw water at each hill depth layer (back to front)
        // Water appears in valley portions of each hill, getting wider toward foreground
        for ((layerIdx, depth) in depthFactors.withIndex()) {
            val layerProgress = layerIdx.toFloat() / depthFactors.size.coerceAtLeast(1)

            // River width per layer: narrower in back, wider in front
            val layerWidthFactor = 0.3f + layerProgress * 0.7f
            val maxRiverDepthPx = height * 0.04f * riverWidthPercent * layerWidthFactor * waterLevel
            if (maxRiverDepthPx < 1f) continue

            // Alpha: slightly more transparent in back, more opaque in front
            val layerAlpha = (0.4f + layerProgress * 0.6f) * opacity

            val px = parallaxCalculator.getOffsetXForDepth(depth)
            val py = parallaxCalculator.getOffsetYForDepth(depth)

            canvas.save()
            canvas.translate(px, py)

            // Sample terrain Y across the width to find valleys
            val left = -extendedOffset - width * 0.05f
            val right = width + extendedOffset + width * 0.05f
            val totalW = right - left
            val numSamples = 50

            // Find the valley floor (lowest Y = highest screen position, so we want MAX Y for valleys)
            var minTerrainY = Float.MAX_VALUE
            var maxTerrainY = Float.MIN_VALUE
            for (i in 0..numSamples) {
                val screenX = left + i.toFloat() / numSamples * totalW
                val terrainY = if (worldProvider != null && transform != null) {
                    worldProvider(transform.screenToWorldX(screenX), depth) * height
                } else {
                    val xRatio = i.toFloat() / numSamples
                    legacyProvider!!.invoke(xRatio, depth) * height
                }
                terrainYsArray[i] = terrainY
                if (terrainY < minTerrainY) minTerrainY = terrainY
                if (terrainY > maxTerrainY) maxTerrainY = terrainY
            }

            // The valley threshold: water fills from the deepest point up to a level
            // determined by water level (0 = only deepest point, 1 = fills to peaks)
            val valleyRange = maxTerrainY - minTerrainY
            if (valleyRange < 2f) {
                canvas.restore()
                continue  // Flat terrain, skip
            }

            val waterSurfaceY = maxTerrainY - valleyRange * waterLevel * 0.6f

            // Build river path: water fills below waterSurfaceY
            riverPath.reset()
            var segmentStart = -1
            val sampleStepX = totalW / numSamples

            for (i in 0..numSamples) {
                val terrainY = terrainYsArray[i]

                if (terrainY > waterSurfaceY) {
                    if (segmentStart < 0) segmentStart = i
                } else if (segmentStart >= 0) {
                    buildAndDrawSegment(
                        canvas, state, segmentStart, i - 1, left, sampleStepX,
                        waterSurfaceY, isFrozen, waterColor, layerAlpha, waterLevel, layerWidthFactor
                    )
                    segmentStart = -1
                }
            }

            // Close any remaining segment
            if (segmentStart >= 0) {
                buildAndDrawSegment(
                    canvas, state, segmentStart, numSamples, left, sampleStepX,
                    waterSurfaceY, isFrozen, waterColor, layerAlpha, waterLevel, layerWidthFactor
                )
            }

            canvas.restore()
        }
    }

    /**
     * Determine which terrain depth layer index the river should be drawn at.
     * Returns -1 if river should not be drawn.
     */
    fun getTargetLayerIndex(totalLayers: Int): Int {
        if (!enabled || totalLayers <= 0) return -1
        // Settings semantics: 0=foreground, 100=background.
        // Terrain layer index semantics: 0=background, last=foreground.
        val clampedDepthPercent = depthLayerPercent.coerceIn(0f, 1f)
        return ((1f - clampedDepthPercent) * (totalLayers - 1)).toInt().coerceIn(0, totalLayers - 1)
    }

    /**
     * Draw water at a single depth layer. Called by TerrainLayer via callback
     * for correct z-ordering (river behind trees but on top of hills).
     */
    fun drawAtDepthLayer(
        canvas: Canvas, state: SimulationState, parallaxCalculator: ParallaxCalculator,
        depthFactor: Float, layerIndex: Int, totalLayers: Int
    ) {
        if (!enabled || width <= 0 || height <= 0) return

        // Only draw at the target depth layer (single-layer policy like LakeLayer)
        val targetLayer = getTargetLayerIndex(totalLayers)
        if (layerIndex != targetLayer) return

        val waterLevel = state.riverLevel
        if (waterLevel < 0.01f) return

        val worldProvider = terrainYWorldProvider
        @Suppress("DEPRECATION")
        val legacyProvider = terrainYProvider
        if (worldProvider == null && legacyProvider == null) return

        val transform = if (worldProvider != null)
            cachedTransform
        else null

        val isFrozen = WaterColorUtil.isFrozen(state, waterPalette.frozenThreshold)
        val waterColor = WaterColorUtil.getWaterColor(state, isFrozen, waterPalette)

        val layerProgress = layerIndex.toFloat() / totalLayers.coerceAtLeast(1)
        val layerWidthFactor = 0.3f + layerProgress * 0.7f
        val maxRiverDepthPx = height * 0.04f * riverWidthPercent * layerWidthFactor * waterLevel
        if (maxRiverDepthPx < 1f) return

        val layerAlpha = (0.4f + layerProgress * 0.6f) * opacity

        val px = parallaxCalculator.getOffsetXForDepth(depthFactor)
        val py = parallaxCalculator.getOffsetYForDepth(depthFactor)

        canvas.save()
        canvas.translate(px, py)

        // Match TerrainLayer drawHill() bounds exactly so terrain sampling and river geometry align.
        val left = -extendedOffset - width * 0.1f
        val right = width + extendedOffset + width * 0.1f
        val totalW = right - left
        val numSamples = 50
        val sampleStepX = totalW / numSamples

        var minTerrainY = Float.MAX_VALUE
        var maxTerrainY = Float.MIN_VALUE
        for (i in 0..numSamples) {
            val screenX = left + i * sampleStepX
            val terrainY = if (worldProvider != null && transform != null) {
                worldProvider(transform.screenToWorldX(screenX), depthFactor) * height
            } else {
                val xRatio = ((screenX - left) / totalW).coerceIn(0f, 1f)
                legacyProvider!!.invoke(xRatio, depthFactor) * height
            }
            terrainYsArray[i] = terrainY
            if (terrainY < minTerrainY) minTerrainY = terrainY
            if (terrainY > maxTerrainY) maxTerrainY = terrainY
        }

        val valleyRange = maxTerrainY - minTerrainY
        if (valleyRange < 2f) {
            canvas.restore()
            return
        }

        val waterSurfaceY = maxTerrainY - valleyRange * waterLevel * 0.6f

        riverPath.reset()
        var segmentStart = -1

        for (i in 0..numSamples) {
            val terrainY = terrainYsArray[i]

            if (terrainY > waterSurfaceY) {
                if (segmentStart < 0) segmentStart = i
            } else if (segmentStart >= 0) {
                // End of valley segment - build proper polygon and draw
                buildAndDrawSegment(
                    canvas, state, segmentStart, i - 1, left, sampleStepX,
                    waterSurfaceY, isFrozen, waterColor, layerAlpha, waterLevel, layerWidthFactor
                )
                segmentStart = -1
            }
        }

        // Trailing segment
        if (segmentStart >= 0) {
            buildAndDrawSegment(
                canvas, state, segmentStart, numSamples, left, sampleStepX,
                waterSurfaceY, isFrozen, waterColor, layerAlpha, waterLevel, layerWidthFactor
            )
        }

        canvas.restore()
    }

    /**
     * Build a proper river polygon for one contiguous valley segment.
     * Top edge traces the animated water surface; bottom edge follows terrain contour.
     */
    private fun buildAndDrawSegment(
        canvas: Canvas, state: SimulationState,
        startIdx: Int, endIdx: Int,
        left: Float, sampleStepX: Float,
        waterSurfaceY: Float, isFrozen: Boolean,
        waterColor: Int, layerAlpha: Float, waterLevel: Float, layerWidthFactor: Float
    ) {
        riverPath.reset()
        val waveAmp = if (isFrozen) 0f else (2f + waterLevel * 3f) * layerWidthFactor

        // Phase 1: Trace water surface left-to-right (top edge)
        for (i in startIdx..endIdx) {
            val screenX = left + i * sampleStepX
            val wave = if (isFrozen) 0f
                       else sin((screenX * 0.02f + flowPhase * 1.5f).toDouble()).toFloat() * waveAmp +
                            sin((screenX * 0.045f + flowPhase * 0.7f).toDouble()).toFloat() * waveAmp * 0.3f
            if (i == startIdx) {
                riverPath.moveTo(screenX, waterSurfaceY + wave)
            } else {
                riverPath.lineTo(screenX, waterSurfaceY + wave)
            }
        }

        // Phase 2: Trace terrain contour right-to-left (bottom edge)
        for (i in endIdx downTo startIdx) {
            val screenX = left + i * sampleStepX
            riverPath.lineTo(screenX, terrainYsArray[i])
        }

        riverPath.close()
        drawRiverSegment(canvas, state, waterColor, layerAlpha, isFrozen, waterLevel, layerWidthFactor, waterSurfaceY)
    }

    /**
     * Draw a single river segment (a contiguous valley filled with water).
     */
    @Suppress("UNUSED_PARAMETER")
    private fun drawRiverSegment(
        canvas: Canvas, state: SimulationState,
        color: Int, alphaFactor: Float, isFrozen: Boolean,
        waterLevel: Float, widthFactor: Float,
        waterSurfaceY: Float
    ) {
        // Boost minimum alpha for visibility
        val alphaMultiplier = (alphaFactor * 255).toInt().coerceIn(60, 255)
        val deepColor = waterPalette.deep
        val shallowColor = RenderConstants.lerpColor(color, Color.rgb(100, 190, 210), 0.25f)

        // Compute river path bounds for gradient
        val bounds = segmentBounds
        riverPath.computeBounds(bounds, true)
        val topY = bounds.top
        val botY = bounds.bottom

        // 3-stop depth gradient: shallow near surface → main water → deep at bottom
        val sBucket = (topY.toInt() / 4).toLong()
        val bBucket = (botY.toInt() / 4).toLong()
        val gradKey = sBucket * 100003L + bBucket * 31L + color.toLong() + alphaMultiplier
        // Use cached wavy gradient slot (reuse existing cache fields)
        if (cachedWavyGradient == null || cachedWavyGradientKey != gradKey) {
            gradientColors[0] = Color.argb(alphaMultiplier, Color.red(shallowColor), Color.green(shallowColor), Color.blue(shallowColor))
            gradientColors[1] = Color.argb(alphaMultiplier, Color.red(color), Color.green(color), Color.blue(color))
            gradientColors[2] = Color.argb(alphaMultiplier, Color.red(deepColor), Color.green(deepColor), Color.blue(deepColor))
            cachedWavyGradient = LinearGradient(
                0f, topY, 0f, botY,
                gradientColors,
                gradientStops,
                Shader.TileMode.CLAMP
            )
            cachedWavyGradientKey = gradKey
        }
        riverPaint.shader = cachedWavyGradient
        canvas.drawPath(riverPath, riverPaint)
        riverPaint.shader = null

        // Shoreline highlight — thin bright stroke along the water edge
        foamPaint.color = Color.argb((35 * alphaFactor).toInt().coerceIn(10, 55), 180, 220, 255)
        foamPaint.strokeWidth = 1.5f * widthFactor
        foamPaint.style = Paint.Style.STROKE
        canvas.drawPath(riverPath, foamPaint)

        // Flow lines — horizontal ripples near the water surface
        if (!isFrozen && waterLevel > 0.15f) {
            val rippleAlpha = (22 * alphaFactor).toInt().coerceIn(6, 40)
            foamPaint.color = Color.argb(rippleAlpha, 200, 230, 255)
            foamPaint.strokeWidth = 1f
            val rippleY1 = topY + (botY - topY) * 0.15f
            val rippleY2 = topY + (botY - topY) * 0.35f
            val waveOff1 = sin((flowPhase * 0.8f).toDouble()).toFloat() * 3f
            val waveOff2 = sin((flowPhase * 0.6f + 1.5f).toDouble()).toFloat() * 3f
            canvas.drawLine(bounds.left + 4f + waveOff1, rippleY1, bounds.right - 4f + waveOff1, rippleY1, foamPaint)
            canvas.drawLine(bounds.left + 8f + waveOff2, rippleY2, bounds.right - 8f + waveOff2, rippleY2, foamPaint)
        }

        // Foam on surface (white froth)
        if (!isFrozen && waterLevel > 0.2f) {
            foamPaint.color = Color.argb((30 * alphaFactor).toInt().coerceIn(8, 50), 255, 255, 255)
            foamPaint.strokeWidth = 1.5f * widthFactor
            foamPaint.style = Paint.Style.STROKE
            canvas.drawPath(riverPath, foamPaint)
        }
    }

    /**
     * Fallback: simple horizontal river when no terrain data is available.
     */
    private fun drawFallbackRiver(
        canvas: Canvas, state: SimulationState,
        parallaxCalculator: ParallaxCalculator,
        waterLevel: Float, waterColor: Int, isFrozen: Boolean
    ) {
        val riverBaseY = height * 0.88f
        val maxRiverHeight = height * 0.08f * riverWidthPercent
        val riverHeight = maxRiverHeight * waterLevel.coerceIn(0f, 1f)
        if (riverHeight < 1f) return

        val px = parallaxCalculator.getOffsetXForDepth(RenderConstants.PARALLAX_HILLS_MAX - 0.05f)
        val py = parallaxCalculator.getOffsetYForDepth(RenderConstants.PARALLAX_HILLS_MAX - 0.05f)
        canvas.save()
        canvas.translate(px, py)

        val riverTop = riverBaseY - riverHeight
        val riverBottom = riverBaseY

        when (style) {
            RiverStyle.FLAT -> drawFlat(canvas, state, riverTop, riverBottom, waterColor, isFrozen)
            RiverStyle.WAVY -> drawWavy(canvas, state, riverTop, riverBottom, waterColor, waterLevel, isFrozen)
            RiverStyle.REFLECTIVE -> drawReflective(canvas, state, riverTop, riverBottom, waterColor, waterLevel, isFrozen)
        }
        canvas.restore()
    }

    // ========================= Flat Style =========================

    @Suppress("UNUSED_PARAMETER")
    private fun drawFlat(
        canvas: Canvas, state: SimulationState,
        top: Float, bottom: Float, color: Int, isFrozen: Boolean
    ) {
        val left = -extendedOffset - width * 0.1f
        val right = width + extendedOffset + width * 0.1f

        riverPaint.shader = null
        riverPaint.color = Color.argb(
            (Color.alpha(color) * opacity).toInt().coerceIn(0, 255),
            Color.red(color), Color.green(color), Color.blue(color)
        )

        canvas.drawRect(left, top, right, bottom, riverPaint)

        // Subtle shimmer line
        if (!isFrozen) {
            val shimmerY = top + (bottom - top) * 0.3f
            val shimmerAlpha = (40 + 20 * sin(flowPhase * 3f)).toInt().coerceIn(20, 80)
            foamPaint.color = Color.argb(shimmerAlpha, 255, 255, 255)
            foamPaint.strokeWidth = 1.5f
            canvas.drawLine(left, shimmerY, right, shimmerY, foamPaint)
        }
    }

    // ========================= Wavy Style =========================

    @Suppress("UNUSED_PARAMETER")
    private fun drawWavy(
        canvas: Canvas, state: SimulationState,
        top: Float, bottom: Float, color: Int, waterLevel: Float, isFrozen: Boolean
    ) {
        val left = -extendedOffset - width * 0.1f
        val right = width + extendedOffset + width * 0.1f
        val riverWidth = right - left

        // Build wavy surface path
        riverPath.reset()
        riverPath.moveTo(left, bottom)

        val waveAmplitude = if (isFrozen) 1f else (3f + waterLevel * 5f)  // Bigger waves with more water
        val waveFrequency = 0.02f  // Waves per pixel
        val numPoints = 60

        for (i in 0..numPoints) {
            val ratio = i.toFloat() / numPoints
            val x = left + ratio * riverWidth

            // Multiple wave layers for organic surface
            val wave1 = sin((x * waveFrequency + flowPhase * 1.5f).toDouble()).toFloat()
            val wave2 = sin((x * waveFrequency * 2.3f + flowPhase * 0.8f + 1.5f).toDouble()).toFloat() * 0.4f
            val wave3 = sin((x * waveFrequency * 0.5f + flowPhase * 0.3f + 3.0f).toDouble()).toFloat() * 0.6f

            val waveY = top + (wave1 + wave2 + wave3) * waveAmplitude

            if (i == 0) riverPath.moveTo(x, bottom)
            riverPath.lineTo(x, waveY)
        }

        riverPath.lineTo(right, bottom)
        riverPath.close()

        // Gradient from surface to depth — cache by bucketed positions + color
        val alphaMultiplier = (opacity * 255).toInt().coerceIn(0, 255)
        val tBucket = (top.toInt() / 4).toLong()
        val bBucket = (bottom.toInt() / 4).toLong()
        val wavyKey = tBucket * 100003L + bBucket * 31L + color.toLong() + alphaMultiplier
        if (cachedWavyGradient == null || cachedWavyGradientKey != wavyKey) {
            cachedWavyGradient = LinearGradient(
                0f, top, 0f, bottom,
                Color.argb(alphaMultiplier, Color.red(color), Color.green(color), Color.blue(color)),
                Color.argb(alphaMultiplier, Color.red(waterPalette.deep), Color.green(waterPalette.deep), Color.blue(waterPalette.deep)),
                Shader.TileMode.CLAMP
            )
            cachedWavyGradientKey = wavyKey
        }
        riverPaint.shader = cachedWavyGradient
        canvas.drawPath(riverPath, riverPaint)
        riverPaint.shader = null

        // Draw foam lines on surface (not when frozen)
        if (!isFrozen && waterLevel > 0.2f) {
            drawFoam(canvas, left, right, top, waveAmplitude)
        }
    }

    // ========================= Reflective Style =========================

    private fun drawReflective(
        canvas: Canvas, state: SimulationState,
        top: Float, bottom: Float, color: Int, waterLevel: Float, isFrozen: Boolean
    ) {
        val left = -extendedOffset - width * 0.1f
        val right = width + extendedOffset + width * 0.1f

        // Sky reflection color (lighter/bluer version of water)
        val reflectionColor = if (isFrozen) {
            waterPalette.ice
        } else {
            val skyInfluence = 0.3f
            RenderConstants.lerpColor(color, getSkyReflectionColor(state), skyInfluence)
        }

        val alphaMultiplier = (opacity * 255).toInt().coerceIn(0, 255)

        // Three-stop gradient: reflection at top, water color in middle, dark at bottom — cache
        val tBucket = (top.toInt() / 4).toLong()
        val bBucket = (bottom.toInt() / 4).toLong()
        val refKey = tBucket * 100003L + bBucket * 31L + color.toLong() + reflectionColor.toLong() * 7L + alphaMultiplier
        if (cachedReflectiveGradient == null || cachedReflectiveGradientKey != refKey) {
            gradientColors[0] = Color.argb(alphaMultiplier,
                Color.red(reflectionColor), Color.green(reflectionColor), Color.blue(reflectionColor))
            gradientColors[1] = Color.argb(alphaMultiplier,
                Color.red(color), Color.green(color), Color.blue(color))
            gradientColors[2] = Color.argb(alphaMultiplier,
                Color.red(waterPalette.deep), Color.green(waterPalette.deep), Color.blue(waterPalette.deep))
            cachedReflectiveGradient = LinearGradient(
                0f, top, 0f, bottom,
                gradientColors,
                reflectiveGradientStops,
                Shader.TileMode.CLAMP
            )
            cachedReflectiveGradientKey = refKey
        }
        riverPaint.shader = cachedReflectiveGradient

        // Slight wave on surface
        riverPath.reset()
        val riverWidth = right - left
        val numPoints = 40
        val waveAmp = if (isFrozen) 0.5f else 2f + waterLevel * 3f

        riverPath.moveTo(left, bottom)
        for (i in 0..numPoints) {
            val ratio = i.toFloat() / numPoints
            val x = left + ratio * riverWidth
            val wave = sin((x * 0.015f + flowPhase).toDouble()).toFloat() * waveAmp
            riverPath.lineTo(x, top + wave)
        }
        riverPath.lineTo(right, bottom)
        riverPath.close()

        canvas.drawPath(riverPath, riverPaint)
        riverPaint.shader = null

        // Sparkle effect (sun/moon reflection on water)
        if (!isFrozen && waterLevel > 0.1f) {
            drawSparkles(canvas, state, left, right, top, bottom)
        }
    }

    // ========================= Shared Effects =========================

    private fun drawFoam(canvas: Canvas, left: Float, right: Float, surfaceY: Float, amplitude: Float) {
        foamPath.reset()
        foamPaint.color = Color.argb(60, 255, 255, 255)
        foamPaint.strokeWidth = 1.5f

        val riverWidth = right - left
        val numPoints = 30

        for (i in 0..numPoints) {
            val ratio = i.toFloat() / numPoints
            val x = left + ratio * riverWidth
            val wave = sin((x * 0.025f + flowPhase * 2f + 0.5f).toDouble()).toFloat() * amplitude * 0.5f
            val y = surfaceY + wave + amplitude * 0.8f

            if (i == 0) foamPath.moveTo(x, y) else foamPath.lineTo(x, y)
        }

        canvas.drawPath(foamPath, foamPaint)
    }

    private fun drawSparkles(
        canvas: Canvas, state: SimulationState,
        left: Float, right: Float, top: Float, bottom: Float
    ) {
        val lightLevel = state.getLightLevel()
        if (lightLevel < 0.2f && state.moonIllumination < 0.3f) return  // Too dark for sparkles

        // Continuous blend: day sparkles from sun, night sparkles from moon
        val dayFactor = ((lightLevel - 0.15f) / 0.85f).coerceIn(0f, 1f)
        val sparkleIntensity = dayFactor * lightLevel * 0.8f + (1f - dayFactor) * state.moonIllumination * 0.5f

        val sparkleAlpha = (sparkleIntensity * 180).toInt().coerceIn(0, 200)
        sparkPaint.color = Color.argb(sparkleAlpha, 255, 255, 240)

        // Draw scattered sparkle dots
        val riverWidth = right - left
        val riverHeight = bottom - top
        val seed = (flowPhase * 100).toInt()         
        val numSparkles = (8 * sparkleIntensity).toInt().coerceIn(2, 15)

        for (i in 0 until numSparkles) {
            // Pseudo-random but deterministic positions that shift with flow
            val hash = ((i * 7919 + seed) % 10000) / 10000f
            val hash2 = ((i * 6271 + seed * 3) % 10000) / 10000f

            val x = left + hash * riverWidth
            val y = top + hash2 * riverHeight * 0.5f  // Sparkles near surface

            // Twinkle: vary size/alpha based on flow phase
            val twinkle = sin((flowPhase * 5f + i * 2.3f).toDouble()).toFloat()
            if (twinkle > 0.3f) {
                val radius = 1.5f + twinkle * 2f
                canvas.drawCircle(x, y, radius, sparkPaint)
            }
        }
    }

    // ========================= Color Helpers =========================



    private fun getSkyReflectionColor(state: SimulationState): Int {
        val hour = state.timeOfDayHours
        val sunrise = state.sunriseHours
        val sunset = state.sunsetHours

        val nightColor = Color.rgb(15, 20, 40)
        val dawnColor = Color.rgb(180, 150, 130)
        val dayColor = Color.rgb(135, 190, 230)
        val duskColor = Color.rgb(160, 100, 80)

        if (sunrise <= 0f || sunset <= 0f) {
            // Legacy fallback
            return when {
                state.isNight -> nightColor
                state.isDawn -> dawnColor
                state.isDusk -> duskColor
                else -> dayColor
            }
        }

        val dawnStart = sunrise - 1.0f
        val dawnEnd = sunrise + 1.5f
        val duskStart = sunset - 1.5f
        val duskEnd = sunset + 1.0f

        return when {
            // Pre-dawn: night → dawn
            hour >= dawnStart && hour < sunrise -> {
                val t = ((hour - dawnStart) / (sunrise - dawnStart)).coerceIn(0f, 1f)
                RenderConstants.lerpColorRGB(nightColor, dawnColor, t)
            }
            // Dawn → day
            hour >= sunrise && hour < dawnEnd -> {
                val t = ((hour - sunrise) / (dawnEnd - sunrise)).coerceIn(0f, 1f)
                RenderConstants.lerpColorRGB(dawnColor, dayColor, t)
            }
            // Day
            hour >= dawnEnd && hour < duskStart -> dayColor
            // Day → dusk
            hour >= duskStart && hour < sunset -> {
                val t = ((hour - duskStart) / (sunset - duskStart)).coerceIn(0f, 1f)
                RenderConstants.lerpColorRGB(dayColor, duskColor, t)
            }
            // Dusk → night
            hour >= sunset && hour < duskEnd -> {
                val t = ((hour - sunset) / (duskEnd - sunset)).coerceIn(0f, 1f)
                RenderConstants.lerpColorRGB(duskColor, nightColor, t)
            }
            // Night
            else -> nightColor
        }
    }

    // ========================= Settings =========================

    override fun reloadSettings() {
        val snapshot = PreferenceManager.getDefaultSharedPreferences(context).all
        reloadSettings(SimulationSettings.fromSnapshot(snapshot))
    }

    override fun reloadSettings(settings: SimulationSettings) {
        enabled = settings.getBoolean("pref_river_enabled", PreferenceDefaults.RIVER_ENABLED)
        style = RiverStyle.fromString(
            settings.getString("pref_river_style", PreferenceDefaults.RIVER_STYLE)
        )
        riverWidthPercent = settings.getInt("pref_river_width", PreferenceDefaults.RIVER_WIDTH) / 100f
        opacity = settings.getInt("pref_river_opacity", PreferenceDefaults.RIVER_OPACITY) / 100f
        flowSpeed = settings.getInt("pref_river_speed", PreferenceDefaults.RIVER_SPEED) / 100f
        depthLayerPercent = settings.getInt("pref_river_depth", PreferenceDefaults.RIVER_DEPTH_LAYER) / 100f
    }

    override fun destroy() {
        // No resources to clean up
    }

    /** Check if the river is currently visible. */
    fun isVisible(): Boolean = enabled
}
