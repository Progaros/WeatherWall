package info.olli.weatherlwp.rendering

import android.content.Context
import android.graphics.Canvas
import android.util.Log
import info.olli.weatherlwp.parallax.ParallaxCalculator
import info.olli.weatherlwp.rendering.seasonal.ChristmasStarRenderer
import info.olli.weatherlwp.rendering.seasonal.DragonRenderer
import info.olli.weatherlwp.rendering.seasonal.FireworksSystem
import info.olli.weatherlwp.rendering.seasonal.HolidaySchedule
import info.olli.weatherlwp.rendering.seasonal.RainbowRenderer
import info.olli.weatherlwp.simulation.SimulationSettings
import info.olli.weatherlwp.simulation.SimulationState
import java.util.Calendar

/**
 * Seasonal event effects: Christmas star with trail and New Year fireworks.
 * Active during December 15 - January 7, or when debug override is set.
 * Facade that dispatches to the renderers in [info.olli.weatherlwp.rendering.seasonal].
 */
class SeasonalEvents(private val context: Context? = null) : SceneLayer {
    override val layerType = LayerType.SEASONAL_EVENTS

    /** Whether seasonal rendering is enabled (set by ParallaxScene). */
    var enabled: Boolean = true

    enum class Season {
        NONE,
        CHRISTMAS,      // Dec 15-26
        NEW_YEAR,       // Dec 27 - Jan 7
        CN_NEW_YEAR,    // Chinese New Year (late Jan - mid Feb, varies)
        ST_PATRICKS     // St. Patrick's Day (Mar 14-18)
    }

    private val christmasStar = ChristmasStarRenderer()
    private val fireworks = FireworksSystem()
    private val rainbow = RainbowRenderer()
    private val dragon = DragonRenderer()
    private val holidaySchedule: HolidaySchedule

    private var initialized = false
    private var screenWidth = 0
    private var screenHeight = 0

    // Scale factor for height-relative sizing (uses RenderConstants.REFERENCE_HEIGHT)
    // All pixel values are designed for 1920px height and scaled proportionally
    private var heightScale = 1f

    init {
        val defaults = SimulationSettings.fromSnapshot(emptyMap<String, Any?>())
        holidaySchedule = HolidaySchedule(defaults)
        applySettings(defaults)
    }

    /**
     * Apply settings from immutable snapshot.
     */
    private fun applySettings(settings: SimulationSettings) {
        holidaySchedule.applySettings(settings)
        christmasStar.applySettings(settings)
        fireworks.applySettings(settings)
        rainbow.applySettings(settings)
    }

    /**
     * Check if shake-to-fireworks is enabled.
     */
    fun isShakeFireworksEnabled(): Boolean = fireworks.shakeFireworksEnabled

    /** (Re)initialize all renderers when not yet initialized or the surface size changed. */
    private fun ensureInitialized(width: Int, height: Int) {
        if (!initialized || screenWidth != width || screenHeight != height) {
            screenWidth = width
            screenHeight = height
            heightScale = RenderConstants.getHeightScale(screenHeight.toFloat(), screenWidth.toFloat())
            initParticles()
            initialized = true
        }
    }

    /**
     * Trigger fireworks on shake gesture.
     * Launches multiple rockets in quick succession for a burst effect.
     * Uses a normal distribution around the target count for variety.
     * Works regardless of seasonal settings being enabled.
     */
    fun triggerShakeFireworks() {
        if (!fireworks.shakeFireworksEnabled) return

        // Ensure initialization even if seasonal events are disabled
        // This allows shake fireworks to work year-round
        if (!initialized) {
            Log.d(TAG, "Shake triggered but not initialized - waiting for next frame")
            return
        }

        fireworks.triggerShake()
    }

    /**
     * Initialize for shake fireworks if not already done.
     * Called from ParallaxScene to ensure shake works even without seasonal rendering.
     */
    fun ensureShakeReady(width: Int, height: Int) {
        if (fireworks.shakeFireworksEnabled && !initialized) {
            ensureInitialized(width, height)
        }
    }

    fun getCurrentSeason(): Season = holidaySchedule.getCurrentSeason()

    /**
     * Determine season from current date using circular day distance.
     * Each event has a center date and ±interval days.
     * Priority order: Christmas → New Year → CN New Year → St. Patrick's.
     */
    internal fun getSeasonByDate(
        cal: Calendar = Calendar.getInstance()
    ): Season = holidaySchedule.getSeasonByDate(cal)

    companion object {
        private const val TAG = "SeasonalEvents"

        /**
         * Circular distance between two day-of-year values,
         * correctly wrapping around year-end (e.g. Dec 31→Jan 1 = 1 day).
         */
        internal fun circularDayDistance(day1: Int, day2: Int, daysInYear: Int = 365): Int =
            HolidaySchedule.circularDayDistance(day1, day2, daysInYear)

        /**
         * Get the day-of-year for a given month and day.
         * @param month 0-indexed Calendar month (Calendar.JANUARY=0, Calendar.DECEMBER=11)
         */
        internal fun dayOfYear(month: Int, day: Int, referenceCal: Calendar = Calendar.getInstance()): Int =
            HolidaySchedule.dayOfYear(month, day, referenceCal)
    }

