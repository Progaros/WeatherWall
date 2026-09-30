package info.olli.weatherlwp.rendering.seasonal

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import info.olli.weatherlwp.simulation.SimulationSettings
import kotlin.random.Random

// Pre-calculated trigonometry for 8-point star (avoids Math.toRadians in render loop)
// Angles: 0°, 45°, 90°, 135°, 180°, 225°, 270°, 315°
private val STAR_RAY_ANGLES_RAD = doubleArrayOf(
    0.0, Math.PI / 4, Math.PI / 2, 3 * Math.PI / 4,
    Math.PI, 5 * Math.PI / 4, 3 * Math.PI / 2, 7 * Math.PI / 4
)
private val STAR_RAY_COS = STAR_RAY_ANGLES_RAD.map { kotlin.math.cos(it).toFloat() }.toFloatArray()
private val STAR_RAY_SIN = STAR_RAY_ANGLES_RAD.map { kotlin.math.sin(it).toFloat() }.toFloatArray()

// Pre-calculated trail angle (225° = down-left from star)
private const val TRAIL_ANGLE_RAD = 225.0 * Math.PI / 180.0
private val TRAIL_COS = kotlin.math.cos(TRAIL_ANGLE_RAD).toFloat()
private val TRAIL_SIN = kotlin.math.sin(TRAIL_ANGLE_RAD).toFloat()
private const val TRAIL_PERP_RAD = TRAIL_ANGLE_RAD + Math.PI / 2
private val TRAIL_PERP_COS = kotlin.math.cos(TRAIL_PERP_RAD).toFloat()
private val TRAIL_PERP_SIN = kotlin.math.sin(TRAIL_PERP_RAD).toFloat()

// Conversion constant
private const val DEG_TO_RAD = Math.PI / 180.0

/** Christmas (Bethlehem) star with glow, 8-point rays and tapered trail. */
internal class ChristmasStarRenderer {

    // Christmas star (Bethlehem star with trail/Schweif)
    private data class ChristmasStar(
        val x: Float,      // Fixed position (top right area)
        val y: Float,
        val size: Float,
        var twinkle: Float = 0f,
        var twinkleSpeed: Float = 2f
    )

    // Shooting star / trail particles
    private data class TrailParticle(
        var x: Float = 0f,
        var y: Float = 0f,
        var alpha: Int = 255,
        var size: Float = 2f
    )

    // Background twinkling stars
    private data class Star(
        var x: Float = 0f,
        var y: Float = 0f,
        var size: Float = 0f,
        var twinkle: Float = 0f,
        var twinkleSpeed: Float = 0f
    )

    // Pre-allocated pools
    private val stars = Array(30) { Star() }
    private val trailParticles = Array(20) { TrailParticle() }
    private var christmasStar: ChristmasStar? = null

    // Pre-allocated paths (avoid allocations in render loop)
    private val christmasTrailPath = Path()
    private val christmasRayPath = Path()

    // Pre-allocated paints
    private val starPaint = Paint().apply {
        color = Color.WHITE
        isAntiAlias = true
    }

    private val christmasStarPaint = Paint().apply {
        color = Color.parseColor("#FFFFD700")  // Golden
        isAntiAlias = true
    }

    private val trailPaint = Paint().apply {
        color = Color.parseColor("#FFFFD700")
        isAntiAlias = true
    }

    private var starSizeMultiplier = 1.0f
    private var trailLengthMultiplier = 1.0f

    fun applySettings(settings: SimulationSettings) {
        starSizeMultiplier = (settings.getInt("pref_christmas_star_size", 100) / 100f).coerceIn(0.5f, 2.0f)
        trailLengthMultiplier = (settings.getInt("pref_christmas_star_trail", 100) / 100f).coerceIn(0f, 2.0f)
    }

