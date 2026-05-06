package info.olli.weatherlwp.rendering

import android.graphics.Color
import info.olli.weatherlwp.util.ColorMath

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
     * Calculate height scale for the current screen.
     * Elements sized relative to reference height should be multiplied by this.
     * 
     * @param screenHeight The actual screen height
     * @return Scale factor (1.0 at 1920px, 0.5 at 960px, clamped to MIN_HEIGHT_SCALE)
     */
    fun getHeightScale(screenHeight: Float): Float {
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
    
    /**
     * Calculate the extended canvas width.
     * Content (mountains, hills, trees, events) should be generated for this width.
     *
     * Uses height-proportional scaling to maintain uniform world appearance:
     * horizontal spacing scales with height, so the scene looks proportionally
     * the same at any aspect ratio. Falls back to screen-width-based sizing
     * when the canvas is wider than the reference aspect ratio predicts (landscape).
     *
     * @param screenWidth The actual visible screen width
     * @param screenHeight The actual visible screen height
     * @return The extended width for content generation and positioning
     */
    fun getExtendedWidth(screenWidth: Float, screenHeight: Float): Float {
        // Height-proportional: at REFERENCE_HEIGHT, gives the reference extended width.
        val heightScale = (screenHeight / REFERENCE_HEIGHT).coerceAtLeast(MIN_HEIGHT_SCALE)
        val referenceExtendedWidth = REFERENCE_HEIGHT * REFERENCE_ASPECT_RATIO * (1f + 2f * EXTENDED_SIDE_MARGIN_RATIO)
        val heightBasedExtended = referenceExtendedWidth * heightScale
        // Screen-based minimum: extended width must at least cover the screen + margins
        val screenBasedExtended = screenWidth + screenWidth * EXTENDED_SIDE_MARGIN_RATIO * 2f
        return maxOf(heightBasedExtended, screenBasedExtended)
    }

    /**
     * Scale factor for count-based object density using horizontal span only.
     * Accounts for aspect ratio: wider views (shorter canvas) get proportionally
     * more objects so the scene doesn't look empty with tiny elements.
     */
    fun getHorizontalDensityScale(extendedWidth: Float, screenWidth: Float = 0f, screenHeight: Float = 0f): Float {
        val referenceWidth = REFERENCE_HEIGHT * REFERENCE_ASPECT_RATIO
        val referenceExtendedWidth = referenceWidth * (1f + 2f * EXTENDED_SIDE_MARGIN_RATIO)
        val baseScale = (extendedWidth / referenceExtendedWidth).coerceAtLeast(0.25f)
        
        // When aspect ratio is wider than reference, boost density so
        // the wider visible area is filled with proportionally more objects.
        if (screenWidth > 0f && screenHeight > 0f) {
            val referenceAspect = REFERENCE_ASPECT_RATIO  // 0.5625 (9:16)
            val currentAspect = screenWidth / screenHeight
            val aspectBoost = (currentAspect / referenceAspect).coerceIn(1f, 3f)
            return baseScale * aspectBoost
        }
        return baseScale
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
    
    /** World coordinate bounds for scene elements (deprecated - use extended width system) */
    const val WORLD_MIN_X = -4.0f
    const val WORLD_MAX_X = 5.0f
    const val WORLD_WIDTH = WORLD_MAX_X - WORLD_MIN_X
    
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
    val SKY_NIGHT_TOP: Int by lazy { Color.parseColor("#0B0D1E") }      // Deep space (hint of blue)
    val SKY_NIGHT_MID: Int by lazy { Color.parseColor("#151830") }      // Dark navy blue
    val SKY_NIGHT_BOTTOM: Int by lazy { Color.parseColor("#252848") }   // Warmer blue at horizon
    
    // Dawn: Navy-blue transitioning through muted coral to warm golden horizon
    // Dawn colors are slightly less vivid than dusk per atmospheric science
    // (morning air is cleaner -> less Rayleigh scattering -> softer sunrise colors)
    val SKY_DAWN_TOP: Int by lazy { Color.parseColor("#354880") }       // Brighter navy-blue (more light reaching)
    val SKY_DAWN_MID: Int by lazy { Color.parseColor("#D08868") }       // Warm coral-salmon (slightly more vivid)
    val SKY_DAWN_BOTTOM: Int by lazy { Color.parseColor("#F0B880") }    // Warm golden-orange horizon
    
    // Day: Clear blue -> Blue -> Light horizon haze
    // Lighter/paler than Material Design blues for photorealistic sky
    val SKY_DAY_TOP: Int by lazy { Color.parseColor("#2E80D0") }        // Clear sky blue (less saturated)
    val SKY_DAY_MID: Int by lazy { Color.parseColor("#60B8F5") }        // Bright sky blue
    val SKY_DAY_BOTTOM: Int by lazy { Color.parseColor("#D0EFFF") }     // Pale hazy horizon
    
    // Dusk: Deep purple-blue -> Warm terracotta -> Soft golden-orange horizon
    // Dusk is more vivid than dawn (NOAA: evening air contains more particles
    // from daytime convection -> enhanced Rayleigh scattering)
    val SKY_DUSK_TOP: Int by lazy { Color.parseColor("#2E2850") }       // Rich purple-blue
    val SKY_DUSK_MID: Int by lazy { Color.parseColor("#D05838") }       // Vivid terracotta-orange
    val SKY_DUSK_BOTTOM: Int by lazy { Color.parseColor("#E89050") }    // Warm deep golden-orange horizon
    
    // Ambient tint colors for global tinting (more muted for realism)
    val AMBIENT_NIGHT: Int by lazy { Color.parseColor("#2A2040") }      // Purple-blue night tint
    val AMBIENT_DAWN: Int by lazy { Color.parseColor("#E89070") }       // Muted warm salmon dawn tint
    val AMBIENT_DAY: Int by lazy { Color.parseColor("#E8F0F8") }        // Very light neutral (nearly white with hint of sky)
    val AMBIENT_DUSK: Int by lazy { Color.parseColor("#D06040") }       // Warm terracotta dusk tint
    
    // ============== Time Thresholds (hours) ==============
    // These are fallback defaults. SkyLayer uses dynamic sunrise/sunset from weather data.
    
    const val TIME_NIGHT_END = 5.5f
    const val TIME_DAWN_START = 5.5f
    const val TIME_DAWN_END = 7.0f
    const val TIME_DAY_END = 17.5f
    const val TIME_DUSK_END = 20.0f
    
    // ============== Mountain Colors ==============
    
    /** Base mountain gray (RGB values) - far mountains are darker/bluer */
    const val MOUNTAIN_GRAY_MIN = 40
    const val MOUNTAIN_GRAY_MAX = 100
    
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
    const val STAR_COUNT = 100
    
    // ============== Animation Speeds ==============
    
    /** Wind effect on cloud speed multiplier */
    const val WIND_CLOUD_SPEED_MIN = 0.3f   // Light breeze cloud drift
    const val WIND_CLOUD_SPEED_MAX = 12.0f  // Dramatic storm cloud speed
    
    /** Tree sway animation parameters */
    const val TREE_SWAY_FREQUENCY = 0.6f   // Base frequency (slower, more natural)
    const val TREE_SWAY_AMPLITUDE = 0.20f  // Reduced for subtler base sway (user multiplier scales this)
    
    /** Gust effect multiplier for tree lean */
    const val GUST_LEAN_FACTOR = 0.25f     // Moderate gust bending (balance of 0.15 and 0.4)
    
    /** Star twinkle animation */
    const val STAR_TWINKLE_SPEED = 0.001f
    
    /** Cloud movement speed multiplier (base delta-time scale) */
    const val CLOUD_MOVEMENT_SCALE = 0.06f  // Increased for faster cloud movement
    
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
    const val TREE_SCALE_MIN = 0.7f
    const val TREE_SCALE_RANGE = 0.3f
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
    const val CLOUD_SPEED_BASE = 0.15f   // Increased for visible movement
    const val CLOUD_SPEED_RANGE = 0.25f  // More speed variation
    
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
     * Apply night tint to a color (reduce RGB, add blue).
     */
    fun applyNightTint(color: Int, intensity: Float = 1.0f): Int {
        val redMult = 0.6f + (1f - intensity) * 0.4f
        val greenMult = 0.7f + (1f - intensity) * 0.3f
        val blueAdd = (20 * intensity).toInt()
        return Color.rgb(
            (Color.red(color) * redMult).toInt().coerceIn(0, 255),
            (Color.green(color) * greenMult).toInt().coerceIn(0, 255),
            (Color.blue(color) + blueAdd).coerceIn(0, 255)
        )
    }
    
    /**
     * Calculate ambient tint blend amount based on distance/depth.
     * Objects further away blend more with the ambient light color (atmospheric perspective).
     * 
     * @param baseColor The object's base color (reserved for future per-color tint curves)
     * @param ambientColor The ambient/sky color (reserved for future intensity-based scaling)
     * @param distance 0.0 = foreground (less tint), 1.0 = background (more tint)
     * @return Blend amount (0.05 foreground to 0.45 background) for use with [blendWithAmbient]
     */
    @Suppress("UNUSED_PARAMETER")
    fun applyAmbientTint(baseColor: Int, ambientColor: Int, distance: Float): Float {
        // More distant objects blend more with ambient (atmospheric perspective)
        // Foreground: 5% ambient, Background: 45% ambient
        // Reduced from 20-60% to preserve foreground colors, especially tree greens during day
        return 0.05f + distance * 0.4f
    }
    
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
     * Apply atmospheric perspective to a color based on depth/distance.
     * Combines distance-based tint calculation with ambient blending.
     * 
     * Use this for objects at varying depths where atmospheric perspective matters.
     * 
     * @param baseColor The object's base color
     * @param ambientColor The current ambient light color
     * @param distance 0.0 = foreground (less tint), 1.0 = background (more tint)
     * @return The tinted color with atmospheric perspective applied
     */
    fun applyAtmosphericPerspective(baseColor: Int, ambientColor: Int, distance: Float): Int {
        val tintAmount = applyAmbientTint(baseColor, ambientColor, distance)
        return blendWithAmbient(baseColor, ambientColor, tintAmount)
    }
    
    /**
     * Get the ambient color for a given time of day.
     * Uses smooth interpolation when sunriseHours/sunsetHours are provided (non-zero).
     * 
     * Timeline (e.g. sunrise=6, sunset=20):
     * - Night: after dusk transition, before dawn transition
     * - Pre-dawn: sunrise-1h .. sunrise  → lerp(NIGHT, DAWN)
     * - Dawn→Day: sunrise .. sunrise+0.5h → lerp(DAWN, DAY)
     * - Day: sunrise+0.5h .. sunset-1.5h
     * - Day→Dusk: sunset-1.5h .. sunset  → lerp(DAY, DUSK)
     * - Post-dusk: sunset .. sunset+1h    → lerp(DUSK, NIGHT)
     */
    @Suppress("UNUSED_PARAMETER")
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
            // Smooth time-based interpolation
            val hour = timeOfDayHours
            val dawnTransStart = sunriseHours - 1.0f        // Night→Dawn starts
            val dawnTransEnd = sunriseHours                  // Night→Dawn ends (= Dawn peak)
            val dawnToDayEnd = sunriseHours + 0.5f           // Dawn→Day ends
            val dayToDuskStart = sunsetHours - 1.5f          // Day→Dusk starts
            val duskTransStart = sunsetHours                 // Dusk peak
            val duskTransEnd = sunsetHours + 1.0f            // Dusk→Night ends

            when {
                // Pre-dawn: Night → Dawn
                hour >= dawnTransStart && hour < dawnTransEnd -> {
                    val t = easeInOutCubic(((hour - dawnTransStart) / (dawnTransEnd - dawnTransStart)).coerceIn(0f, 1f))
                    lerpColorRGB(AMBIENT_NIGHT, AMBIENT_DAWN, t)
                }
                // Dawn → Day
                hour >= dawnTransEnd && hour < dawnToDayEnd -> {
                    val t = easeInOutCubic(((hour - dawnTransEnd) / (dawnToDayEnd - dawnTransEnd)).coerceIn(0f, 1f))
                    lerpColorRGB(AMBIENT_DAWN, AMBIENT_DAY, t)
                }
                // Day
                hour >= dawnToDayEnd && hour < dayToDuskStart -> AMBIENT_DAY
                // Day → Dusk
                hour >= dayToDuskStart && hour < duskTransStart -> {
                    val t = easeInOutCubic(((hour - dayToDuskStart) / (duskTransStart - dayToDuskStart)).coerceIn(0f, 1f))
                    lerpColorRGB(AMBIENT_DAY, AMBIENT_DUSK, t)
                }
                // Post-dusk: Dusk → Night
                hour >= duskTransStart && hour < duskTransEnd -> {
                    val t = easeInOutCubic(((hour - duskTransStart) / (duskTransEnd - duskTransStart)).coerceIn(0f, 1f))
                    lerpColorRGB(AMBIENT_DUSK, AMBIENT_NIGHT, t)
                }
                // Night
                else -> AMBIENT_NIGHT
            }
        } else {
            // Fallback: boolean-based (legacy callers)
            when {
                isNight -> AMBIENT_NIGHT
                isDawn -> AMBIENT_DAWN
                isDusk -> AMBIENT_DUSK
                else -> AMBIENT_DAY
            }
        }

        return if (isNight && nightShadeStrength > 0f) {
            ColorMath.shadeRgb(base, nightShadeStrength)
        } else {
            base
        }
    }
    
    /**
     * Linear RGB color interpolation.
     */
    fun lerpColorRGB(from: Int, to: Int, t: Float): Int {
        val r = (Color.red(from) + (Color.red(to) - Color.red(from)) * t).toInt().coerceIn(0, 255)
        val g = (Color.green(from) + (Color.green(to) - Color.green(from)) * t).toInt().coerceIn(0, 255)
        val b = (Color.blue(from) + (Color.blue(to) - Color.blue(from)) * t).toInt().coerceIn(0, 255)
        return Color.rgb(r, g, b)
    }
    
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
