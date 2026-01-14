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
     * Extended canvas width multiplier.
     * Content is generated for a virtual canvas that is wider than the visible screen.
     * This allows parallax to reveal content beyond the visible edges.
     * 
     * Extended width = max(screen width, screen height * EXTENDED_WIDTH_MULTIPLIER)
     * In portrait: 2x height gives sufficient horizontal content for parallax
     * In landscape: Uses actual width since it's already wide
     * 
     * Note: Reduced from 4.0 to 2.0 for battery efficiency - fewer trees/hills to generate and draw.
     */
    const val EXTENDED_WIDTH_MULTIPLIER = 2.0f
    
    /**
     * Calculate the extended canvas width.
     * Content (mountains, hills, trees, events) should be generated for this width.
     * 
     * @param screenWidth The actual visible screen width
     * @param screenHeight The actual visible screen height
     * @return The extended width for content generation
     */
    fun getExtendedWidth(screenWidth: Float, screenHeight: Float): Float {
        // Extended width = height * multiplier, but at least actual screen width
        return maxOf(screenWidth, screenHeight * EXTENDED_WIDTH_MULTIPLIER)
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
    
    // Night: Deep space -> Dark blue -> Slightly lighter horizon
    val SKY_NIGHT_TOP: Int by lazy { Color.parseColor("#0B0B1A") }      // Deep space
    val SKY_NIGHT_MID: Int by lazy { Color.parseColor("#141428") }      // Dark blue
    val SKY_NIGHT_BOTTOM: Int by lazy { Color.parseColor("#252540") }   // Lighter horizon
    
    // Dawn: Orange-purple transition with bright horizon
    val SKY_DAWN_TOP: Int by lazy { Color.parseColor("#4A3060") }       // Purple-ish
    val SKY_DAWN_MID: Int by lazy { Color.parseColor("#FF8855") }       // Orange
    val SKY_DAWN_BOTTOM: Int by lazy { Color.parseColor("#FFCC88") }    // Bright horizon
    
    // Day: Deep blue -> Blue -> Light horizon haze
    val SKY_DAY_TOP: Int by lazy { Color.parseColor("#1565C0") }        // Deep blue
    val SKY_DAY_MID: Int by lazy { Color.parseColor("#42A5F5") }        // Bright blue
    val SKY_DAY_BOTTOM: Int by lazy { Color.parseColor("#B3E5FC") }     // Light horizon
    
    // Dusk: Purple -> Orange -> Bright glow at horizon
    val SKY_DUSK_TOP: Int by lazy { Color.parseColor("#4A2060") }       // Deep purple
    val SKY_DUSK_MID: Int by lazy { Color.parseColor("#FF6B35") }       // Orange
    val SKY_DUSK_BOTTOM: Int by lazy { Color.parseColor("#FFAB76") }    // Bright horizon glow
    
    // Ambient tint colors for global tinting
    val AMBIENT_NIGHT: Int by lazy { Color.parseColor("#2A2040") }      // Purple-blue night tint
    val AMBIENT_DAWN: Int by lazy { Color.parseColor("#FF9966") }       // Warm orange dawn tint
    val AMBIENT_DAY: Int by lazy { Color.parseColor("#E8F0F8") }        // Very light neutral (nearly white with hint of sky)
    val AMBIENT_DUSK: Int by lazy { Color.parseColor("#FF7744") }       // Orange-red dusk tint
    
    // ============== Time Thresholds (hours) ==============
    
    const val TIME_NIGHT_END = 5.0f
    const val TIME_DAWN_START = 5.0f
    const val TIME_DAWN_END = 7.5f
    const val TIME_DAY_END = 17.0f
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
     */
    fun lerpColorHSV(start: Int, end: Int, ratio: Float): Int {
        val startHsv = FloatArray(3)
        val endHsv = FloatArray(3)
        Color.colorToHSV(start, startHsv)
        Color.colorToHSV(end, endHsv)
        
        // Handle hue wrapping (e.g., 350° to 10° should go through 0°, not 180°)
        var hueDiff = endHsv[0] - startHsv[0]
        if (hueDiff > 180f) hueDiff -= 360f
        if (hueDiff < -180f) hueDiff += 360f
        
        val h = (startHsv[0] + hueDiff * ratio + 360f) % 360f
        val s = startHsv[1] + (endHsv[1] - startHsv[1]) * ratio
        val v = startHsv[2] + (endHsv[2] - startHsv[2]) * ratio
        
        return Color.HSVToColor(floatArrayOf(h, s, v))
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
     * Apply ambient tint to a color based on time of day.
     * Objects should take on the ambient light color, especially at night/dusk.
     * 
     * @param baseColor The object's base color
     * @param ambientColor The ambient/sky color to blend with
     * @param distance 0.0 = foreground (less tint), 1.0 = background (more tint)
     * @return The tinted color
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
     */
    @Suppress("UNUSED_PARAMETER")
    fun getAmbientColor(
        timeOfDayHours: Float,
        isNight: Boolean,
        isDawn: Boolean,
        isDusk: Boolean,
        nightShadeStrength: Float = 0f
    ): Int {
        val base = when {
            isNight -> AMBIENT_NIGHT
            isDawn -> AMBIENT_DAWN
            isDusk -> AMBIENT_DUSK
            else -> AMBIENT_DAY
        }

        return if (isNight && nightShadeStrength > 0f) {
            ColorMath.shadeRgb(base, nightShadeStrength)
        } else {
            base
        }
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
