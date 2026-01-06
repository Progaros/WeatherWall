package info.olli.weatherlwp.rendering

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import androidx.preference.PreferenceManager
import info.olli.weatherlwp.parallax.ParallaxCalculator
import info.olli.weatherlwp.simulation.SimulationState

/**
 * Debug overlay layer that displays performance and state information.
 * Renders on top of vignette so it's always visible.
 * 
 * Displays selectable info:
 * - FPS (current and average)
 * - Frame time in ms
 * - Power saving / idle mode status
 * - Time of day
 * - Weather type
 * - Wind strength
 * - Cloud density
 * - Active events count
 * - Moon phase (at night)
 * - Parallax offset
 */
class DebugOverlayLayer(private val context: Context) : SceneLayer {
    
    // Available debug data items
    enum class DebugItem(val key: String, val label: String) {
        FPS("fps", "FPS"),
        FRAME_TIME("frame_time", "Frame Time"),
        POWER_MODE("power_mode", "Power Mode"),
        TIME_OF_DAY("time_of_day", "Time of Day"),
        WEATHER("weather", "Weather"),
        WIND("wind", "Wind"),
        CLOUDS("clouds", "Cloud Density"),
        EVENTS("events", "Active Events"),
        MOON("moon", "Moon Phase"),
        PARALLAX("parallax", "Parallax Offset")
    }
    
    private val prefs = PreferenceManager.getDefaultSharedPreferences(context)
    
    private var enabled = false
    private var enabledItems = mutableSetOf<DebugItem>()
    
    private var width = 0f
    private var height = 0f
    
    // Frame timing
    private var lastFrameTimeNanos = 0L
    private var currentFps = 0f
    private var avgFps = 0f
    private var frameTimeMs = 0f
    private var frameCount = 0
    private var fpsAccumulator = 0f
    private val FPS_SAMPLE_SIZE = 30
    
