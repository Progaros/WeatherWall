package info.olli.weatherlwp.rendering.seasonal

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.Log
import info.olli.weatherlwp.PreferenceDefaults
import info.olli.weatherlwp.simulation.SimulationSettings
import kotlin.random.Random

// Java Random is used for nextGaussian() (normal distribution)
private val gaussianRandom = java.util.Random()

/**
 * New Year fireworks: pooled rockets/explosion particles, frequency-based auto-launch
 * and shake-triggered bursts.
 */
internal class FireworksSystem {

    // Firework particles for New Year
    private data class Firework(
        var x: Float = 0f,
        var y: Float = 0f,
        var vx: Float = 0f,
        var vy: Float = 0f,
        var color: Int = 0,
        var life: Int = 0,
        var maxLife: Int = 0,
        var size: Float = 3f,
        var hasSparkle: Boolean = false,
        var sparkleTrailX: Float = 0f,
        var sparkleTrailY: Float = 0f
    )

    // Rocket that shoots up before exploding
    private data class Rocket(
        var x: Float = 0f,
        var y: Float = 0f,
        var vy: Float = 0f,
        var targetY: Float = 0f,
        var colors: List<Int> = emptyList(),  // 1-3 colors for this rocket's explosion
        var active: Boolean = false,
        var explodeAfterFrame: Long = 0,  // Frame number when rocket should explode
        var trailX1: Float = 0f, var trailY1: Float = 0f,
        var trailX2: Float = 0f, var trailY2: Float = 0f,
        var trailX3: Float = 0f, var trailY3: Float = 0f
    )

    // Pre-allocated pools
    private val fireworks = Array(2000) { Firework() }  // Large pool for shake fireworks (prevents late-exploding rockets from disappearing)
    private val rockets = Array(50) { Rocket() }  // Up to 50 rockets in flight for shake fireworks

    // Set true whenever a rocket/firework is activated; lets hasActiveFireworks skip the
    // full pool scan while nothing is in flight. Self-clears once a scan finds none active.
    @Volatile private var fireworksPossiblyActive = false

    private val fireworkPaint = Paint().apply {
        isAntiAlias = true
    }

    private val rocketPaint = Paint().apply {
        color = Color.WHITE
        isAntiAlias = true
    }

    private val sparklePaint = Paint().apply {
        isAntiAlias = true
        color = Color.WHITE
    }

    private val fireworkColors = intArrayOf(
        Color.RED, Color.YELLOW, Color.GREEN, Color.CYAN,
        Color.MAGENTA, Color.WHITE, 0xFFFF6600.toInt(),  // Orange
        0xFFFF69B4.toInt(),  // Hot pink
        0xFF00FF7F.toInt(),  // Spring green
        0xFFFFD700.toInt(),  // Gold
        0xFF7B68EE.toInt()   // Medium slate blue
    )

    private var screenWidth = 0
    private var screenHeight = 0
    private var heightScale = 1f

    // Global frame counter for time-based rocket explosions (Long to avoid overflow)
    private var frameCounter = 0L
    private var fireworkTimer = 0

    private var fireworkFrequency = 50  // 0-100, default 50
    private var fireworkAmount = 100    // 10-200, controls particles per explosion

    // Shake-to-fireworks settings
    var shakeFireworksEnabled = true
        private set
    private var shakeFireworksCount = 12  // Number of fireworks to launch on shake (10-15)

    fun applySettings(settings: SimulationSettings) {
        fireworkFrequency = settings.getInt("pref_firework_frequency", PreferenceDefaults.FIREWORK_FREQUENCY).coerceIn(0, 100)
        fireworkAmount = settings.getInt("pref_firework_amount", PreferenceDefaults.FIREWORK_AMOUNT).coerceIn(10, 200)

        shakeFireworksEnabled = settings.getBoolean("pref_shake_fireworks_enabled", PreferenceDefaults.SHAKE_FIREWORKS_ENABLED)
        shakeFireworksCount = settings.getInt("pref_shake_fireworks_count", 15).coerceIn(5, 50)
    }

    /** Store screen metrics and clear all in-flight rockets and particles. */
    fun reset(screenWidth: Int, screenHeight: Int, heightScale: Float) {
        this.screenWidth = screenWidth
        this.screenHeight = screenHeight
        this.heightScale = heightScale

        // Reset fireworks
        for (f in fireworks) {
            f.life = 0
        }

        // Reset rockets
        for (r in rockets) {
            r.active = false
        }
    }

