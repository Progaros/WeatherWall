package info.olli.weatherlwp.rendering.seasonal

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path

/** Chinese New Year dragon flying right-to-left across the sky. */
internal class DragonRenderer {

    private var dragonPhase = 0f
    // Pre-allocated dragon segment positions to avoid per-frame Array+Pair allocations
    private val dragonSegmentX = FloatArray(12)
    private val dragonSegmentY = FloatArray(12)
    private val dragonColors = intArrayOf(
        Color.rgb(200, 30, 30),    // Red (body)
        Color.rgb(255, 200, 50),   // Gold (accents)
        Color.rgb(255, 80, 30),    // Orange-red (scales)
        Color.rgb(180, 20, 20)     // Dark red (outline)
    )

    // Pre-allocated paths (avoid per-frame allocations)
    private val dragonAntlerPath = Path()
    private val dragonHeadPath = Path()
    private val dragonEyePath = Path()
    private val dragonBrowPath = Path()
    private val dragonWhiskerPath = Path()

    private val dragonPaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.FILL
    }

    private val dragonOutlinePaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = dragonColors[3]
    }

    /** Advance the flight animation by one frame; called before the fireworks backdrop is drawn. */
    fun advance() {
        dragonPhase += 0.025f
    }

    fun draw(canvas: Canvas, screenWidth: Int, screenHeight: Int, heightScale: Float) {
        // --- 1. CALCULATE POSITIONS ---
        val scale = heightScale.coerceIn(0.5f, 1.5f)
        val segmentRadius = 18f * scale
        val segmentCount = 12
        val segmentSpacing = segmentRadius * 1.6f
        val totalBodyLength = segmentCount * segmentSpacing
        val margin = segmentRadius * 4f

        // Dragon moves right-to-left
        // Wrap distance accounts for full body length + margins so dragon fully
        // exits left edge before re-entering from the right (fixes despawn-on-screen bug)
        val wrapDistance = screenWidth + totalBodyLength + margin * 2f + 200f * heightScale
        val baseX = screenWidth + (margin + 100f) * heightScale - (dragonPhase * 40f * heightScale) % wrapDistance
        val baseY = screenHeight * 0.18f

        for (i in 0 until segmentCount) {
            val t = i.toFloat()
            // Wave motion
            val wave1 = kotlin.math.sin((dragonPhase * 3f - t * 0.5f).toDouble()).toFloat() * 25f * scale
            val wave2 = kotlin.math.sin((dragonPhase * 5f - t * 0.8f).toDouble()).toFloat() * 8f * scale
            dragonSegmentX[i] = baseX + t * segmentSpacing
            dragonSegmentY[i] = baseY + wave1 + wave2
        }

        // --- VISIBILITY CHECK ---
        // Skip rendering if dragon is completely off-screen
        // Use generous margin to include head features that extend beyond segment positions
        var leftmostX = dragonSegmentX[0]
        var rightmostX = dragonSegmentX[0]
        for (i in 1 until segmentCount) {
            if (dragonSegmentX[i] < leftmostX) leftmostX = dragonSegmentX[i]
            if (dragonSegmentX[i] > rightmostX) rightmostX = dragonSegmentX[i]
        }

        // Dragon moves right-to-left, so skip if entirely past left edge or waiting to enter from right
        if (rightmostX < -margin || leftmostX > screenWidth + margin) {
            return  // Dragon completely off-screen, skip rendering
        }

        // --- 2. DRAW BODY (Tail to Neck) ---
        // Stop at i=1 so head (i=0) is drawn last on top
        for (i in (segmentCount - 1) downTo 1) {
            val x = dragonSegmentX[i]
            val y = dragonSegmentY[i]
            // Taper tail
            val sizeFactor = 1f - (i.toFloat() / segmentCount) * 0.4f
            val radius = segmentRadius * sizeFactor

            // Body Segment
            dragonPaint.color = if (i % 2 == 0) dragonColors[0] else dragonColors[2]
            canvas.drawCircle(x, y, radius, dragonPaint)

            // Gold Scales (SKIP for segments close to head to reduce clutter)
            if (i % 2 == 1 && i > 2) {
                dragonPaint.color = dragonColors[1]
                canvas.drawCircle(x, y - radius * 0.3f, radius * 0.5f, dragonPaint)
            }

            // Outline
            canvas.drawCircle(x, y, radius, dragonOutlinePaint)
        }

        // --- 3. DRAW HEAD (Complex Path) ---
        val headX = dragonSegmentX[0]
        val headY = dragonSegmentY[0]
        val headSize = segmentRadius * 2.4f

        // A. Rear Antlers (Solid Fill, not just lines)
        // Draw these BEFORE the head so they look like they grow out of the skull
        dragonPaint.color = dragonColors[1] // Gold
        dragonAntlerPath.reset()
        // Main beam (polygon shape for solidity)
        dragonAntlerPath.moveTo(headX + headSize * 0.1f, headY - headSize * 0.6f) // Base
        dragonAntlerPath.lineTo(headX + headSize * 0.4f, headY - headSize * 1.5f) // Tip
        dragonAntlerPath.lineTo(headX + headSize * 0.25f, headY - headSize * 1.5f) // Thickness at tip
        dragonAntlerPath.lineTo(headX - headSize * 0.05f, headY - headSize * 0.6f) // Base width
        dragonAntlerPath.close()
        // Small branch
        dragonAntlerPath.moveTo(headX + headSize * 0.2f, headY - headSize * 1.0f)
        dragonAntlerPath.lineTo(headX + headSize * 0.0f, headY - headSize * 1.3f)
        dragonAntlerPath.lineTo(headX - headSize * 0.1f, headY - headSize * 1.2f)
        dragonAntlerPath.close()

        // Draw filled antler + outline
        canvas.drawPath(dragonAntlerPath, dragonPaint) // Fill Gold
        dragonOutlinePaint.color = dragonColors[3]
        canvas.drawPath(dragonAntlerPath, dragonOutlinePaint) // Outline Dark Red

        // B. Head Shape (Snout & Jaw with distinct overbite)
        dragonHeadPath.reset()
        val snoutLen = headSize * 1.4f

        // Forehead
        dragonHeadPath.moveTo(headX + headSize * 0.2f, headY - headSize * 0.5f)
        dragonHeadPath.cubicTo(
            headX - headSize * 0.2f, headY - headSize * 0.6f,
            headX - snoutLen * 0.4f, headY - headSize * 0.5f,
            headX - snoutLen, headY - headSize * 0.2f // Snout Tip
        )

        // Upper Lip / Snout (Overhanging - creates overbite)
        dragonHeadPath.cubicTo(
            headX - snoutLen - headSize * 0.2f, headY + headSize * 0.1f, // Curve down/out
            headX - snoutLen * 0.8f, headY + headSize * 0.2f,            // Curve back in
            headX - headSize * 0.5f, headY + headSize * 0.1f             // Corner of mouth
        )

        // Lower Jaw (Distinct from upper - step down)
        dragonHeadPath.lineTo(headX - headSize * 0.6f, headY + headSize * 0.2f) // Step down slightly
        dragonHeadPath.cubicTo(
            headX - headSize * 0.6f, headY + headSize * 0.5f, // Jaw chin curve
            headX - headSize * 0.2f, headY + headSize * 0.7f, // Jaw bottom
            headX + headSize * 0.3f, headY + headSize * 0.4f  // Jaw hinge/neck
        )

        // Close at back of head
        dragonHeadPath.quadTo(
            headX + headSize * 0.5f, headY - headSize * 0.2f,
            headX + headSize * 0.2f, headY - headSize * 0.5f
        )

        // Fill Head (Red)
        dragonPaint.color = dragonColors[0]
        canvas.drawPath(dragonHeadPath, dragonPaint)

        // Outline Head
        dragonOutlinePaint.color = dragonColors[3]
        canvas.drawPath(dragonHeadPath, dragonOutlinePaint)

        // C. Face Details
        // Eye (Almond shaped - demon/eagle style)
        val eyeX = headX - headSize * 0.25f
        val eyeY = headY - headSize * 0.25f
        val eyeRad = headSize * 0.22f

        dragonPaint.color = Color.WHITE
        dragonEyePath.reset()
        dragonEyePath.moveTo(eyeX - eyeRad, eyeY)
        dragonEyePath.quadTo(eyeX, eyeY - eyeRad * 0.7f, eyeX + eyeRad, eyeY - eyeRad * 0.2f)
        dragonEyePath.quadTo(eyeX, eyeY + eyeRad * 0.7f, eyeX - eyeRad, eyeY)
        canvas.drawPath(dragonEyePath, dragonPaint)

        // Pupil
        dragonPaint.color = Color.BLACK
        canvas.drawCircle(eyeX - eyeRad * 0.1f, eyeY, eyeRad * 0.3f, dragonPaint)

        // Brow Ridge (Gold)
        dragonOutlinePaint.color = dragonColors[1] // Gold
        dragonOutlinePaint.strokeWidth = 3f * scale
        dragonOutlinePaint.style = Paint.Style.STROKE
        dragonBrowPath.reset()
        dragonBrowPath.moveTo(eyeX - eyeRad * 1.2f, eyeY - eyeRad * 0.6f)
        dragonBrowPath.quadTo(eyeX, eyeY - eyeRad * 1.3f, eyeX + eyeRad * 1.2f, eyeY - eyeRad * 0.5f)
        canvas.drawPath(dragonBrowPath, dragonOutlinePaint)

        // Nostril (Flared)
        dragonPaint.color = dragonColors[3]
        canvas.drawOval(
            headX - snoutLen * 0.85f, headY - headSize * 0.1f,
            headX - snoutLen * 0.7f, headY + headSize * 0.05f,
            dragonPaint
        )

        // D. Whiskers (Flowing from Snout)
        val whiskerStartX = headX - snoutLen * 0.7f
        val whiskerStartY = headY - headSize * 0.1f

        dragonWhiskerPath.reset()
        dragonWhiskerPath.moveTo(whiskerStartX, whiskerStartY)
        dragonWhiskerPath.cubicTo(
            whiskerStartX - headSize * 0.5f, whiskerStartY + headSize * 0.8f, // Loop forward/down
            headX - headSize * 0.2f, headY + headSize * 1.2f,                 // Loop under jaw
            headX + headSize * 1.5f, headY + headSize * 0.5f                  // Trail back
        )

        dragonOutlinePaint.color = dragonColors[1] // Gold
        dragonOutlinePaint.strokeWidth = 2.5f * scale
        canvas.drawPath(dragonWhiskerPath, dragonOutlinePaint)

        // Reset paint for next frame
        dragonOutlinePaint.color = dragonColors[3]
        dragonOutlinePaint.strokeWidth = 2f * scale
        dragonOutlinePaint.style = Paint.Style.STROKE
    }
}
