package info.olli.weatherlwp.rendering

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import info.olli.weatherlwp.parallax.ParallaxCalculator
import info.olli.weatherlwp.simulation.SimulationSettings
import info.olli.weatherlwp.simulation.SimulationState
import info.olli.weatherlwp.weather.WeatherType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Regression tests for three reported defects:
 * - rain and fog never rendered because intensity was never driven by simulation state
 * - clouds drifted several times faster than the reported wind
 * - parallax throttled fast movement harder at low frame rates
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class WeatherWindRegressionTest {

    private val width = 360f
    private val height = 640f

    private fun stateFor(
        weather: WeatherType,
        precipIntensity: Float = 0f,
        windStrength: Float = 0.5f,
        windMultiplier: Float = 1f
    ) = SimulationState.default().copy(
        weather = weather,
        precipIntensity = precipIntensity,
        windStrength = windStrength,
        windMultiplier = windMultiplier,
        windDirection = 0f,
        screenWidth = width,
        screenHeight = height
    )

    /** Renders a few frames and returns (pixels changed from black, clearly lit pixels). */
    private fun renderWeather(state: SimulationState): Pair<Int, Int> {
        val layer = WeatherLayer().apply { resize(width, height) }
        val bitmap = Bitmap.createBitmap(width.toInt(), height.toInt(), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.BLACK)
        // A few frames so particles move away from their spawn row.
        repeat(10) { layer.update(state, 16L) }
        layer.draw(canvas, state, ParallaxCalculator().apply { setCanvasSize(width, height) })

        var changed = 0
        var lit = 0
        for (y in 0 until bitmap.height) {
            for (x in 0 until bitmap.width) {
                val pixel = bitmap.getPixel(x, y)
                if (pixel != Color.BLACK) changed++
                // Rain streaks and fog are light blue-white; the rain dimming overlay is not.
                if (Color.blue(pixel) > 80) lit++
            }
        }
        return changed to lit
    }

    // ============== Weather rendering ==============

    @Test
    fun `rain renders even when provider reports zero precipitation probability`() {
        // Gadgetbridge payloads routinely omit precipProbability, which used to leave
        // precipIntensity at 0 and the rain effect invisible.
        val (_, lit) = renderWeather(stateFor(WeatherType.RAIN, precipIntensity = 0f))
        assertTrue("Rain streaks should be visible, got $lit lit pixels", lit > 0)
    }

    @Test
    fun `fog renders when no visibility data is available`() {
        val (_, lit) = renderWeather(stateFor(WeatherType.FOG))
        assertTrue("Fog should be visible, got $lit lit pixels", lit > 0)
    }

    @Test
    fun `seeded weather particles render identically`() {
        fun render(seed: Int): IntArray {
            val layer = WeatherLayer(kotlin.random.Random(seed)).apply { resize(width, height) }
            val bitmap = Bitmap.createBitmap(width.toInt(), height.toInt(), Bitmap.Config.ARGB_8888)
            val state = stateFor(WeatherType.SNOW, precipIntensity = 1f)
            repeat(10) { layer.update(state, 16L) }
            layer.draw(Canvas(bitmap), state, ParallaxCalculator().apply { setCanvasSize(width, height) })
            return IntArray(bitmap.width * bitmap.height).also {
                bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                bitmap.recycle()
            }
        }
        assertTrue(render(7).contentEquals(render(7)))
        assertTrue(!render(7).contentEquals(render(8)))
    }

    @Test
    fun `clear weather draws nothing`() {
        assertEquals(0, renderWeather(stateFor(WeatherType.CLEAR)).first)
    }

    @Test
    fun `heavy rain is denser than light rain`() {
        val light = renderWeather(stateFor(WeatherType.RAIN, precipIntensity = 0.35f)).second
        val heavy = renderWeather(stateFor(WeatherType.RAIN, precipIntensity = 1f)).second
        assertTrue("Heavy rain ($heavy) should cover more than light rain ($light)", heavy > light)
    }

    // ============== Cloud speed ==============

    private fun cloudDrift(windStrength: Float, windMultiplier: Float): Float {
        val layer = CloudLayer(RuntimeEnvironment.getApplication()).apply {
            resize(width, height)
            reloadSettings(SimulationSettings(snapshot = mapOf("pref_clouds_enabled" to true)))
        }
        val group = layer.getCloudGroups().first()
        val state = stateFor(WeatherType.CLEAR, windStrength = windStrength, windMultiplier = windMultiplier)
        val before = group.currentWorldX
        repeat(30) { layer.update(state, 16L) }
        return kotlin.math.abs(group.currentWorldX - before)
    }

    @Test
    fun `cloud speed saturates instead of scaling past the documented maximum`() {
        // pref_wind_multiplier defaults to 200%, which previously pushed cloud speed
        // far beyond WIND_CLOUD_SPEED_MAX and detached it from the reported wind.
        val atMax = cloudDrift(windStrength = 1f, windMultiplier = 1f)
        val wayOverMax = cloudDrift(windStrength = 1f, windMultiplier = 3f)
        assertEquals("Cloud drift must saturate at max wind", atMax, wayOverMax, atMax * 0.02f)
    }

    @Test
    fun `cloud speed still tracks wind below saturation`() {
        val gentle = cloudDrift(windStrength = 0.1f, windMultiplier = 1f)
        val brisk = cloudDrift(windStrength = 0.6f, windMultiplier = 1f)
        assertTrue("Brisk wind ($brisk) should out-run gentle wind ($gentle)", brisk > gentle)
    }

    // ============== Parallax frame-rate independence ==============

    private fun parallaxTravel(frameMs: Long, frames: Int, tiltPerSecond: Float): Float {
        val calc = ParallaxCalculator().apply {
            strength = 1f
            maxOffsetPercent = 20f
            returnSpeed = 0f
            setCanvasSize(width, height)
        }
        var tilt = 0f
        calc.updateFromSensor(tilt, 0f)
        calc.update(frameMs)
        repeat(frames) {
            tilt += tiltPerSecond * (frameMs / 1000f)
            calc.updateFromSensor(tilt, 0f)
            calc.update(frameMs)
        }
        return kotlin.math.abs(calc.getOffsetX())
    }

    @Test
    fun `fast tilt is not throttled harder at 30fps than at 60fps`() {
        // A fast flick: 3 tilt units per second sustained for ~200ms.
        val at60 = parallaxTravel(frameMs = 16L, frames = 12, tiltPerSecond = 3f)
        val at30 = parallaxTravel(frameMs = 33L, frames = 6, tiltPerSecond = 3f)
        assertTrue("30fps travel ($at30) should be within 25% of 60fps travel ($at60)",
            kotlin.math.abs(at30 - at60) <= at60 * 0.25f)
    }

    @Test
    fun `pending sensor motion is reported before the next frame integrates it`() {
        val calc = ParallaxCalculator().apply { setCanvasSize(width, height) }
        calc.updateFromSensor(0f, 0f)
        calc.update(16L)
        calc.updateFromSensor(0.2f, 0f)
        assertTrue("Motion should be pending before update()", calc.hasPendingSensorMotion())
        calc.update(16L)
        assertTrue("Motion should be consumed by update()", !calc.hasPendingSensorMotion())
    }
}
