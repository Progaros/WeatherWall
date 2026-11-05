package info.olli.weatherlwp.rendering

import android.graphics.Color
import androidx.core.graphics.toColorInt
import info.olli.weatherlwp.util.ColorMath
import kotlin.math.sqrt

/**
 * Centralized constants for rendering layers.
 * 
 * This file contains all magic numbers, color definitions, and rendering
 * parameters used across the scene layers. Centralizing these values:
 * - Makes tuning easier (single place to adjust)
 * - Improves consistency across layers
 * - Documents the rendering parameters
 */
object RenderConstants {
    
    // ============== Screen & Coordinate System ==============
    
    /** Reference screen height for scaling calculations (1080p portrait) */
    const val REFERENCE_HEIGHT = 1920f
    
    /** Minimum height scale to prevent elements becoming too small */
    const val MIN_HEIGHT_SCALE = 0.5f
    
    /**
     * Calculate height scale (pixels per world unit) for the current screen.
     *
     * This is the uniform scale factor used by the viewport model:
     *   `screenX = screenWidth/2 + worldX * heightScale`
     *
     * Using screenHeight directly (not max) means landscape mode correctly
     * shows a wider slice of the world rather than stretching. The wider
     * viewport is filled by cell-based generation over the visible range.
     *
     * @param screenHeight The actual screen height
     * @param screenWidth  Ignored (kept for API compat). Use screenHeight only.
     * @return Scale factor (1.0 at 1920px, clamped to MIN_HEIGHT_SCALE)
     */
    fun getHeightScale(screenHeight: Float, @Suppress("UNUSED_PARAMETER") screenWidth: Float = 0f): Float {
        return (screenHeight / REFERENCE_HEIGHT).coerceAtLeast(MIN_HEIGHT_SCALE)
    }
    
    /** Reference aspect ratio for world-space calculations (9:16 portrait) */
    const val REFERENCE_ASPECT_RATIO = 0.5625f
    
    /**
     * Off-screen side margin ratio relative to visible width.
     * Keeps coordinates centered on screen while still providing enough room
     * for parallax reveal and object spawn just outside the viewport.
     */
    const val EXTENDED_SIDE_MARGIN_RATIO = 0.38f

    /** Reference extended width: the canonical world width at reference height on reference device. */
    val REFERENCE_EXTENDED_WIDTH = REFERENCE_HEIGHT * REFERENCE_ASPECT_RATIO * (1f + 2f * EXTENDED_SIDE_MARGIN_RATIO)

    /**
     * Half-width of the visible world in world-space units for generation.
     *
     * Used for viewport-based generation: only cells within this range
     * around center need to be generated. Includes generous parallax padding
     * so content is available even at maximum tilt/scroll offset.
     *
     * Uses the viewport's actual world-space width, which is derived from
     * [REFERENCE_HEIGHT] (the default worldHeight) and the screen aspect
     * ratio. This makes generation:
     * - **zoom-independent**: changing worldHeight (zoom) does not change
     *   what is generated — only the viewport projection changes.
     * - **aspect-ratio-aware**: landscape screens produce a wider generation
     *   range because the viewport physically sees more of the world.
     *
     * @param screenWidth  The actual visible screen width in pixels.
     * @param screenHeight The actual visible screen height in pixels.
     * @return World-space half-width that covers viewport + parallax padding
     */
    fun getVisibleWorldHalf(screenWidth: Float, screenHeight: Float = REFERENCE_HEIGHT): Float {
        // Compute the actual visible world half-width from the viewport geometry.
        // worldWidth = REFERENCE_HEIGHT * aspect  (at default zoom)
        // In portrait (1080×1920): aspect=0.5625, worldHalf=540
        // In landscape (1920×1080): aspect=1.778, worldHalf=1707
        val aspect = screenWidth / screenHeight.coerceAtLeast(1f)
        val visibleHalf = REFERENCE_HEIGHT * aspect / 2f
        // Parallax padding: covers maximum parallax shift in world units
        val parallaxPad = REFERENCE_HEIGHT * 0.45f
        return visibleHalf + parallaxPad
    }