    /**
     * Legacy no-op retained for compatibility with older call sites.
     */
    fun updateSettingsIfNeeded() {
        // Settings now come from ParallaxScene.reloadSettings(settings).
    }

    fun render(canvas: Canvas, enabled: Boolean) {
        if (!enabled) return

        try {
            val season = getCurrentSeason()
            if (season == Season.NONE) return

            ensureInitialized(canvas.width, canvas.height)

            // All rendering is now handled by renderBehindMountains/renderBehindTerrain
            // This method only ensures initialization for screen size changes
        } catch (e: Exception) {
            Log.e(TAG, "Error rendering seasonal events", e)
        }
    }

    /**
     * Render elements that belong behind the terrain/mountains (e.g. St. Patrick's rainbow, Christmas star).
     * This is called from the scene renderer just before MountainLayer is drawn.
     * These elements appear in the sky layer.
     * @param parallaxOffsetX X offset from parallax calculator for depth effect (default 0f)
     * @param parallaxOffsetY Y offset from parallax calculator for depth effect (default 0f)
     */
    fun renderBehindMountains(canvas: Canvas, enabled: Boolean, parallaxOffsetX: Float = 0f, parallaxOffsetY: Float = 0f) {
        if (!enabled) return
        try {
            val season = getCurrentSeason()
            if (season == Season.NONE) return
            ensureInitialized(canvas.width, canvas.height)
            when (season) {
                Season.ST_PATRICKS -> rainbow.draw(canvas, screenWidth, screenHeight, parallaxOffsetX, parallaxOffsetY)
                Season.CHRISTMAS -> christmasStar.draw(canvas, parallaxOffsetX, parallaxOffsetY)
                else -> {}
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error rendering behind-mountain seasonal events", e)
        }
    }

    /**
     * Render elements that belong in front of mountains but behind terrain/hills.
     * This includes New Year fireworks and Chinese New Year effects.
     * Called from scene renderer after mountains but before terrain layer.
     */
    fun renderBehindTerrain(canvas: Canvas, enabled: Boolean) {
        // Shake fireworks should render independent of season/master switch
        val hasActiveFireworks = fireworks.hasActiveFireworks()

        if (!enabled && !hasActiveFireworks) return
        try {
            val season = getCurrentSeason()

            // Allow rendering if we have active fireworks (from shake) even if season is NONE
            if (season == Season.NONE && !hasActiveFireworks) return

            ensureInitialized(canvas.width, canvas.height)
            // Fireworks should appear behind hills but in front of mountains
            when {
                season == Season.NEW_YEAR -> fireworks.render(canvas, autoLaunch = true)
                season == Season.CN_NEW_YEAR -> {
                    dragon.advance()
                    // New Year fireworks as backdrop for the dragon
                    fireworks.render(canvas, autoLaunch = true)
                    dragon.draw(canvas, screenWidth, screenHeight, heightScale)
                }
                // If not in season but we have active fireworks (e.g. from shake), render them without auto-launch
                hasActiveFireworks -> fireworks.render(canvas, autoLaunch = false)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error rendering behind-terrain seasonal events", e)
        }
    }

    private fun initParticles() {
        christmasStar.init(screenWidth, screenHeight, heightScale)
        fireworks.reset(screenWidth, screenHeight, heightScale)
    }

    // === SceneLayer interface implementation ===

    override fun resize(w: Float, h: Float) {
        val newW = w.toInt()
        val newH = h.toInt()
        if (screenWidth != newW || screenHeight != newH || !initialized) {
            screenWidth = newW
            screenHeight = newH
            heightScale = RenderConstants.getHeightScale(h, w)
            initParticles()
            initialized = true
        }
    }

    override fun update(state: SimulationState, deltaTimeMs: Long) {
        // Seasonal animation is frame-driven inside render methods
        updateSettingsIfNeeded()
    }

    override fun draw(canvas: Canvas, state: SimulationState, parallaxCalculator: ParallaxCalculator) {
        // No-op: SeasonalEvents renders via drawBehindMountains/drawBehindTerrain
        // called from ParallaxScene at the correct z-depth compositing points
    }

    /**
     * Draw elements behind mountains (Christmas star, rainbow).
     * Called from ParallaxScene at the sky z-depth.
     */
    fun drawBehindMountains(canvas: Canvas, parallaxCalculator: ParallaxCalculator) {
        val skyPx = parallaxCalculator.getOffsetXForDepth(RenderConstants.PARALLAX_SKY)
        val skyPy = parallaxCalculator.getOffsetYForDepth(RenderConstants.PARALLAX_SKY)
        renderBehindMountains(canvas, enabled, skyPx, skyPy)
    }

    /**
     * Draw elements behind terrain (fireworks, CN dragon).
     * Called from ParallaxScene between mountains and terrain.
     */
    fun drawBehindTerrain(canvas: Canvas) {
        renderBehindTerrain(canvas, enabled)
    }

    override fun reloadSettings(settings: SimulationSettings) {
        applySettings(settings)
    }

    override fun destroy() {
        // Pools are fixed-size arrays, no cleanup needed
    }
}
