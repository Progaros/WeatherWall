package info.olli.weatherlwp.rendering

import android.graphics.Bitmap
import android.graphics.Canvas
import info.olli.weatherlwp.parallax.ParallaxCalculator
import info.olli.weatherlwp.simulation.SimulationSettings
import info.olli.weatherlwp.simulation.SimulationState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Guards the cached terrain geometry.
 *
 * Hill silhouettes, frost contours and decoration bitmaps are static for a given
 * screen size and settings snapshot. These tests fail if per-frame rebuilds are
 * reintroduced, if cache invalidation stops working, or if the baked decoration
 * bitmaps grow back to full screen height.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class TerrainGeometryCacheTest {

    private val width = 1080f
    private val height = 1920f
    private val hillCount = 5
    private val hillStartY = 0.60f
    private val hillEndY = 0.95f

    private fun settings(vararg overrides: Pair<String, Any?>) = SimulationSettings(
        snapshot = mapOf(
            "pref_random_seed" to 42,
            "pref_hill_count" to hillCount,
            "pref_hill_lower_start" to (hillStartY * 100).toInt(),
            "pref_hill_upper_end" to (hillEndY * 100).toInt(),
            "pref_tree_density" to 23,
            "pref_tree_sparsity" to 74,
            "pref_spring_flowers" to true,
            "pref_spring_flower_density" to 88,
            "pref_spring_flower_spread" to 51,
            "pref_mushrooms_enabled" to true,
            "pref_fallen_leaves_enabled" to true,
            "pref_frost_enabled" to true,
            "pref_winter_snow_hills" to true,
            "pref_weather_snow_enabled" to true
        ) + overrides.toMap()
    )

    private fun newLayer(settings: SimulationSettings = settings()): TerrainLayer =
        TerrainLayer(RuntimeEnvironment.getApplication()).apply {
            resize(width, height)
            reloadSettings(settings)
        }

    private fun winterState(timeSeconds: Float) = SimulationState.default().copy(
        timeOfDayHours = 12f,
        isNight = false, isDawn = false, isDusk = false,
        dayProgress = 0.5f,
        snowLevel = 0.8f,
        currentSeason = "winter",
        screenWidth = width, screenHeight = height,
        timeSeconds = timeSeconds,
        absoluteTimeMs = 1700000000000L
    )

    private fun renderFrames(layer: TerrainLayer, frames: Int, startSeconds: Float = 0f) {
        val bitmap = Bitmap.createBitmap(width.toInt(), height.toInt(), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val parallax = ParallaxCalculator().apply { setCanvasSize(width, height) }
        for (i in 0 until frames) {
            // Advance animation time only; time-of-day stays inside one ambient bucket.
            val state = winterState(startSeconds + i * (1f / 60f))
            layer.update(state, 16L)
            layer.draw(canvas, state, parallax)
        }
        bitmap.recycle()
    }

    // ===================== Steady-state rebuild counters =====================

    @Test
    fun `steady state rendering rebuilds no hill geometry`() {
        val layer = newLayer()
        renderFrames(layer, frames = 5)          // warm-up: first build happens here
        layer.renderStats.reset()

        renderFrames(layer, frames = 600, startSeconds = 100f)

        assertEquals(600L, layer.renderStats.framesRendered)
        assertEquals(
            "Hill geometry must not be rebuilt during steady-state rendering (${layer.renderStats})",
            0L, layer.renderStats.hillGeometryBuilds
        )
        assertEquals(
            "Frost contours must not be rebuilt during steady-state rendering (${layer.renderStats})",
            0L, layer.renderStats.frostPathBuilds
        )
    }

    @Test
    fun `steady state rendering rebakes no decoration bitmaps`() {
        val layer = newLayer()
        renderFrames(layer, frames = 5)
        layer.renderStats.reset()

        renderFrames(layer, frames = 600, startSeconds = 100f)

        assertEquals(
            "Decoration bitmaps must not be re-baked during steady-state rendering (${layer.renderStats})",
            0L, layer.renderStats.decorationBakes
        )
    }

    @Test
    fun `warm up builds hill geometry exactly once`() {
        val layer = newLayer()
        layer.renderStats.reset()

        renderFrames(layer, frames = 120)

        assertEquals(
            "First frame should build geometry once, later frames reuse it (${layer.renderStats})",
            1L, layer.renderStats.hillGeometryBuilds
        )
        assertEquals(
            "One fill path per hill",
            hillCount.toLong(), layer.renderStats.hillPathBuilds
        )
    }

    // ===================== Cache invalidation =====================

    @Test
    fun `resize invalidates cached hill geometry`() {
        val layer = newLayer()
        renderFrames(layer, frames = 5)
        layer.renderStats.reset()

        layer.resize(width, height * 1.25f)
        renderFrames(layer, frames = 5)

        assertEquals(
            "Resize must trigger exactly one geometry rebuild (${layer.renderStats})",
            1L, layer.renderStats.hillGeometryBuilds
        )
    }

    @Test
    fun `identical resize does not invalidate cached hill geometry`() {
        val layer = newLayer()
        renderFrames(layer, frames = 5)
        layer.renderStats.reset()

        layer.resize(width, height)
        renderFrames(layer, frames = 5)

        assertEquals(
            "A no-op resize must not rebuild geometry (${layer.renderStats})",
            0L, layer.renderStats.hillGeometryBuilds
        )
    }

    @Test
    fun `structural settings change invalidates cached hill geometry`() {
        val layer = newLayer()
        renderFrames(layer, frames = 5)
        layer.renderStats.reset()

        layer.reloadSettings(settings("pref_hill_amplitude" to 205))
        renderFrames(layer, frames = 5)

        assertTrue(
            "Changing hill amplitude must rebuild geometry (${layer.renderStats})",
            layer.renderStats.hillGeometryBuilds >= 1L
        )
    }

    @Test
    fun `hill count change rebuilds one path per hill`() {
        val layer = newLayer()
        renderFrames(layer, frames = 5)
        layer.renderStats.reset()

        layer.reloadSettings(settings("pref_hill_count" to 3))
        renderFrames(layer, frames = 5)

        assertEquals(3L, layer.renderStats.hillPathBuilds)
    }

    @Test
    fun `regenerated hill geometry does not reuse stale gradients`() {
        // Hill gradients are built from the cached minY, so changing the hill shape
        // must not leave gradients anchored to the previous silhouette.
        // Vegetation is disabled so only the hill fill is compared.
        fun profile(amplitude: Int) = settings(
            "pref_hill_amplitude" to amplitude,
            "pref_tree_density" to 0,
            "pref_bushes_enabled" to false
        )

        val parallax = ParallaxCalculator().apply { setCanvasSize(width, height) }
        val state = winterState(100f)

        fun render(layer: TerrainLayer): Bitmap {
            val bmp = Bitmap.createBitmap(width.toInt(), height.toInt(), Bitmap.Config.ARGB_8888)
            layer.update(state, 16L)
            layer.draw(Canvas(bmp), state, parallax)
            return bmp
        }

        val reloaded = newLayer(profile(20))
        renderFrames(reloaded, frames = 3)
        reloaded.reloadSettings(profile(300))
        renderFrames(reloaded, frames = 3)
        val switched = render(reloaded)

        val fresh = newLayer(profile(300))
        renderFrames(fresh, frames = 3)
        val reference = render(fresh)

        assertEquals(
            "Reloading hill amplitude must render the same as a freshly built layer",
            0, differingPixels(switched, reference)
        )

        switched.recycle()
        reference.recycle()
    }

    // ===================== Decoration bitmap memory =====================

    @Test
    fun `decoration bitmaps are cropped to the decoration band`() {
        val layer = newLayer()
        renderFrames(layer, frames = 5)

        val actualBytes = layer.renderStats.decorationBitmapBytes
        assertTrue("Decoration bitmaps should have been baked", actualBytes > 0L)

        // Previous behaviour: each hill's bitmap spanned from DECOR_BITMAP_MARGIN
        // above its heightOffset all the way down to the bottom of the screen.
        val bmpW = (width * (1f + 2f * TerrainLayer.DECOR_BITMAP_MARGIN)).toInt()
        var legacyBytes = 0L
        for (i in 0 until hillCount) {
            val heightOffset = hillStartY + (i.toFloat() / hillCount) * (hillEndY - hillStartY)
            val topY = ((heightOffset - TerrainLayer.DECOR_BITMAP_MARGIN)
                .coerceAtLeast(0f) * height)
            legacyBytes += bmpW.toLong() * (height - topY).toInt() * 4L
        }

        assertTrue(
            "Cropped decoration bitmaps ($actualBytes B) should use well under half of " +
                "the previous full-height bitmaps ($legacyBytes B)",
            actualBytes < legacyBytes / 2
        )
    }

    @Test
    fun `decoration band stays within the hill surface range`() {
        // The bitmap band must cover the hill surface plus decoration offsets and no more.
        val bandHeight = TerrainLayer.HILL_SURFACE_MAX - TerrainLayer.HILL_SURFACE_MIN
        assertTrue("Hill surface band must be positive", bandHeight > 0f)
        assertTrue(
            "Hill surface never rises above the hill's heightOffset",
            TerrainLayer.HILL_SURFACE_MIN >= 0f
        )
        assertTrue(
            "Hill surface excursion must stay below the horizontal parallax margin",
            TerrainLayer.HILL_SURFACE_MAX <= TerrainLayer.DECOR_BITMAP_MARGIN
        )
    }

    // ===================== Visual equivalence =====================

    private fun renderOnce(settings: SimulationSettings): Bitmap {
        val layer = TerrainLayer(RuntimeEnvironment.getApplication()).apply {
            resize(width, height)
            reloadSettings(settings)
        }
        val bitmap = Bitmap.createBitmap(width.toInt(), height.toInt(), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val parallax = ParallaxCalculator().apply { setCanvasSize(width, height) }
        val state = winterState(100f)
        layer.update(state, 16L)
        layer.draw(canvas, state, parallax)
        return bitmap
    }

    private fun differingPixels(a: Bitmap, b: Bitmap): Int {
        var diff = 0
        for (y in 0 until a.height step 4) {
            for (x in 0 until a.width step 4) {
                if (a.getPixel(x, y) != b.getPixel(x, y)) diff++
            }
        }
        return diff
    }

    @Test
    fun `frost contour is actually drawn when enabled`() {
        val withFrost = renderOnce(settings("pref_frost_enabled" to true))
        val withoutFrost = renderOnce(settings("pref_frost_enabled" to false))

        val diff = differingPixels(withFrost, withoutFrost)
        assertTrue(
            "Enabling frost must change the rendered output (differing samples=$diff)",
            diff > 0
        )

        withFrost.recycle()
        withoutFrost.recycle()
    }

    @Test
    fun `cached geometry renders identically across frames`() {
        // Trees and bushes sway continuously, so they are disabled here to isolate the
        // cached artefacts: hill fills, frost contours and the baked decoration bitmap.
        val staticScene = settings(
            "pref_tree_density" to 0,
            "pref_bushes_enabled" to false
        )
        val layer = newLayer(staticScene)
        val parallax = ParallaxCalculator().apply { setCanvasSize(width, height) }
        val state = winterState(100f)

        val first = Bitmap.createBitmap(width.toInt(), height.toInt(), Bitmap.Config.ARGB_8888)
        layer.update(state, 16L)
        layer.draw(Canvas(first), state, parallax)

        // Re-render the same simulation state after many frames of cache reuse.
        renderFrames(layer, frames = 200, startSeconds = 200f)
        val later = Bitmap.createBitmap(width.toInt(), height.toInt(), Bitmap.Config.ARGB_8888)
        layer.update(state, 16L)
        layer.draw(Canvas(later), state, parallax)

        assertEquals(
            "Cached hill/frost/decoration output must be byte-stable across frames",
            0, differingPixels(first, later)
        )
        assertEquals(
            "No rebuilds should have occurred while re-rendering (${layer.renderStats})",
            1L, layer.renderStats.hillGeometryBuilds
        )

        first.recycle()
        later.recycle()
    }
}