    /**
     * Calculate the generation world range for the given screen width.
     *
     * This is the total width of the deterministic world that elements are
     * generated across. It must be wide enough to cover the full screen plus
     * parallax padding even at the minimum height scale (maximum zoom-out).
     *
     * Only depends on [screenWidth] — height has no influence. This ensures
     * that the same set of elements (same count, same positions) is generated
     * regardless of the current preview height.
     *
     * @param screenWidth The actual visible screen width
     * @return The world range for element generation
     */
    fun getGenerationWorldRange(screenWidth: Float): Float {
        val minHeight = REFERENCE_HEIGHT * MIN_HEIGHT_SCALE
        val parallaxPadding = minHeight * 0.45f * 2f
        val neededAtMinScale = (screenWidth + parallaxPadding) / MIN_HEIGHT_SCALE
        return maxOf(neededAtMinScale, REFERENCE_EXTENDED_WIDTH)
    }

    /**
     * Calculate the extended canvas width for draw-time coordinate mapping.
     *
     * Returns [getGenerationWorldRange] scaled by [getHeightScale]. This makes
     * the coordinate space shrink when height decreases, causing all elements
     * to move symmetrically toward center (zoom-in effect). When height
     * increases, elements spread apart and previously off-screen content
     * becomes visible from both sides (zoom-out effect).
     *
     * @param screenWidth The actual visible screen width
     * @param screenHeight The actual visible screen height
     * @return The extended width for draw-time position mapping
     */
    fun getExtendedWidth(screenWidth: Float, screenHeight: Float): Float {
        val heightScale = getHeightScale(screenHeight)
        return getGenerationWorldRange(screenWidth) * heightScale
    }

    /**
     * Scale factor for count-based object density.
     *
     * Scales element counts proportionally to the generation world range
     * relative to the reference. This ensures consistent on-screen density:
     * wider screens generate more elements to fill the wider world range,
     * but the visible density stays the same.
     *
     * Height has no influence — the same count is generated regardless of
     * preview height, ensuring deterministic, stable element positions.
     */
    fun getHorizontalDensityScale(extendedWidth: Float, screenWidth: Float = 0f, @Suppress("UNUSED_PARAMETER") screenHeight: Float = 0f): Float {
        // Use the generation world range (width-only) for density scaling.
        // When screenWidth is provided, compute the canonical range directly.
        // Otherwise fall back to using extendedWidth as an approximation.
        val genRange = if (screenWidth > 0f) getGenerationWorldRange(screenWidth) else extendedWidth
        return (genRange / REFERENCE_EXTENDED_WIDTH).coerceIn(0.25f, 4f)
    }

    /**
     * Get the horizontal offset to center the extended canvas.
     * Content at X=0 in extended space should appear at screen center.
     * 
     * @param screenWidth The actual visible screen width
     * @param extendedWidth The extended canvas width
     * @return The offset to subtract from extended X to get screen X
     */
    fun getExtendedOffset(screenWidth: Float, extendedWidth: Float): Float {
        return (extendedWidth - screenWidth) / 2f
    }
    
    // ============== Parallax Depth Factors ==============
    // All values 0.0 to 1.0: 0 = far (moves least), 1 = near (moves most)
    // All layers move in SAME direction at different speeds to create depth
    // Near objects (trees, animals) move MORE than far objects (sky, mountains)
    
    /** Sky parallax - very far, minimal movement */
    const val PARALLAX_SKY = 0.05f
    
    /** Stars parallax - subtle movement, far background */
    const val PARALLAX_STARS = 0.10f
    
    /** Clouds parallax - far background */
    const val PARALLAX_CLOUDS = 0.15f
    
    /** Mountains parallax range - background */
    const val PARALLAX_MOUNTAIN_MIN = 0.20f
    const val PARALLAX_MOUNTAIN_MAX = 0.35f
    
    /** Hills/terrain parallax range - midground to foreground */
    const val PARALLAX_HILLS_MIN = 0.50f
    const val PARALLAX_HILLS_MAX = 0.80f
    