    /**
     * Whether any rocket or particle is in flight. The fireworksPossiblyActive gate skips the
     * O(rockets+fireworks) scan in the common idle case.
     */
    fun hasActiveFireworks(): Boolean {
        return if (fireworksPossiblyActive) {
            val active = rockets.any { it.active } || fireworks.any { it.life > 0 }
            if (!active) fireworksPossiblyActive = false
            active
        } else {
            false
        }
    }

    /**
     * Launch a burst of rockets; count is normally distributed around the configured count.
     * Caller must ensure the system has been [reset] with valid screen metrics.
     */
    fun triggerShake() {
        // Use normal distribution for variety in firework count
        // Mean = shakeFireworksCount, stdDev = 15% of count
        val stdDev = shakeFireworksCount * 0.15
        val normalValue = gaussianRandom.nextGaussian() * stdDev + shakeFireworksCount
        val actualCount = normalValue.toInt().coerceIn(3, 50)

        Log.d(SEASONAL_TAG, "Triggering shake fireworks: $actualCount rockets (target: $shakeFireworksCount)")

        // Launch rockets up to the requested count (capped by available rocket slots)
        for (i in 0 until actualCount.coerceAtMost(rockets.size)) {
            launchRocket()
        }
    }

    fun render(canvas: Canvas, autoLaunch: Boolean = true) {
        if (autoLaunch) {
            fireworkTimer++

            // Launch new rocket based on frequency setting
            // At 0: every 200+ frames (rare), at 50: every 40-90 frames, at 100: every 10-30 frames
            val minInterval = when {
                fireworkFrequency >= 80 -> 10
                fireworkFrequency >= 50 -> 30
                fireworkFrequency >= 20 -> 60
                else -> 150
            }
            val intervalRange = when {
                fireworkFrequency >= 80 -> 20
                fireworkFrequency >= 50 -> 50
                fireworkFrequency >= 20 -> 80
                else -> 100
            }

            if (fireworkFrequency > 0 && fireworkTimer > minInterval + Random.nextInt(intervalRange)) {
                fireworkTimer = 0
                launchRocket()
            }
        }

        // Increment frame counter for time-based rocket explosions
        frameCounter++

        // Update and render rockets
        for (r in rockets) {
            if (r.active) {
                // Store trail positions (previous positions for trail effect)
                r.trailX3 = r.trailX2
                r.trailY3 = r.trailY2
                r.trailX2 = r.trailX1
                r.trailY2 = r.trailY1
                r.trailX1 = r.x
                r.trailY1 = r.y

                // Move rocket upward (scale velocity by height for consistent physics)
                r.y += r.vy * heightScale
                r.vy += 0.06f * heightScale  // Gentle deceleration

                // Draw rocket trail (fading dots behind, scaled by height)
                val trailScale = heightScale.coerceAtLeast(0.5f)  // Don't go below 50% size
                rocketPaint.color = Color.argb(60, 255, 200, 100)
                canvas.drawCircle(r.trailX3, r.trailY3, 1.5f * trailScale, rocketPaint)
                rocketPaint.color = Color.argb(120, 255, 220, 150)
                canvas.drawCircle(r.trailX2, r.trailY2, 2f * trailScale, rocketPaint)
                rocketPaint.color = Color.argb(200, 255, 240, 200)
                canvas.drawCircle(r.trailX1, r.trailY1, 2.5f * trailScale, rocketPaint)

                // Draw rocket head (scaled by height)
                rocketPaint.color = Color.WHITE
                canvas.drawCircle(r.x, r.y, 3f * trailScale, rocketPaint)

                // Explode at target apex or fallback frame timeout.
                if (r.y <= r.targetY || frameCounter >= r.explodeAfterFrame) {
                    explodeRocket(r)
                    r.active = false
                }
            }
        }

        // Update and render explosion particles
        // Scale factor for particle sizes (min 50% to keep visible on very small screens)
        val particleScale = heightScale.coerceAtLeast(0.5f)

        for (f in fireworks) {
            if (f.life > 0) {
                // Store previous position for sparkle trail
                f.sparkleTrailX = f.x
                f.sparkleTrailY = f.y

                // Update physics (scale velocity and gravity by height for consistent motion)
                f.x += f.vx * heightScale
                f.y += f.vy * heightScale
                f.vy += 0.12f // gravity (in reference pixels per frame)
                f.vx *= 0.98f // air resistance
                f.life--

                val lifeRatio = f.life / f.maxLife.toFloat()
                val alpha = (255 * lifeRatio).toInt().coerceIn(0, 255)

                // Scaled size for this particle
                val scaledSize = f.size * particleScale

                // Draw sparkle trail behind the ball (fine particles)
                if (f.hasSparkle && lifeRatio > 0.3f) {
                    sparklePaint.color = f.color
                    sparklePaint.alpha = (alpha * 0.4f).toInt()
                    canvas.drawCircle(f.sparkleTrailX, f.sparkleTrailY, scaledSize * 0.4f, sparklePaint)

                    // Additional tiny sparkles (spread scaled by height)
                    val sparkleAlpha = (alpha * 0.25f).toInt()
                    sparklePaint.alpha = sparkleAlpha
                    canvas.drawCircle(
                        f.sparkleTrailX + (Random.nextFloat() - 0.5f) * 4f * heightScale,
                        f.sparkleTrailY + (Random.nextFloat() - 0.5f) * 4f * heightScale,
                        scaledSize * 0.2f, sparklePaint
                    )
                }

                // Draw main colorful ball
                fireworkPaint.color = f.color
                fireworkPaint.alpha = alpha
                val size = scaledSize * (0.5f + lifeRatio * 0.5f)  // Shrink as it fades
                canvas.drawCircle(f.x, f.y, size, fireworkPaint)

                // Bright core
                fireworkPaint.alpha = (alpha * 0.7f).toInt()
                fireworkPaint.color = Color.WHITE
                canvas.drawCircle(f.x, f.y, size * 0.4f, fireworkPaint)
            }
        }
    }

