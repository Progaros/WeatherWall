package com.example.weatherlwp.parallax

import android.util.Log

/**
 * Calculates parallax offset values with smooth center drift.
 * 
 * This class manages the parallax offset state and provides smooth
 * interpolation back to center when no sensor input is received.
 * 
 * Usage:
 * 1. Call updateFromSensor(x, y) when gyro/accelerometer data arrives
 * 2. Call update(deltaTimeMs) each frame to apply center drift
 * 3. Use getOffset() to get current (x, y) parallax values
 * 4. Use getOffsetForDepth(depth) to get parallax for a specific layer
 */
class ParallaxCalculator {
    companion object {
        private const val TAG = "ParallaxCalculator"
        
        /** How quickly sensor input affects current position (0-1, higher = more responsive) */
        private const val SENSOR_SMOOTHING = 0.10f // Reduced slightly for smoother feel
        
        /** Time to return to center when no input (milliseconds) */
        private const val CENTER_RETURN_TIME_MS = 1000L // Reduced from 3000ms
    }
    
    // Current smoothed parallax offset (what's actually rendered)
    private var currentX = 0f
    private var currentY = 0f
    
    // Target offset from sensor input
    private var targetX = 0f
    private var targetY = 0f
    
    // Last sensor update timestamp
    private var lastSensorUpdateMs = 0L
    
    // User-configurable multipliers
    var strength = 1.0f                 // Sensitivity: how much sensor tilt is amplified
    var maxOffsetPx = 40f               // Absolute max pixel shift for strength=1
    var centerDriftRate = 0.02f         // How fast we recenter when idle
    
    /**
     * Update from sensor input (accelerometer/gyroscope).
     * Values should be normalized to -1..1 range.
     */
    fun updateFromSensor(normalizedX: Float, normalizedY: Float) {
        // Clamp input to avoid extreme jumps
        val clampedX = normalizedX.coerceIn(-1.2f, 1.2f)
        val clampedY = normalizedY.coerceIn(-1.2f, 1.2f)
        
        // If movement is extreme (e.g. > 1.0), dampen it or center it
        // This handles the "if extreme movement center again" requirement
        val damping = if (kotlin.math.abs(clampedX) > 1.0f || kotlin.math.abs(clampedY) > 1.0f) {
            0.5f // Reduce effect for extreme angles
        } else {
            1.0f
        }
        
        targetX = clampedX * maxOffsetPx * strength * damping
        targetY = clampedY * maxOffsetPx * strength * damping
        lastSensorUpdateMs = System.currentTimeMillis()
    }
    
    /**
     * Update parallax state each frame.
     * Call this every frame before rendering.
     * 
     * @param deltaTimeMs Time since last frame in milliseconds
     */
    fun update(deltaTimeMs: Long = 16L) {
        val now = System.currentTimeMillis()
        val timeSinceSensor = now - lastSensorUpdateMs
        
        // Smooth interpolation toward target
        // centerDriftRate also affects responsiveness: higher = snappier movement
        val baseLerp = SENSOR_SMOOTHING * deltaTimeMs / 16f
        val lerpFactor = (baseLerp * (0.5f + centerDriftRate * 5f)).coerceIn(0f, 0.5f)
        currentX += (targetX - currentX) * lerpFactor
        currentY += (targetY - currentY) * lerpFactor
        clampCurrent()
        
        // If no sensor input for a while, drift back to center
        if (timeSinceSensor > 200) {  // Reduced grace period from 500ms to 200ms
            val centerProgress = ((timeSinceSensor - 200f) / CENTER_RETURN_TIME_MS).coerceIn(0f, 1f)
            // centerDriftRate has stronger effect now
            val driftFactor = centerDriftRate * 2f * centerProgress * (deltaTimeMs / 16f)
            
            currentX *= (1f - driftFactor)
            currentY *= (1f - driftFactor)
            
            // Also move target toward center
            targetX *= (1f - driftFactor)
            targetY *= (1f - driftFactor)
            clampCurrent()
        }
    }
    
    /**
     * Get the current parallax offset as (x, y) pair.
     * Use this for layers that manage their own depth multiplication.
     */
    fun getOffset(): Pair<Float, Float> = Pair(currentX, currentY)
    
    /**
     * Get the current X offset.
     */
    fun getOffsetX(): Float = currentX
    
    /**
     * Get the current Y offset.
     */
    fun getOffsetY(): Float = currentY
    
    /**
     * Get parallax offset for a specific layer depth.
     * 
     * @param depthFactor Layer depth: 0.0 = background (moves least), 1.0 = foreground (moves most)
     * @return (x, y) offset to apply to this layer
     */
    fun getOffsetForDepth(depthFactor: Float): Pair<Float, Float> {
        return Pair(currentX * depthFactor, currentY * depthFactor)
    }
    
    /**
     * Get X offset for a specific depth.
     */
    fun getOffsetXForDepth(depthFactor: Float): Float = currentX * depthFactor
    
    /**
     * Get Y offset for a specific depth.
     */
    fun getOffsetYForDepth(depthFactor: Float): Float = currentY * depthFactor
    
    /**
     * Reset parallax to center immediately.
     */
    fun reset() {
        currentX = 0f
        currentY = 0f
        targetX = 0f
        targetY = 0f
    }

    private fun clampCurrent() {
        val limit = maxOffsetPx * strength
        currentX = currentX.coerceIn(-limit, limit)
        currentY = currentY.coerceIn(-limit, limit)
    }
    
    /**
     * Debug logging of current state.
     */
    fun logState() {
        Log.d(TAG, "Parallax: current=(%.1f, %.1f), target=(%.1f, %.1f), strength=%.2f".format(
            currentX, currentY, targetX, targetY, strength
        ))
    }
}