    /**
     * Calculate parallax depth factor for ground events based on normalized depth (0-1).
     * Maps normalized depth to the hills parallax range.
     * 
     * @param normalizedDepth 0.0 = closest to viewer, 1.0 = furthest
     * @return Parallax depth factor in PARALLAX_HILLS range
     */
    fun getHillParallaxDepth(normalizedDepth: Float): Float {
        return PARALLAX_HILLS_MIN + normalizedDepth * (PARALLAX_HILLS_MAX - PARALLAX_HILLS_MIN)
    }
    
    /**
     * Calculate event duration safely from distance and speed.
     * Ensures no division by zero and clamps result to reasonable range.
     * 
     * @param distance Total distance to travel (normalized screen units)
     * @param speed Movement speed per millisecond
     * @param minMs Minimum duration in milliseconds (default 1000ms = 1 second)
     * @param maxMs Maximum duration in milliseconds (default 180000ms = 3 minutes)
     * @return Safe duration in milliseconds
     */
    fun calculateEventDuration(distance: Float, speed: Float, minMs: Long = 1000L, maxMs: Long = 180000L): Long {
        val safeSpeed = speed.coerceAtLeast(0.00001f)
        val rawDuration = (distance / safeSpeed).toLong()
        return rawDuration.coerceIn(minMs, maxMs)
    }
    
    // ============== Sky Colors (3-stop gradients: Top, Middle, Horizon) ==============
    // Always use a 3-stop LinearGradient for the sky.
    // Horizon should be significantly lighter than top (atmosphere thickness)
    // NOTE: Lazy initialization prevents unit test failures when only math functions are used
    
    // Night: Deep space -> Dark blue -> Slightly warmer horizon
    // True dark sky has subtle blue tint, not pure black (atmospheric airglow + light pollution)
    val SKY_NIGHT_TOP: Int by lazy { "#0B0D1E".toColorInt() }      // Deep space (hint of blue)
    val SKY_NIGHT_MID: Int by lazy { "#151830".toColorInt() }      // Dark navy blue
    val SKY_NIGHT_BOTTOM: Int by lazy { "#252848".toColorInt() }   // Warmer blue at horizon
    
    // Dawn: Navy-blue transitioning through muted coral to warm golden horizon
    // Dawn colors are slightly less vivid than dusk per atmospheric science
    // (morning air is cleaner -> less Rayleigh scattering -> softer sunrise colors)
    val SKY_DAWN_TOP: Int by lazy { "#354880".toColorInt() }       // Brighter navy-blue (more light reaching)
    val SKY_DAWN_MID: Int by lazy { "#D08868".toColorInt() }       // Warm coral-salmon (slightly more vivid)
    val SKY_DAWN_BOTTOM: Int by lazy { "#F0B880".toColorInt() }    // Warm golden-orange horizon
    
    // Day: Clear blue -> Blue -> Light horizon haze
    // Lighter/paler than Material Design blues for photorealistic sky
    val SKY_DAY_TOP: Int by lazy { "#2E80D0".toColorInt() }        // Clear sky blue (less saturated)
    val SKY_DAY_MID: Int by lazy { "#60B8F5".toColorInt() }        // Bright sky blue
    val SKY_DAY_BOTTOM: Int by lazy { "#D0EFFF".toColorInt() }     // Pale hazy horizon
    
    // Dusk: Deep purple-blue -> Warm terracotta -> Soft golden-orange horizon
    // Dusk is more vivid than dawn (NOAA: evening air contains more particles
    // from daytime convection -> enhanced Rayleigh scattering)
    val SKY_DUSK_TOP: Int by lazy { "#2E2850".toColorInt() }       // Rich purple-blue
    val SKY_DUSK_MID: Int by lazy { "#D05838".toColorInt() }       // Vivid terracotta-orange
    val SKY_DUSK_BOTTOM: Int by lazy { "#E89050".toColorInt() }    // Warm deep golden-orange horizon
    
