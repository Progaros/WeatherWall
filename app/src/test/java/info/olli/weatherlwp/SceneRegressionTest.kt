package info.olli.weatherlwp

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import info.olli.weatherlwp.parallax.ParallaxCalculator
import info.olli.weatherlwp.rendering.CloudLayer
import info.olli.weatherlwp.rendering.MountainLayer
import info.olli.weatherlwp.rendering.SkyLayer
import info.olli.weatherlwp.rendering.TerrainLayer
import info.olli.weatherlwp.simulation.CloudDensity
import info.olli.weatherlwp.simulation.SimulationSettings
import info.olli.weatherlwp.simulation.SimulationState
import info.olli.weatherlwp.testutil.stateAt
import info.olli.weatherlwp.weather.WeatherType
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.math.abs

/**
 * Golden-image regression test: renders the static scene layers (sky, cloud deck, mountains,
 * terrain) for a few fixed states and compares them with the PNGs in
 * src/test/resources/scene_reference. Cloud bitmaps and particles are async/random and left out.
 *
 * After an intended visual change, regenerate with -Dscene.reference.update=true and review the PNGs.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class SceneRegressionTest {

    private val referenceDir = File("src/test/resources/scene_reference")
    private val diffDir = File("build/scene-diff")
    private val update = System.getProperty("scene.reference.update") == "true"

    private val settings = SimulationSettings(snapshot = mapOf(
        "pref_random_seed" to 42,
        "pref_hill_count" to 5,
        "pref_tree_density" to 60,
        "pref_bush_density" to 60,
        "pref_mountain_density" to 50,
        "pref_spring_flower_density" to 600
    ))

    private fun season(state: SimulationState, season: String) =
        state.copy(currentSeason = season, previousSeason = season, seasonTransitionProgress = 1f)

    private fun base(hour: Float, season: String) = season(stateAt(hour), season).copy(
        screenWidth = WIDTH.toFloat(), screenHeight = HEIGHT.toFloat(),
        timeSeconds = 1000f, absoluteTimeMs = 1_700_000_000_000L, shadowStrength = 0.5f
    )

    private val scenes = linkedMapOf(
        "day_summer" to base(14f, "summer").copy(weather = WeatherType.CLEAR, cloudDensity = CloudDensity.LIGHT),
        "dawn_spring_cloudy" to base(5.8f, "spring").copy(weather = WeatherType.CLOUDY, cloudCover = 0.7f),
        "dusk_autumn_rain" to base(19.6f, "autumn").copy(
            weather = WeatherType.RAIN, cloudCover = 0.95f, precipIntensity = 0.8f
        ),
        "snow_winter" to base(11f, "winter").copy(
            weather = WeatherType.SNOW, cloudCover = 0.9f, precipIntensity = 0.8f, snowLevel = 0.8f
        ),
        "night_summer" to base(23f, "summer").copy(
            weather = WeatherType.CLEAR, cloudDensity = CloudDensity.NONE,
            moonPhase = 0.4f, moonIllumination = 0.8f, nightShadeStrength = 0.35f
        )
    )

    @Test
    fun `static scene layers match the reference images`() {
        val failures = mutableListOf<String>()
        val report = StringBuilder()
        for ((name, state) in scenes) {
            val bitmap = render(state)
            val reference = File(referenceDir, "$name.png")
            if (update) {
                referenceDir.mkdirs()
                reference.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                report.appendLine("$name: reference written")
                continue
            }
            if (!reference.exists()) {
                failures += "$name: no reference image"
                continue
            }
            val expected = BitmapFactory.decodeFile(reference.path)
            val (changed, maxDelta) = compare(expected, bitmap)
            val fraction = changed.toFloat() / (WIDTH * HEIGHT)
            report.appendLine("$name: ${changed} px differ (max delta $maxDelta)")
            if (fraction > MAX_CHANGED_FRACTION) {
                diffDir.mkdirs()
                File(diffDir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                failures += "$name: ${"%.2f".format(fraction * 100)}% of pixels changed, max delta $maxDelta"
            }
        }
        println(report)
        assertTrue(
            "Scene renders differ from src/test/resources/scene_reference (actual images in app/build/scene-diff; " +
                "rerun with -Dscene.reference.update=true if the change is intended):\n" + failures.joinToString("\n"),
            failures.isEmpty()
        )
    }

    private fun render(state: SimulationState): Bitmap {
        val context = RuntimeEnvironment.getApplication()
        val w = WIDTH.toFloat()
        val h = HEIGHT.toFloat()
        val layers = listOf(SkyLayer(context), CloudLayer(context), MountainLayer(context), TerrainLayer(context))
        val parallax = ParallaxCalculator().apply { setCanvasSize(w, h) }
        val bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        for (layer in layers) {
            layer.resize(w, h)
            layer.reloadSettings(settings)
            layer.update(state, 16L)
            layer.draw(canvas, state, parallax)
            layer.destroy()
        }
        return bitmap
    }

    /** Pixels whose largest channel difference exceeds [PIXEL_TOLERANCE], and the largest difference seen. */
    private fun compare(expected: Bitmap, actual: Bitmap): Pair<Int, Int> {
        if (expected.width != actual.width || expected.height != actual.height) return WIDTH * HEIGHT to 255
        val a = IntArray(WIDTH * HEIGHT).also { expected.getPixels(it, 0, WIDTH, 0, 0, WIDTH, HEIGHT) }
        val b = IntArray(WIDTH * HEIGHT).also { actual.getPixels(it, 0, WIDTH, 0, 0, WIDTH, HEIGHT) }
        var changed = 0
        var maxDelta = 0
        for (i in a.indices) {
            val p = a[i]
            val q = b[i]
            val d = maxOf(
                abs((p shr 16 and 0xFF) - (q shr 16 and 0xFF)),
                abs((p shr 8 and 0xFF) - (q shr 8 and 0xFF)),
                abs((p and 0xFF) - (q and 0xFF))
            )
            if (d > maxDelta) maxDelta = d
            if (d > PIXEL_TOLERANCE) changed++
        }
        return changed to maxDelta
    }

    private companion object {
        const val WIDTH = 360
        const val HEIGHT = 780
        const val PIXEL_TOLERANCE = 6
        /** Anti-aliasing noise across machines stays far below this. */
        const val MAX_CHANGED_FRACTION = 0.003f
    }
}