    private fun launchRocket() {
        // Find an inactive rocket slot
        for (r in rockets) {
            if (!r.active) {
                r.x = screenWidth * 0.15f + Random.nextFloat() * screenWidth * 0.7f
                r.y = screenHeight.toFloat()
                // Upward velocity: -14 to -20 (negative = up, faster to reach height)
                r.vy = -14f - Random.nextFloat() * 6f
                r.targetY = screenHeight * 0.08f + Random.nextFloat() * screenHeight * 0.22f

                // Explode after 75-119 frames (~1.25-2.0 seconds at 60fps)
                // Longer flight time ensures rockets clear mountains (which are at 30-60% from bottom)
                r.explodeAfterFrame = frameCounter + 75 + Random.nextInt(45)

                // Choose 1-3 colors for this rocket's explosion
                val numColors = 1 + Random.nextInt(3)  // 1, 2, or 3 colors
                r.colors = List(numColors) { fireworkColors[Random.nextInt(fireworkColors.size)] }

                r.active = true
                fireworksPossiblyActive = true
                r.trailX1 = r.x
                r.trailY1 = r.y
                r.trailX2 = r.x
                r.trailY2 = r.y
                r.trailX3 = r.x
                r.trailY3 = r.y
                break
            }
        }
    }

    private fun explodeRocket(rocket: Rocket) {
        val cx = rocket.x
        val cy = rocket.y.coerceIn(0f, screenHeight.toFloat())
        val colors = rocket.colors

        // Spawn explosion particles based on fireworkAmount setting
        // Reduced particle count for shake to ensure pool isn't exhausted
        // At 10: 8-12 particles, at 100: 25-35 particles, at 200: 45-55 particles
        var particleCount = 0
        val baseParticles = (fireworkAmount * 0.2f + 5).toInt()   // 10→7, 100→25, 200→45
        val varianceParticles = (fireworkAmount * 0.1f + 3).toInt()  // 10→4, 100→13, 200→23
        val targetParticles = baseParticles + Random.nextInt(varianceParticles.coerceAtLeast(1))

        for (f in fireworks) {
            if (f.life <= 0 && particleCount < targetParticles) {
                val angle = Random.nextFloat() * 360f
                // Narrow speed range keeps the burst a clear ring
                val speed = 6f + Random.nextFloat() * 2f

                f.x = cx
                f.y = cy
                val angleRad = angle * DEG_TO_RAD
                f.vx = (speed * kotlin.math.cos(angleRad)).toFloat()
                f.vy = (speed * kotlin.math.sin(angleRad)).toFloat()

                // Randomly pick one of the rocket's colors
                f.color = colors[Random.nextInt(colors.size)]
                f.maxLife = 50 + Random.nextInt(40)
                f.life = f.maxLife
                fireworksPossiblyActive = true
                f.size = 2.5f + Random.nextFloat() * 3f
                f.hasSparkle = Random.nextFloat() > 0.4f  // 60% have sparkle trails
                f.sparkleTrailX = cx
                f.sparkleTrailY = cy

                particleCount++
            }
        }
    }
}