    // Ambient tint colors for global tinting (more muted for realism)
    val AMBIENT_NIGHT: Int by lazy { "#2A2040".toColorInt() }      // Purple-blue night tint
    val AMBIENT_DAWN: Int by lazy { "#E89070".toColorInt() }       // Muted warm salmon dawn tint
    val AMBIENT_DAY: Int by lazy { "#E8F0F8".toColorInt() }        // Very light neutral (nearly white with hint of sky)
    val AMBIENT_DUSK: Int by lazy { "#D06040".toColorInt() }       // Warm terracotta dusk tint
    
    // ============== Time Thresholds (hours) ==============
    // These are fallback defaults. SkyLayer uses dynamic sunrise/sunset from weather data.
    
    const val TIME_NIGHT_END = 5.5f
    const val TIME_DAWN_START = 5.5f
    const val TIME_DAWN_END = 7.0f
    const val TIME_DAY_END = 17.5f
    const val TIME_DUSK_END = 20.0f
    
    // ============== Mountain Colors ==============
    
    /** Base mountain gray (RGB values) - far mountains are lighter (aerial perspective) */
    const val MOUNTAIN_GRAY_NEAR = 85
    const val MOUNTAIN_GRAY_FAR = 105
    
    // ============== Terrain Colors ==============
    // NOTE: Lazy initialization prevents unit test failures when only math functions are used
    
    val HILL_BASE_COLOR: Int by lazy { Color.rgb(30, 80, 40) }
    val TREE_BASE_COLOR: Int by lazy { Color.rgb(20, 60, 30) }
    
    // ============== Weather Effect Constants ==============
    
    /** Storm cloud Y positions (fraction of screen height, can be negative) */
    const val STORM_CLOUD_Y1 = -0.05f  // Back layer, partially above screen
    const val STORM_CLOUD_Y2 = 0.05f   // Middle layer
    const val STORM_CLOUD_Y3 = 0.15f   // Front layer
    
    /** Storm cloud colors (ARGB) - lazy to avoid Android dependency in tests */
    val STORM_CLOUD_COLOR_BACK: Int by lazy { Color.argb(180, 30, 35, 45) }
    val STORM_CLOUD_COLOR_MID: Int by lazy { Color.argb(160, 40, 45, 55) }
    val STORM_CLOUD_COLOR_FRONT: Int by lazy { Color.argb(140, 50, 55, 65) }
    
    /** Cloud size multipliers */
    const val STORM_CLOUD_SIZE_BACK = 0.6f
    const val STORM_CLOUD_SIZE_MID = 0.7f
    const val STORM_CLOUD_SIZE_FRONT = 0.8f
    const val STORM_CLOUD_BASE_SIZE = 0.30f  // As fraction of screen width
    
    /** Fog colors (RGB) */
    const val FOG_R = 210
    const val FOG_G = 215
    const val FOG_B = 225
    
    // ============== Particle Pool Sizes ==============
    
    const val RAIN_PARTICLE_POOL_SIZE = 300
    const val SNOW_PARTICLE_POOL_SIZE = 200
    const val FIREWORK_PARTICLE_POOL_SIZE = 200
    const val STAR_COUNT = 380  // Isotropic star field overflows all edges; more stars keep on-screen density
    
    // ============== Animation Speeds ==============
    
    /** Wind effect on cloud speed multiplier */
    const val WIND_CLOUD_SPEED_MIN = 0.3f   // Light breeze cloud drift
    const val WIND_CLOUD_SPEED_MAX = 12.0f  // Dramatic storm cloud speed
    
    /** Tree sway animation parameters */
    const val TREE_SWAY_FREQUENCY = 0.6f   // Base frequency (slower, more natural)
    const val TREE_SWAY_AMPLITUDE = 0.20f  // User multiplier scales this
    
    /** Gust effect multiplier for tree lean */
    const val GUST_LEAN_FACTOR = 0.25f
    
    /** Cloud movement speed multiplier (base delta-time scale) */
    const val CLOUD_MOVEMENT_SCALE = 0.06f
    
