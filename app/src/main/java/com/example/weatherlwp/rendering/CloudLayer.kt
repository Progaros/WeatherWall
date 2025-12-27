package com.example.weatherlwp.rendering

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import kotlin.random.Random

/**
 * CloudLayer - Renders moving cloud shapes with diffused edges.
 * Clouds move slowly across the screen and affect sky color when dense.
 */
class CloudLayer {
    
    enum class CloudDensity {
        NONE, LIGHT, MEDIUM, HEAVY, OVERCAST
    }
    
    private data class Cloud(
        var x: Float,           // Current X position (0-1 ratio + overflow)
        val y: Float,           // Y position (0-0.5 ratio, upper half)
        val width: Float,       // Width multiplier
        val height: Float,      // Height multiplier  
        val speed: Float,       // Movement speed
        val alpha: Int,         // Base alpha
        val layerDepth: Float   // Parallax depth factor
    )
    
    private val clouds = mutableListOf<Cloud>()
    private val cloudPaint = Paint().apply { isAntiAlias = true }
    private val skyOverlayPaint = Paint()
    
    private var lastWidth = 0
    private var lastHeight = 0
    private var initialized = false
    private var density = CloudDensity.LIGHT
    
    private var lastUpdateTime = System.currentTimeMillis()
    
    fun setDensity(newDensity: CloudDensity) {
        if (density != newDensity) {
            density = newDensity
            initialized = false
        }
    }
    
    private fun initClouds(width: Int, height: Int) {
        clouds.clear()
        
        val cloudCount = when (density) {
            CloudDensity.NONE -> 0
            CloudDensity.LIGHT -> 3
            CloudDensity.MEDIUM -> 6
            CloudDensity.HEAVY -> 10
            CloudDensity.OVERCAST -> 15
        }
        
        val random = Random(42)
        
        for (i in 0 until cloudCount) {
            val layerDepth = random.nextFloat() * 0.5f + 0.1f  // 0.1 to 0.6
            clouds.add(Cloud(
                x = random.nextFloat() * 1.5f - 0.25f,  // Start spread across and slightly off-screen
                y = random.nextFloat() * 0.35f + 0.05f, // Upper portion of sky
                width = random.nextFloat() * 0.3f + 0.15f,  // 15-45% of screen width
                height = random.nextFloat() * 0.08f + 0.04f, // 4-12% of screen height
                speed = (random.nextFloat() * 0.015f + 0.005f) * (1f - layerDepth), // Slower for far clouds
                alpha = when (density) {
                    CloudDensity.LIGHT -> random.nextInt(40, 80)
                    CloudDensity.MEDIUM -> random.nextInt(60, 120)
                    CloudDensity.HEAVY -> random.nextInt(100, 160)
                    CloudDensity.OVERCAST -> random.nextInt(140, 200)
                    else -> 0
                },
                layerDepth = layerDepth
            ))
        }
        
        // Sort by depth (far clouds first)
        clouds.sortBy { it.layerDepth }
        
        initialized = true
        lastWidth = width
        lastHeight = height
    }
    
    fun render(canvas: Canvas, enabled: Boolean, parallaxOffsetX: Float = 0f) {
        if (!enabled || density == CloudDensity.NONE) return
        
        val w = canvas.width
        val h = canvas.height
        
        if (!initialized || w != lastWidth || h != lastHeight) {
            initClouds(w, h)
        }
        
        // Update cloud positions
        val now = System.currentTimeMillis()
        val dt = (now - lastUpdateTime) / 1000f
        lastUpdateTime = now
        
        for (cloud in clouds) {
            // Move cloud to the right
            cloud.x += cloud.speed * dt
            
            // Wrap around when off-screen right
            if (cloud.x > 1.3f) {
                cloud.x = -cloud.width - 0.1f
            }
        }
        
        // Draw sky darkening overlay for heavy clouds
        if (density == CloudDensity.HEAVY || density == CloudDensity.OVERCAST) {
            val overlayAlpha = when (density) {
                CloudDensity.HEAVY -> 30
                CloudDensity.OVERCAST -> 60
                else -> 0
            }
            skyOverlayPaint.color = Color.argb(overlayAlpha, 70, 80, 100)
            canvas.drawRect(0f, 0f, w.toFloat(), h * 0.6f, skyOverlayPaint)
        }
        
        // Draw each cloud
        for (cloud in clouds) {
            drawCloud(canvas, w.toFloat(), h.toFloat(), cloud, parallaxOffsetX)
        }
    }
    
    private fun drawCloud(canvas: Canvas, w: Float, h: Float, cloud: Cloud, parallaxOffsetX: Float) {
        val centerX = (cloud.x + parallaxOffsetX * cloud.layerDepth * 0.01f) * w
        val centerY = cloud.y * h
        val cloudWidth = cloud.width * w
        val cloudHeight = cloud.height * h
        
        // Draw multiple overlapping ovals to create fluffy cloud shape
        val segments = 7  // More segments for fluffier clouds
        for (i in 0 until segments) {
            val offsetX = (i - segments / 2) * cloudWidth * 0.20f
            val offsetY = kotlin.math.sin(i * 0.8) * cloudHeight * 0.25f
            val segmentWidth = cloudWidth * (0.35f + kotlin.math.cos(i * 0.5).toFloat() * 0.15f)
            val segmentHeight = cloudHeight * (0.5f + kotlin.math.sin(i * 0.7).toFloat() * 0.25f)
            
            val radius = maxOf(segmentWidth, segmentHeight) * 1.2f  // Larger gradient radius
            val cx = centerX + offsetX.toFloat()
            val cy = centerY + offsetY.toFloat()
            
            // Create radial gradient with gradual alpha falloff (softer edges)
            // Use multiple color stops for smoother transition
            val gradient = RadialGradient(
                cx, cy, radius,
                intArrayOf(
                    Color.argb(cloud.alpha, 255, 255, 255),           // Center: full alpha
                    Color.argb((cloud.alpha * 0.7f).toInt(), 255, 255, 255),  // 40%: 70% alpha
                    Color.argb((cloud.alpha * 0.4f).toInt(), 255, 255, 255),  // 70%: 40% alpha
                    Color.argb((cloud.alpha * 0.15f).toInt(), 255, 255, 255), // 85%: 15% alpha
                    Color.argb(0, 255, 255, 255)                       // Edge: transparent
                ),
                floatArrayOf(0f, 0.4f, 0.7f, 0.85f, 1f),  // Color stop positions
                Shader.TileMode.CLAMP
            )
            cloudPaint.shader = gradient
            
            canvas.drawOval(
                cx - segmentWidth / 2,
                cy - segmentHeight / 2,
                cx + segmentWidth / 2,
                cy + segmentHeight / 2,
                cloudPaint
            )
        }
    }
}
