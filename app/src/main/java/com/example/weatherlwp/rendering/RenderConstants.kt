package com.example.weatherlwp.rendering

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
    const val PARALLAX_MOUNTAIN_MIN = 0.25f
    const val PARALLAX_MOUNTAIN_MAX = 0.45f
    
    /** Hills/terrain parallax range */
    const val PARALLAX_HILLS_MIN = 0.50f
    const val PARALLAX_HILLS_MAX = 1.0f
    
    // ============== Sky Colors ==============
    
    val SKY_NIGHT_TOP: Int = Color.parseColor("#0B1026")
    val SKY_NIGHT_BOTTOM: Int = Color.parseColor("#1A1A3A")
    
    val SKY_DAWN_TOP: Int = Color.parseColor("#FF8C42")
    val SKY_DAWN_BOTTOM: Int = Color.parseColor("#FFD166")
    
    val SKY_DAY_TOP: Int = Color.parseColor("#1E90FF")
    val SKY_DAY_BOTTOM: Int = Color.parseColor("#87CEEB")
    
    val SKY_DUSK_TOP: Int = Color.parseColor("#FF6B35")
    val SKY_DUSK_BOTTOM: Int = Color.parseColor("#FFB347")
    
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
    const val WIND_CLOUD_SPEED_MIN = 0.5f
    const val WIND_CLOUD_SPEED_MAX = 2.5f
    
    /** Tree sway animation parameters */
    const val TREE_SWAY_FREQUENCY = 2.0f
    const val TREE_SWAY_AMPLITUDE = 0.05f
    
    /** Star twinkle animation */
    const val STAR_TWINKLE_SPEED = 0.001f
    
    // ============== Seasonal Event Constants ==============
    
    /** Christmas star position (fraction of screen) */
    const val CHRISTMAS_STAR_X = 0.82f
    const val CHRISTMAS_STAR_Y = 0.12f
    const val CHRISTMAS_STAR_SIZE = 35f
    
    /** Trail angle (degrees from horizontal, down-left) */
    const val CHRISTMAS_STAR_TRAIL_ANGLE = 225.0
    
    // ============== Utility Functions ==============
    
    /**
     * Linearly interpolate between two colors.
     */
    fun lerpColor(start: Int, end: Int, ratio: Float): Int {
        val r = ((Color.red(start) + ratio * (Color.red(end) - Color.red(start))).toInt()).coerceIn(0, 255)
        val g = ((Color.green(start) + ratio * (Color.green(end) - Color.green(start))).toInt()).coerceIn(0, 255)
        val b = ((Color.blue(start) + ratio * (Color.blue(end) - Color.blue(start))).toInt()).coerceIn(0, 255)
        return Color.rgb(r, g, b)
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
}