    /** Lightning timing (milliseconds) */
    const val LIGHTNING_INTERVAL_MIN_MS = 1500L    // Minimum time between flashes (1.5s)
    const val LIGHTNING_INTERVAL_RANGE_MS = 2500L  // Random range added (1.5-4.0s total)
    const val LIGHTNING_FLASH_DURATION_MS = 70L    // Flash visibility duration
    
    // ============== Terrain Generation Constants ==============
    
    /** Hill curve generation */
    const val HILL_CURVE_POINTS_MIN = 80
    const val HILL_CURVE_POINTS_RANGE = 40
    const val HILL_BASE_HEIGHT = 0.35f     // Base position (higher = hills start lower on screen)
    const val HILL_HEIGHT_RANGE = 0.4f     // Max variation from base (higher = taller hills)
    const val HILL_SMOOTHING_FACTOR = 0.15f  // Max change between adjacent points
    
    /** Tree generation */
    const val TREE_SCALE_MIN = 0.45f
    const val TREE_SCALE_RANGE = 0.55f
    const val TREE_HEIGHT_FACTOR = 0.15f  // Tree height as fraction of screen
    const val TREE_GROUND_OFFSET = 0.01f  // Slight offset above hill surface
    
    /** Mountain generation */
    const val MOUNTAIN_HEIGHT_MIN = 0.3f
    const val MOUNTAIN_HEIGHT_RANGE = 0.3f
    const val MOUNTAIN_WIDTH_MIN = 0.8f
    const val MOUNTAIN_WIDTH_RANGE = 0.6f
    const val MOUNTAIN_PEAK_SPREAD = 1.4f  // How spread out peaks are horizontally
    
    /** Cloud generation */
    const val CLOUD_Y_MIN = 0.05f
    const val CLOUD_Y_RANGE = 0.35f
    const val CLOUD_SIZE_MIN = 0.08f
    const val CLOUD_SIZE_RANGE = 0.25f
    const val CLOUD_SPEED_BASE = 0.15f
    const val CLOUD_SPEED_RANGE = 0.25f
    
    // ============== Atmospheric Haze ==============
    
    /** Where haze starts and ends (fraction of screen height) */
    const val HAZE_START_Y = 0.55f
    const val HAZE_END_Y = 0.85f
    const val HAZE_ALPHA = 25
    
    // ============== Seasonal Event Constants ==============
    
    /** Christmas star position (fraction of screen) */
    const val CHRISTMAS_STAR_X = 0.82f
    const val CHRISTMAS_STAR_Y = 0.12f
    const val CHRISTMAS_STAR_SIZE = 35f
    
    /** Trail angle (degrees from horizontal, down-left) */
    const val CHRISTMAS_STAR_TRAIL_ANGLE = 225.0
    
    // ============== Random Event Positioning ==============
    // Y position ranges as fraction of screen height
    
    /** Sky events (airplanes, birds, eagles, balloons, shooting stars) */
    const val EVENT_SKY_Y_MIN = 0.08f
    const val EVENT_SKY_Y_MAX = 0.40f
    
    /** High sky events (balloons) */
    const val EVENT_HIGH_SKY_Y_MIN = 0.10f
    const val EVENT_HIGH_SKY_Y_MAX = 0.30f
    
    /** Ground events (rabbits, foxes) */
    const val EVENT_GROUND_Y_MIN = 0.82f
    const val EVENT_GROUND_Y_MAX = 0.92f
    
    /** Low sky events (butterflies, fireflies, owls) */
    const val EVENT_LOW_SKY_Y_MIN = 0.50f
    const val EVENT_LOW_SKY_Y_MAX = 0.75f
    