    fun init(screenWidth: Int, screenHeight: Int, heightScale: Float) {
        // Use fixed seed for consistent star placement
        val starRandom = Random(54321)

        // Scale factor for sizes (min 50% for visibility on small screens)
        val sizeScale = heightScale.coerceAtLeast(0.5f)

        // Initialize background twinkling stars in upper portion of screen
        // Avoid the christmas star area (upper right)
        val christmasStarX = screenWidth * 0.82f
        val christmasStarY = screenHeight * 0.12f
        val exclusionRadius = screenWidth * 0.15f

        for (s in stars) {
            // Keep regenerating until we get a position not too close to christmas star
            var attempts = 0
            do {
                s.x = starRandom.nextFloat() * screenWidth
                s.y = starRandom.nextFloat() * screenHeight * 0.4f
                attempts++
            } while (attempts < 5 &&
                     kotlin.math.abs(s.x - christmasStarX) < exclusionRadius &&
                     kotlin.math.abs(s.y - christmasStarY) < exclusionRadius)

            // Size scaled relative to height
            s.size = (1.5f + starRandom.nextFloat() * 2.5f) * sizeScale
            s.twinkle = starRandom.nextFloat() * 360f
            s.twinkleSpeed = 2f + starRandom.nextFloat() * 4f
        }

        // Initialize the main Christmas star (Bethlehem star) - fixed position in upper right
        // Size is relative to reference height (35f at 1920px)
        christmasStar = ChristmasStar(
            x = christmasStarX,
            y = christmasStarY,
            size = 35f * heightScale.coerceAtLeast(0.5f),  // Scale with height, min 50%
            twinkle = 0f,
            twinkleSpeed = 3f
        )

        // Initialize trail particles (emanating from the star)
        // Sizes are relative to reference height
        val trailScale = heightScale.coerceAtLeast(0.5f)
        for (i in trailParticles.indices) {
            val t = i.toFloat() / trailParticles.size
            trailParticles[i].apply {
                x = 0f  // Will be set relative to star
                y = 0f
                alpha = (255 * (1f - t * 0.7f)).toInt()  // Fade along trail
                size = (3f + (1f - t) * 8f) * trailScale  // Larger near star, scaled
            }
        }
    }