    // External state (set by ParallaxScene)
    var activeEventCount: Int = 0
    var isIdleMode: Boolean = false
    var parallaxOffsetX: Float = 0f
    var parallaxOffsetY: Float = 0f
    
    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(180, 0, 0, 0)
        style = Paint.Style.FILL
    }
    
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 28f
        typeface = Typeface.MONOSPACE
    }
    
    private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(255, 150, 255, 150)  // Light green for values
        textSize = 28f
        typeface = Typeface.MONOSPACE
    }
    
    private val warningPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(255, 255, 150, 100)  // Orange for warnings
        textSize = 28f
        typeface = Typeface.MONOSPACE
    }
    
    init {
        reloadSettings()
    }
    
    override fun resize(w: Float, h: Float) {
        width = w
        height = h
        // Scale text size based on screen density
        val scaleFactor = (h / 2000f).coerceIn(0.6f, 1.5f)
        textPaint.textSize = 28f * scaleFactor
        valuePaint.textSize = 28f * scaleFactor
        warningPaint.textSize = 28f * scaleFactor
    }
    
    override fun update(state: SimulationState, deltaTimeMs: Long) {
        if (!enabled) return
        
        // Calculate FPS
        val currentNanos = System.nanoTime()
        if (lastFrameTimeNanos > 0) {
            val deltaNanos = currentNanos - lastFrameTimeNanos
            frameTimeMs = deltaNanos / 1_000_000f
            currentFps = 1_000_000_000f / deltaNanos
            
            // Accumulate for average
            fpsAccumulator += currentFps
            frameCount++
            
            if (frameCount >= FPS_SAMPLE_SIZE) {
                avgFps = fpsAccumulator / frameCount
                fpsAccumulator = 0f
                frameCount = 0
            }
        }
        lastFrameTimeNanos = currentNanos
    }
    
    override fun draw(canvas: Canvas, state: SimulationState, parallaxCalculator: ParallaxCalculator) {
        if (!enabled || enabledItems.isEmpty()) return
        
        val lines = mutableListOf<Triple<String, String, Boolean>>()  // label, value, isWarning
        
        // Build lines based on enabled items
        for (item in enabledItems) {
            val (label, value, isWarning) = when (item) {
                DebugItem.FPS -> {
                    val fpsStr = String.format("%.1f / %.1f", currentFps, avgFps)
                    val warn = currentFps < 25f
                    Triple("FPS (cur/avg)", fpsStr, warn)
                }
                DebugItem.FRAME_TIME -> {
                    val ftStr = String.format("%.1f ms", frameTimeMs)
                    val warn = frameTimeMs > 30f
                    Triple("Frame Time", ftStr, warn)
                }
                DebugItem.POWER_MODE -> {
                    val modeStr = if (isIdleMode) "IDLE (Power Save)" else "Normal"
                    Triple("Power Mode", modeStr, isIdleMode)
                }
                DebugItem.TIME_OF_DAY -> {
                    val hours = state.timeOfDayHours.toInt()
                    val minutes = ((state.timeOfDayHours - hours) * 60).toInt()
                    val timeStr = String.format("%02d:%02d", hours, minutes)
                    val period = when {
                        state.isNight -> "Night"
                        state.isDawn -> "Dawn"
                        state.isDusk -> "Dusk"
                        else -> "Day"
                    }
                    Triple("Time", "$timeStr ($period)", false)
                }
                DebugItem.WEATHER -> {
                    Triple("Weather", state.weather.name, false)
                }
                DebugItem.WIND -> {
                    val windStr = String.format("%.0f%% @ %.0f°", state.windStrength * 100, state.windDirection)
                    val warn = state.windStrength > 0.8f
                    Triple("Wind", windStr, warn)
                }
                DebugItem.CLOUDS -> {
                    Triple("Clouds", state.cloudDensity.name, false)
                }
                DebugItem.EVENTS -> {
                    Triple("Events", activeEventCount.toString(), false)
                }
                DebugItem.MOON -> {
                    if (state.isNight) {
                        val phaseStr = when {
                            state.moonPhase < 0.1f || state.moonPhase > 0.9f -> "New"
                            state.moonPhase < 0.25f -> "Waxing Crescent"
                            state.moonPhase < 0.35f -> "First Quarter"
                            state.moonPhase < 0.45f -> "Waxing Gibbous"
                            state.moonPhase < 0.55f -> "Full"
                            state.moonPhase < 0.65f -> "Waning Gibbous"
                            state.moonPhase < 0.75f -> "Last Quarter"
                            else -> "Waning Crescent"
                        }
                        Triple("Moon", phaseStr, false)
                    } else {
                        Triple("Moon", "N/A (day)", false)
                    }
                }
                DebugItem.PARALLAX -> {
                    val offsetStr = String.format("%.1f, %.1f", parallaxOffsetX, parallaxOffsetY)
                    Triple("Parallax", offsetStr, false)
                }
            }
            lines.add(Triple(label, value, isWarning))
        }
        
        if (lines.isEmpty()) return
        
        // Calculate background size
        val padding = 16f
        val lineHeight = textPaint.textSize * 1.4f
        val bgHeight = lines.size * lineHeight + padding * 2
        val bgWidth = 350f * (textPaint.textSize / 28f)  // Scale with text size
        
        // Position at bottom-left, above vignette
        val bgLeft = padding
        val bgTop = height - bgHeight - padding - (height * 0.08f)  // Above bottom vignette
        
        // Draw background
        canvas.drawRoundRect(bgLeft, bgTop, bgLeft + bgWidth, bgTop + bgHeight, 12f, 12f, backgroundPaint)
        
        // Draw lines
        var y = bgTop + padding + textPaint.textSize
        for ((label, value, isWarning) in lines) {
            // Draw label
            canvas.drawText("$label:", bgLeft + padding, y, textPaint)
            
            // Draw value (right-aligned area)
            val paint = if (isWarning) warningPaint else valuePaint
            val valueWidth = paint.measureText(value)
            canvas.drawText(value, bgLeft + bgWidth - padding - valueWidth, y, paint)
            
            y += lineHeight
        }
    }
    
    override fun reloadSettings() {
        enabled = prefs.getBoolean("pref_debug_overlay_enabled", false)
        
        // Load enabled items from preferences
        enabledItems.clear()
        val itemsSet = prefs.getStringSet("pref_debug_overlay_items", null)
        if (itemsSet != null) {
            for (key in itemsSet) {
                DebugItem.entries.find { it.key == key }?.let { enabledItems.add(it) }
            }
        } else {
            // Default: show FPS and Frame Time
            enabledItems.add(DebugItem.FPS)
            enabledItems.add(DebugItem.FRAME_TIME)
        }
    }
    
    override fun destroy() {
        // No resources to release
    }
}