    /** Event durations (milliseconds) */
    const val EVENT_DURATION_AIRPLANE = 25000L
    const val EVENT_DURATION_BIRD_FLOCK = 12000L
    const val EVENT_DURATION_EAGLE = 18000L
    const val EVENT_DURATION_SHOOTING_STAR = 8000L  // Reference only - actual is calculated (longer graceful arc)
    const val EVENT_DURATION_BALLOON = 40000L
    const val EVENT_DURATION_BUTTERFLY = 15000L
    const val EVENT_DURATION_RABBIT = 8000L
    const val EVENT_DURATION_FOX = 12000L
    const val EVENT_DURATION_FIREFLY = 10000L
    const val EVENT_DURATION_OWL = 15000L
    const val EVENT_DURATION_ALIEN = 20000L
    const val EVENT_DURATION_SQUIRREL = 10000L
    const val EVENT_DURATION_DRAGONFLY = 10000L
    const val EVENT_DURATION_SNOWFLAKE = 20000L
    const val EVENT_DURATION_BAT = 13000L
    const val EVENT_DURATION_DEER = 15000L
    
    // ============== Utility Functions ==============
    
    /**
     * Linearly interpolate between two colors in RGB space.
     * For sky transitions, prefer lerpColorHSV for smoother results.
     */
    fun lerpColor(start: Int, end: Int, ratio: Float): Int {
        val r = ((Color.red(start) + ratio * (Color.red(end) - Color.red(start))).toInt()).coerceIn(0, 255)
        val g = ((Color.green(start) + ratio * (Color.green(end) - Color.green(start))).toInt()).coerceIn(0, 255)
        val b = ((Color.blue(start) + ratio * (Color.blue(end) - Color.blue(start))).toInt()).coerceIn(0, 255)
        return Color.rgb(r, g, b)
    }

    /** Diffuse light under a closed cloud deck: colours lose saturation and get a little darker. */
    fun applyOvercastLight(color: Int, dullness: Float): Int {
        if (dullness <= 0.001f) return color
        val r = Color.red(color)
        val g = Color.green(color)
        val b = Color.blue(color)
        val lum = 0.3f * r + 0.59f * g + 0.11f * b
        val desat = 0.45f * dullness
        val dark = 1f - 0.2f * dullness
        return Color.rgb(
            ((r + (lum - r) * desat) * dark).toInt().coerceIn(0, 255),
            ((g + (lum - g) * desat) * dark).toInt().coerceIn(0, 255),
            ((b + (lum - b) * desat) * dark).toInt().coerceIn(0, 255)
        )
    }

