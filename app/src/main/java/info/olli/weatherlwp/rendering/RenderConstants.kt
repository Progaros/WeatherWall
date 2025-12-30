package info.olli.weatherlwp.rendering

import android.graphics.Color

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
    
    /** Reference aspect ratio for world-space calculations (9:16 portrait) */
    const val REFERENCE_ASPECT_RATIO = 0.5625f
    
    /** World coordinate bounds for scene elements */
    const val WORLD_MIN_X = -4.0f
    const val WORLD_MAX_X = 5.0f
    const val WORLD_WIDTH = WORLD_MAX_X - WORLD_MIN_X
    
    // ============== Parallax Depth Factors ==============
    // 0.0 = infinitely far (no movement), 1.0 = foreground (max movement)
    // Negative values = opposite movement (creates depth illusion)
    
    /** Sky parallax - moves opposite direction for infinite depth */
    const val PARALLAX_SKY = -0.15f
    
    /** Stars parallax - subtle movement */
    const val PARALLAX_STARS = 0.30f
    
    /** Clouds parallax - far background */
    const val PARALLAX_CLOUDS = 0.08f
    
    /** Mountains parallax range */
    const val PARALLAX_MOUNTAIN_MIN = 0.15f
    const val PARALLAX_MOUNTAIN_MAX = 0.35f
    
    /** Hills/terrain parallax range */
    const val PARALLAX_HILLS_MIN = 0.75f
    const val PARALLAX_HILLS_MAX = 0.95f
    
    // ============== Sky Colors (3-stop gradients: Top, Middle, Horizon) ==============
    // Per ai.md: "Always use a 3-stop LinearGradient for the sky"
    // Horizon should be significantly lighter than top (atmosphere thickness)
    
    // Night: Deep space -> Dark blue -> Slightly lighter horizon
    val SKY_NIGHT_TOP: Int = Color.parseColor("#0B0B1A")      // Deep space
    val SKY_NIGHT_MID: Int = Color.parseColor("#141428")      // Dark blue
    val SKY_NIGHT_BOTTOM: Int = Color.parseColor("#252540")   // Lighter horizon
    
    // Dawn: Orange-purple transition with bright horizon
    val SKY_DAWN_TOP: Int = Color.parseColor("#4A3060")       // Purple-ish
    val SKY_DAWN_MID: Int = Color.parseColor("#FF8855")       // Orange
    val SKY_DAWN_BOTTOM: Int = Color.parseColor("#FFCC88")    // Bright horizon
    
    // Day: Deep blue -> Blue -> Light horizon haze
    val SKY_DAY_TOP: Int = Color.parseColor("#1565C0")        // Deep blue
    val SKY_DAY_MID: Int = Color.parseColor("#42A5F5")        // Bright blue
    val SKY_DAY_BOTTOM: Int = Color.parseColor("#B3E5FC")     // Light horizon
    
    // Dusk: Purple -> Orange -> Bright glow at horizon
    val SKY_DUSK_TOP: Int = Color.parseColor("#4A2060")       // Deep purple
    val SKY_DUSK_MID: Int = Color.parseColor("#FF6B35")       // Orange
    val SKY_DUSK_BOTTOM: Int = Color.parseColor("#FFAB76")    // Bright horizon glow
    
    // Ambient tint colors (for global tinting per ai.md)
    val AMBIENT_NIGHT: Int = Color.parseColor("#2A2040")      // Purple-blue night tint
    val AMBIENT_DAWN: Int = Color.parseColor("#FF9966")       // Warm orange dawn tint
    val AMBIENT_DAY: Int = Color.parseColor("#E8F0F8")        // Very light neutral (nearly white with hint of sky)
    val AMBIENT_DUSK: Int = Color.parseColor("#FF7744")       // Orange-red dusk tint
    
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
    
    val HILL_BASE_COLOR: Int = Color.rgb(30, 80, 40)
    val TREE_BASE_COLOR: Int = Color.rgb(20, 60, 30)
    
    // ============== Weather Effect Constants ==============
    
    /** Storm cloud Y positions (fraction of screen height, can be negative) */
    const val STORM_CLOUD_Y1 = -0.05f  // Back layer, partially above screen
    const val STORM_CLOUD_Y2 = 0.05f   // Middle layer
    const val STORM_CLOUD_Y3 = 0.15f   // Front layer
    
    /** Storm cloud colors (ARGB) */
    val STORM_CLOUD_COLOR_BACK: Int = Color.argb(180, 30, 35, 45)
    val STORM_CLOUD_COLOR_MID: Int = Color.argb(160, 40, 45, 55)
    val STORM_CLOUD_COLOR_FRONT: Int = Color.argb(140, 50, 55, 65)
    
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
    const val FIREWORK_PARTICLE_POOL_SIZE = 100
    const val STAR_COUNT = 100
    
    // ============== Animation Speeds ==============
    
    /** Wind effect on cloud speed multiplier */
    const val WIND_CLOUD_SPEED_MIN = 0.2f
    const val WIND_CLOUD_SPEED_MAX = 8.0f   // Increased for more dramatic high wind
    
    /** Tree sway animation parameters */
    const val TREE_SWAY_FREQUENCY = 0.6f   // Base frequency (slower, more natural)
    const val TREE_SWAY_AMPLITUDE = 0.25f  // Increased for more visible sway at high wind
    
    /** Star twinkle animation */
    const val STAR_TWINKLE_SPEED = 0.001f
    
    // ============== Terrain Generation Constants ==============
    
    /** Hill curve generation */
    const val HILL_CURVE_POINTS_MIN = 80
    const val HILL_CURVE_POINTS_RANGE = 40
    const val HILL_BASE_HEIGHT = 0.3f
    const val HILL_HEIGHT_RANGE = 0.2f
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
     * Per ai.md: "Always interpolate sky colours in HSV (or Oklab)"
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
     * Per ai.md: "Everything is tinted by the sky color" 
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
     * Implements the "Global Tint" system from ai.md.
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
     */
    @Suppress("UNUSED_PARAMETER")
    fun getAmbientColor(timeOfDayHours: Float, isNight: Boolean, isDawn: Boolean, isDusk: Boolean): Int {
        return when {
            isNight -> AMBIENT_NIGHT
            isDawn -> AMBIENT_DAWN
            isDusk -> AMBIENT_DUSK
            else -> AMBIENT_DAY
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
     * Per ai.md: Use HSV for sky transitions to avoid murky grays.
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