    fun draw(canvas: Canvas, parallaxOffsetX: Float = 0f, parallaxOffsetY: Float = 0f) {
        // Note: Background twinkling stars are now handled by SkyLayer
        // This renderer only draws the main Christmas star with trail
        // This prevents duplicate star rendering at night during Christmas

        // Render the main Christmas star (Bethlehem star) with trail
        val star = christmasStar ?: return
        star.twinkle += star.twinkleSpeed

        // Apply parallax offset for sky-layer depth effect
        val starX = star.x + parallaxOffsetX
        val starY = star.y + parallaxOffsetY

        // Apply size multiplier
        val effectiveSize = star.size * starSizeMultiplier

        // Twinkle factor for the whole star (use precalculated DEG_TO_RAD)
        val twinkleFactor = 0.85f + 0.15f * kotlin.math.sin(star.twinkle * DEG_TO_RAD).toFloat()

        // Trail/Schweif - gradient path going down-left from the star (use precalculated angles)
        val trailLength = effectiveSize * 7f * trailLengthMultiplier

        // Only render trail if length > 0
        if (trailLengthMultiplier > 0.01f) {
            // Smooth gradient trail using LinearGradient instead of discrete circles
            val trailEndX = starX + trailLength * TRAIL_COS
            val trailEndY = starY + trailLength * TRAIL_SIN

            // Create a tapered trail path (wider near star, narrower at end)
            val startWidth = effectiveSize * 0.35f
            val endWidth = effectiveSize * 0.05f

            // Use pre-allocated path (avoid allocation in render loop)
            christmasTrailPath.reset()
            // Starting edge (near star) - wider
            christmasTrailPath.moveTo(
                starX + startWidth * TRAIL_PERP_COS,
                starY + startWidth * TRAIL_PERP_SIN
            )
            christmasTrailPath.lineTo(
                starX - startWidth * TRAIL_PERP_COS,
                starY - startWidth * TRAIL_PERP_SIN
            )
            // End edge (far from star) - narrower
            christmasTrailPath.lineTo(
                trailEndX - endWidth * TRAIL_PERP_COS,
                trailEndY - endWidth * TRAIL_PERP_SIN
            )
            christmasTrailPath.lineTo(
                trailEndX + endWidth * TRAIL_PERP_COS,
                trailEndY + endWidth * TRAIL_PERP_SIN
            )
            christmasTrailPath.close()

            // Linear gradient from star (bright gold) to end (transparent)
            trailPaint.shader = LinearGradient(
                starX, starY, trailEndX, trailEndY,
                intArrayOf(
                    Color.argb((220 * twinkleFactor).toInt(), 255, 215, 0),
                    Color.argb((120 * twinkleFactor).toInt(), 255, 215, 0),
                    Color.argb(0, 255, 215, 0)
                ),
                floatArrayOf(0f, 0.4f, 1f),
                Shader.TileMode.CLAMP
            )
            trailPaint.alpha = 255
            canvas.drawPath(christmasTrailPath, trailPaint)
            trailPaint.shader = null
        }

        // Outer soft glow - radial gradient
        val glowRadius = effectiveSize * 2.5f * twinkleFactor
        christmasStarPaint.shader = RadialGradient(
            starX, starY, glowRadius,
            intArrayOf(
                Color.argb(80, 255, 230, 150),
                Color.argb(40, 255, 215, 0),
                Color.argb(0, 255, 215, 0)
            ),
            floatArrayOf(0f, 0.4f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(starX, starY, glowRadius, christmasStarPaint)
        christmasStarPaint.shader = null

        // 8-point star rays as filled triangles with alpha falloff (use precalculated sin/cos)
        val primaryRayLength = effectiveSize * 1.6f * twinkleFactor
        val secondaryRayLength = effectiveSize * 0.9f * twinkleFactor
        val rayBaseWidth = effectiveSize * 0.12f

        // Use pre-allocated path (avoid allocation in render loop)
        christmasRayPath.reset()
        for (index in 0 until 8) {
            val isPrimary = index % 2 == 0  // 0, 90, 180, 270 are primary
            val rayLen = if (isPrimary) primaryRayLength else secondaryRayLength
            // Use precalculated cos/sin arrays
            val cosVal = STAR_RAY_COS[index]
            val sinVal = STAR_RAY_SIN[index]
            // Perpendicular is 90° ahead in the array (with wrap)
            val perpIndex = (index + 2) % 8
            val perpCos = STAR_RAY_COS[perpIndex]
            val perpSin = STAR_RAY_SIN[perpIndex]

            val tipX = starX + rayLen * cosVal
            val tipY = starY + rayLen * sinVal

            val baseOffset = rayBaseWidth * (if (isPrimary) 1f else 0.7f)
            val baseX1 = starX + baseOffset * perpCos
            val baseY1 = starY + baseOffset * perpSin
            val baseX2 = starX - baseOffset * perpCos
            val baseY2 = starY - baseOffset * perpSin

            christmasRayPath.moveTo(baseX1, baseY1)
            christmasRayPath.lineTo(tipX, tipY)
            christmasRayPath.lineTo(baseX2, baseY2)
            christmasRayPath.close()
        }

        // Fill rays with gradient from center
        christmasStarPaint.shader = RadialGradient(
            starX, starY, primaryRayLength,
            intArrayOf(
                Color.argb(255, 255, 255, 240),
                Color.argb(220, 255, 220, 100),
                Color.argb(150, 255, 200, 50)
            ),
            floatArrayOf(0f, 0.3f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawPath(christmasRayPath, christmasStarPaint)
        christmasStarPaint.shader = null

        // Bright central core with radial gradient
        val coreRadius = effectiveSize * 0.5f * twinkleFactor
        christmasStarPaint.shader = RadialGradient(
            starX, starY, coreRadius,
            intArrayOf(
                Color.argb(255, 255, 255, 255),
                Color.argb(255, 255, 240, 200),
                Color.argb(200, 255, 215, 0)
            ),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(starX, starY, coreRadius, christmasStarPaint)
        christmasStarPaint.shader = null
    }
}