    /** 0 by day and through the first half of twilight, 1 in full night. */
    fun nightFactor(lightLevel: Float): Float {
        val t = (1f - (lightLevel - 0.15f) / 0.35f).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    /** Scotopic (rod) vision: colours fade toward a cool blue-grey as night falls. */
    fun applyNightLight(color: Int, night: Float): Int {
        if (night <= 0.001f) return color
        val r = Color.red(color)
        val g = Color.green(color)
        val b = Color.blue(color)
        val lum = 0.3f * r + 0.59f * g + 0.11f * b
        val desat = 0.6f * night
        val shift = 0.15f * night
        return Color.rgb(
            ((r + (lum - r) * desat) * (1f - shift)).toInt().coerceIn(0, 255),
            ((g + (lum - g) * desat) * (1f - shift * 0.4f)).toInt().coerceIn(0, 255),
            (b + (lum - b) * desat + lum * shift).toInt().coerceIn(0, 255)
        )
    }

    /** Landscape colour under the current light: aerial haze, overcast flattening, night vision. */
    fun sceneLight(base: Int, ambientColor: Int, haze: Float, dullness: Float, night: Float): Int =
        applyNightLight(applyOvercastLight(blendWithAmbient(base, ambientColor, haze), dullness), night)

    /** Morning and evening golden window, shared by sky, ambient light and clouds. */
    const val TWILIGHT_WINDOW_H = 1.0f

    /**
     * Shared time-of-day colour timeline: night until 1h before sunrise, dawn at sunrise,
     * day from 1h after sunrise to 1h before sunset, dusk at sunset, night 1h after it.
     * [mix] blends two stage colours with a linear 0-1 progress (callers apply their own easing).
     */
    inline fun twilightColor(
        hour: Float, sunrise: Float, sunset: Float,
        night: Int, dawn: Int, day: Int, dusk: Int,
        mix: (Int, Int, Float) -> Int
    ): Int {
        val w = TWILIGHT_WINDOW_H
        return when {
            hour < sunrise - w -> night
            hour < sunrise -> mix(night, dawn, (hour - (sunrise - w)) / w)
            hour < sunrise + w -> mix(dawn, day, (hour - sunrise) / w)
            hour < sunset - w -> day
            hour < sunset -> mix(day, dusk, (hour - (sunset - w)) / w)
            hour < sunset + w -> mix(dusk, night, (hour - sunset) / w)
            else -> night
        }
    }
    
    /**
     * Interpolate between two colors in HSV space for smoother sky transitions.
     * HSV avoids the "gray murky transition" problem that RGB has.
     * Uses thread-local cached arrays to avoid per-call allocations.
     */
    private val tlStartHsv = ThreadLocal.withInitial { FloatArray(3) }
    private val tlEndHsv = ThreadLocal.withInitial { FloatArray(3) }
    private val tlResultHsv = ThreadLocal.withInitial { FloatArray(3) }

    fun lerpColorHSV(start: Int, end: Int, ratio: Float): Int {
        val startHsv = tlStartHsv.get()!!
        val endHsv = tlEndHsv.get()!!
        val resultHsv = tlResultHsv.get()!!
        Color.colorToHSV(start, startHsv)
        Color.colorToHSV(end, endHsv)
        
        // Handle hue wrapping (e.g., 350° to 10° should go through 0°, not 180°)
        var hueDiff = endHsv[0] - startHsv[0]
        if (hueDiff > 180f) hueDiff -= 360f
        if (hueDiff < -180f) hueDiff += 360f
        
        resultHsv[0] = (startHsv[0] + hueDiff * ratio + 360f) % 360f
        resultHsv[1] = startHsv[1] + (endHsv[1] - startHsv[1]) * ratio
        resultHsv[2] = startHsv[2] + (endHsv[2] - startHsv[2]) * ratio
        
        return Color.HSVToColor(resultHsv)
    }
    
    /**
     * Ambient blend amount for an object at [distance] (0 = foreground, 1 = background):
     * 5% in front up to 45% far away, low enough to keep foreground greens.
     */
    fun depthHaze(distance: Float): Float = 0.05f + distance * 0.4f

    /** Rank of a hill band among [count] depth-sorted bands: 0 = farthest, 1 = nearest. */
    fun bandNearness(index: Int, count: Int): Float =
        if (count <= 1) 1f else (index.toFloat() / (count - 1)).coerceIn(0f, 1f)

    /**
     * Aerial-perspective blend toward the ambient colour for a hill band and everything on it.
     * Far bands fade toward the sky, near bands keep their colour; at night every band
     * gets at least [NIGHT_HAZE_FLOOR] so vegetation sinks into the night tint.
     */
    fun bandHaze(nearness: Float, lightLevel: Float): Float {
        val distance = 1f - nearness.coerceIn(0f, 1f)
        // Curved so mid bands keep their colour and only the farthest ones fade strongly
        val haze = BAND_HAZE_NEAR + distance * sqrt(distance) * (BAND_HAZE_FAR - BAND_HAZE_NEAR)
        val dayFactor = ((lightLevel - 0.15f) / 0.85f).coerceIn(0f, 1f)
        return maxOf(haze, (1f - dayFactor) * NIGHT_HAZE_FLOOR)
    }

    private const val BAND_HAZE_NEAR = 0.04f
    private const val BAND_HAZE_FAR = 0.4f
    private const val NIGHT_HAZE_FLOOR = 0.35f
    
    /**
     * Blend a color with the ambient atmosphere color.
     * Implements the global tint system for atmospheric perspective.
     * 
     * @param baseColor The object's base/local color
     * @param ambientColor The current ambient light color (varies by time of day)
     * @param blendAmount How much to blend (0.0 = pure base, 1.0 = pure ambient)
     */
    fun blendWithAmbient(baseColor: Int, ambientColor: Int, blendAmount: Float): Int {
        val amount = blendAmount.coerceIn(0f, 1f)
        val r = (Color.red(baseColor) * (1 - amount) + Color.red(ambientColor) * amount).toInt()
        val g = (Color.green(baseColor) * (1 - amount) + Color.green(ambientColor) * amount).toInt()
        val b = (Color.blue(baseColor) * (1 - amount) + Color.blue(ambientColor) * amount).toInt()
        return Color.rgb(r.coerceIn(0, 255), g.coerceIn(0, 255), b.coerceIn(0, 255))
    }
    
    /**
     * Get the ambient color for a given time of day.
     * Uses the shared [twilightColor] timeline when sunriseHours/sunsetHours are provided (non-zero).
     */
    fun getAmbientColor(
        timeOfDayHours: Float,
        isNight: Boolean,
        isDawn: Boolean,
        isDusk: Boolean,
        nightShadeStrength: Float = 0f,
        sunriseHours: Float = 0f,
        sunsetHours: Float = 0f
    ): Int {
        val base = if (sunriseHours > 0f && sunsetHours > 0f) {
            twilightColor(
                timeOfDayHours, sunriseHours, sunsetHours,
                AMBIENT_NIGHT, AMBIENT_DAWN, AMBIENT_DAY, AMBIENT_DUSK
            ) { from, to, t -> lerpColorRGB(from, to, easeInOutCubic(t)) }
        } else {
            // Fallback when sunrise/sunset are unknown: boolean-based
            when {
                isNight -> AMBIENT_NIGHT
                isDawn -> AMBIENT_DAWN
                isDusk -> AMBIENT_DUSK
                else -> AMBIENT_DAY
            }
        }

        // Apply night shade continuously using time-based night factor
        if (nightShadeStrength > 0f) {
            val nightFactor = if (sunriseHours > 0f && sunsetHours > 0f) {
                val h = timeOfDayHours
                val dawnStart = sunriseHours - 1.0f
                val duskEnd = sunsetHours + 1.0f
                when {
                    h >= dawnStart && h <= sunriseHours -> {
                        val t = ((h - dawnStart) / 1.0f).coerceIn(0f, 1f)
                        1f - t * t * (3f - 2f * t)
                    }
                    h >= sunsetHours && h <= duskEnd -> {
                        val t = ((h - sunsetHours) / 1.0f).coerceIn(0f, 1f)
                        t * t * (3f - 2f * t)
                    }
                    h >= sunriseHours && h <= sunsetHours -> 0f
                    else -> 1f
                }
            } else {
                if (isNight) 1f else 0f
            }
            val effectiveShade = nightShadeStrength * nightFactor
            return if (effectiveShade > 0.001f) ColorMath.shadeRgb(base, effectiveShade) else base
        }
        return base
    }
    
    /**
     * Linear RGB color interpolation. Delegates to [lerpColor].
     */
    fun lerpColorRGB(from: Int, to: Int, t: Float): Int = lerpColor(from, to, t)
    
    /**
     * Ease-in-out cubic function for smooth transitions.
     */
    fun easeInOutCubic(t: Float): Float {
        val t2 = t * t
        val t3 = t2 * t
        return if (t < 0.5f) {
            4f * t3
        } else {
            1f - (-2f * t + 2f).let { it * it * it } / 2f
        }
    }
    
    /**
     * Smooth color transition with easing using HSV interpolation.
     * Uses HSV for sky transitions to avoid murky grays.
     */
    fun easeSkyColorTransition(fromColor: Int, toColor: Int, progress: Float): Int {
        val eased = easeInOutCubic(progress.coerceIn(0f, 1f))
        return lerpColorHSV(fromColor, toColor, eased)
    }
    
    /**
     * Smooth 3-stop color interpolation with easing using HSV.
     */
    fun lerpColorSmooth(start: Int, mid: Int, end: Int, progress: Float): Int {
        return if (progress < 0.5f) {
            easeSkyColorTransition(start, mid, progress * 2f)
        } else {
            easeSkyColorTransition(mid, end, (progress - 0.5f) * 2f)
        }
    }
}
