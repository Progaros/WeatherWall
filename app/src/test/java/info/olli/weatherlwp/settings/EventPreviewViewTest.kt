package info.olli.weatherlwp.settings

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs
import kotlin.math.hypot

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class EventPreviewViewTest {

    @Test
    fun `every event stays centred in its preview circle`() {
        val context = RuntimeEnvironment.getApplication()
        val clockField = EventPreviewView::class.java.getDeclaredField("animationTimeSeconds")
            .apply { isAccessible = true }
        val failures = mutableListOf<String>()

        for (type in EventPreviewView.EventType.entries) {
            val view = EventPreviewView(context)
            view.eventType = type
            clockField.setFloat(view, 0f)
            val spec = View.MeasureSpec.makeMeasureSpec(SIZE, View.MeasureSpec.EXACTLY)
            view.measure(spec, spec)
            view.layout(0, 0, SIZE, SIZE)

            val bitmap = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            var worst = 0f
            var visibleFrames = 0
            var t = 0f
            while (t <= DURATION_S) {
                clockField.setFloat(view, t)
                bitmap.eraseColor(Color.TRANSPARENT)
                view.draw(canvas)
                val centroidDistance = eventCentroidDistance(bitmap)
                if (centroidDistance != null) {
                    visibleFrames++
                    worst = maxOf(worst, centroidDistance)
                }
                t += STEP_S
            }
            bitmap.recycle()

            println("EventPreview ${type.name}: visibleFrames=$visibleFrames worstCentroid=${"%.2f".format(worst)}r")
            if (visibleFrames * STEP_S < DURATION_S) failures += "${type.name} visible in only $visibleFrames frames"
            // Fireflies are a fixed-anchor swarm; the centroid of the few lit flies is just flicker noise.
            else if (type != EventPreviewView.EventType.FIREFLIES && worst > MAX_CENTROID_RADIUS) {
                failures += "${type.name} drifted to ${"%.2f".format(worst)}r"
            }
        }

        assertTrue("Preview events leave their circle: $failures", failures.isEmpty())
    }

    /** Distance of the event's pixel centroid from the circle centre in radii, or null if too few event pixels. */
    private fun eventCentroidDistance(bitmap: Bitmap): Float? {
        val c = SIZE / 2f
        val r = SIZE * 0.48f
        // Plain background samples: top of the sky and the left end of the ground band.
        val sky = bitmap.getPixel(SIZE / 2, (c - r * 0.93f).toInt())
        val ground = bitmap.getPixel((c - r * 0.72f).toInt(), (c + r * 0.35f).toInt())
        var sumX = 0.0
        var sumY = 0.0
        var count = 0
        for (y in 0 until SIZE) {
            for (x in 0 until SIZE) {
                val d = hypot(x - c, y - c)
                val p = bitmap.getPixel(x, y)
                if (Color.alpha(p) < 40) continue
                // Anti-aliased circle rim is neither background nor event.
                if (abs(d - r) < 2f) continue
                if (d < r && (close(p, sky) || close(p, ground))) continue
                sumX += x
                sumY += y
                count++
            }
        }
        if (count < MIN_EVENT_PIXELS) return null
        return (hypot(sumX / count - c, sumY / count - c) / r).toFloat()
    }

    private fun close(a: Int, b: Int): Boolean =
        abs(Color.red(a) - Color.red(b)) + abs(Color.green(a) - Color.green(b)) +
            abs(Color.blue(a) - Color.blue(b)) + abs(Color.alpha(a) - Color.alpha(b)) < 24

    private companion object {
        const val SIZE = 160
        const val DURATION_S = 8f
        const val STEP_S = 0.5f
        const val MIN_EVENT_PIXELS = 12
        // Framing puts the anchor at most ~0.2r off-centre; drawn body stays well inside 0.6r.
        const val MAX_CENTROID_RADIUS = 0.6f
    }
}
