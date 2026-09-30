package info.olli.weatherlwp.testutil

import info.olli.weatherlwp.parallax.ParallaxCalculator
import kotlin.math.sqrt

// The delta model clamps large per-frame jumps and treats tiny ones as stillness, so tests must
// ramp tilt gradually. Auto step count keeps each frame's delta above the stillness threshold.
private fun autoSteps(dx: Float, dy: Float, steps: Int): Int =
    if (steps > 0) steps else (sqrt(dx * dx + dy * dy) / 0.025f).toInt().coerceIn(5, 100)

/** Ramp tilt from (0,0) to ([toX], [toY]), one 16 ms frame per step. */
fun rampTilt(calc: ParallaxCalculator, toX: Float, toY: Float, steps: Int = -1) {
    val actualSteps = autoSteps(toX, toY, steps)
    for (i in 0..actualSteps) {
        val t = i.toFloat() / actualSteps
        calc.updateFromSensor(toX * t, toY * t)
        calc.update(16)
    }
}

/** Ramp tilt from ([fromX], [fromY]) to ([toX], [toY]), one 16 ms frame per step. */
fun rampTiltFromTo(
    calc: ParallaxCalculator,
    fromX: Float, fromY: Float,
    toX: Float, toY: Float,
    steps: Int = -1
) {
    val dx = toX - fromX
    val dy = toY - fromY
    val actualSteps = autoSteps(dx, dy, steps)
    for (i in 1..actualSteps) {
        val t = i.toFloat() / actualSteps
        calc.updateFromSensor(fromX + dx * t, fromY + dy * t)
        calc.update(16)
    }
}
