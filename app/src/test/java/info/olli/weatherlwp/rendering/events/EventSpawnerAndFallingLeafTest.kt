package info.olli.weatherlwp.rendering.events

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import info.olli.weatherlwp.parallax.ParallaxCalculator
import info.olli.weatherlwp.rendering.TerrainLayer
import info.olli.weatherlwp.simulation.CloudDensity
import info.olli.weatherlwp.simulation.SimulationSettings
import info.olli.weatherlwp.simulation.SimulationState
import info.olli.weatherlwp.weather.WeatherType
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.random.Random

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EventSpawnerAndFallingLeafTest {

    private fun autumnDayState(timeMs: Long) = SimulationState.default().copy(
        screenWidth = 1080f,
        screenHeight = 1920f,
        isNight = false,
        isDusk = false,
        weather = WeatherType.CLEAR,
        cloudDensity = CloudDensity.LIGHT,
        currentSeason = "autumn",
        previousSeason = "autumn",
        seasonTransitionProgress = 1f,
        absoluteTimeMs = timeMs
    )

    @Test
    fun `newly started spawner catches up in-flight deterministic spawns to match running spawner`() {
        val enabled = EventSpec.entries.map { it.id }.toSet()
        val startMs = 1_700_000_000_000L
        val running = EventSpawner()
        val runningSpawns = mutableListOf<EventSpawner.SpawnKey>()

        // Advance continuously over 20 seconds (40 buckets)
        for (i in 0..40) {
            val t = startMs + i * EventSpawner.BUCKET_MS
            val reqs = running.collectAutomaticSpawns(
                state = autumnDayState(t),
                season = "autumn",
                spawnChance = 0.25f,
                enabledEvents = enabled,
                meteorShowersEnabled = false
            )
            runningSpawns.addAll(reqs.map { it.key })
        }

        // A freshly created spawner (e.g. preview opened later) starting at startMs + 20s
        val lateJoiner = EventSpawner()
        val lateSpawns = lateJoiner.collectAutomaticSpawns(
            state = autumnDayState(startMs + 40 * EventSpawner.BUCKET_MS),
            season = "autumn",
            spawnChance = 0.25f,
            enabledEvents = enabled,
            meteorShowersEnabled = false
        ).map { it.key }.toSet()

        val recentRunningSpawns = runningSpawns.filter { it.spawnTimeMs >= startMs }
        assertTrue("Expected at least one deterministic spawn in 20s window", recentRunningSpawns.isNotEmpty())
        for (key in recentRunningSpawns) {
            assertTrue("Late-joining scene missed deterministic spawn $key", key in lateSpawns)
        }
    }

    @Test
    fun `idle jump across multiple buckets catches up without duplicating`() {
        val enabled = setOf(EventSpec.FALLING_LEAF.id)
        val spawner = EventSpawner()
        val baseMs = 1_700_000_100_000L

        // Initialize at baseMs
        spawner.collectAutomaticSpawns(autumnDayState(baseMs), "autumn", 0.5f, enabled, false)

        // Jump ahead 10 buckets (5 seconds of idle sleep)
        val jumped = spawner.collectAutomaticSpawns(
            autumnDayState(baseMs + 10 * EventSpawner.BUCKET_MS), "autumn", 0.5f, enabled, false
        )
        assertTrue("Idle catch-up should collect spawns from skipped buckets", jumped.isNotEmpty())
        assertEquals("No duplicate spawn keys", jumped.size, jumped.map { it.key }.toSet().size)

        // Re-evaluating the same bucket returns nothing
        val repeat = spawner.collectAutomaticSpawns(
            autumnDayState(baseMs + 10 * EventSpawner.BUCKET_MS), "autumn", 0.5f, enabled, false
        )
        assertTrue(repeat.isEmpty())
    }

    @Test
    fun `manual spawn entries round-trip optional spawn positions`() {
        val withDepth = ManualEventSpawns.Entry("fallingleaf", 1000L, EventSpawnPosition(0.42f, 0.31f, 0.65f))
        val withoutDepth = ManualEventSpawns.Entry("fallingleaf", 2000L, EventSpawnPosition(0.55f, 0.40f, null))
        val plain = ManualEventSpawns.Entry("fox", 3000L)

        val formatted = ManualEventSpawns.format(listOf(withDepth, withoutDepth, plain))
        assertTrue("Plain entry keeps legacy format", formatted.endsWith(",fox@3000"))

        val parsed = ManualEventSpawns.parse(formatted)
        assertEquals(listOf(withDepth, withoutDepth, plain), parsed)
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `falling leaf honors explicit spawn position and renders visible pixels`() {
        val pos = EventSpawnPosition(x = 0.48f, y = 0.35f, depthFactor = 0.68f)
        val leaf = FallingLeafEvent(Random(42L), spawnTimeMs = 10_000L, spawnPosition = pos)

        assertEquals(EventLayer.GROUND, leaf.layer)
        assertEquals(0.68f, leaf.depthFactor, 0.001f)
        assertEquals(0.35f, leaf.getCurrentYRatio(10_000L, { _, _ -> 0.80f }, 1920f), 0.02f)

        val yLater = leaf.getCurrentYRatio(13_000L, { _, _ -> 0.80f }, 1920f)
        assertTrue("Leaf should descend over time (start=0.35, later=$yLater)", yLater > 0.42f)

        val bitmap = Bitmap.createBitmap(400, 600, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val calc = ParallaxCalculator().apply { setCanvasSize(400f, 600f); reset() }
        try {
            leaf.draw(
                canvas, Paint(Paint.ANTI_ALIAS_FLAG), Paint(Paint.ANTI_ALIAS_FLAG),
                calc, 400f, 600f, 10f, Color.WHITE, 0f, 12_000L, { _, _ -> 0.80f }, 0f
            )
            var drawn = 0
            for (y in 0 until 600) for (x in 0 until 400) {
                if (Color.alpha(bitmap.getPixel(x, y)) > 10) drawn++
            }
            assertTrue("FallingLeafEvent should render visible leaf pixels (got $drawn)", drawn > 20)
        } finally {
            bitmap.recycle()
        }
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `terrain generates dense baked fallen leaves and deterministic tree crown spawns`() {
        val layer = TerrainLayer(RuntimeEnvironment.getApplication())
        val settings = SimulationSettings.fromSnapshot(mapOf(
            "pref_random_seed" to 42,
            "pref_hill_count" to 5,
            "pref_tree_density" to 80,
            "pref_leaf_tree_ratio" to 70,
            "pref_fallen_leaves_enabled" to true,
            "pref_fallen_leaves_density" to 50
        ))
        try {
            layer.resize(1080f, 1920f)
            layer.reloadSettings(settings)

            assertTrue(
                "Expected dense fallen leaf count for lush hill carpet, got ${layer.fallenLeafCount()}",
                layer.fallenLeafCount() >= 300
            )

            val spawn1 = layer.pickTreeCrownSpawn(Random(1234L))
            val spawn2 = layer.pickTreeCrownSpawn(Random(1234L))
            assertNotNull("Should pick a visible tree crown when trees exist", spawn1)
            assertEquals("Tree crown selection must be deterministic for preview/wallpaper sync", spawn1, spawn2)
        } finally {
            layer.destroy()
        }
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `leaf density 100 carpets the foreground hill in autumn leaves`() {
        val layer = TerrainLayer(RuntimeEnvironment.getApplication())
        val baseSettings = mapOf(
            "pref_random_seed" to 42,
            "pref_hill_count" to 5,
            "pref_tree_density" to 0,
            "pref_bush_density" to 0,
            "pref_autumn_grass" to false,
            "pref_mushrooms_enabled" to false,
            "pref_fallen_leaves_enabled" to true
        )
        val state = autumnDayState(1_700_000_000_000L).copy(screenWidth = 360f, screenHeight = 640f)
        val calc = ParallaxCalculator().apply { setCanvasSize(360f, 640f); reset() }
        val bmpOff = Bitmap.createBitmap(360, 640, Bitmap.Config.ARGB_8888)
        val bmpFull = Bitmap.createBitmap(360, 640, Bitmap.Config.ARGB_8888)
        try {
            layer.resize(360f, 640f)
            layer.reloadSettings(SimulationSettings.fromSnapshot(baseSettings + ("pref_fallen_leaves_enabled" to false)))
            layer.update(state, 16L)
            layer.draw(Canvas(bmpOff), state, calc)

            layer.reloadSettings(SimulationSettings.fromSnapshot(baseSettings + ("pref_fallen_leaves_density" to 100)))
            layer.update(state, 16L)
            layer.draw(Canvas(bmpFull), state, calc)

            // Sample bottom band (foreground hill surface): density 100 must cover virtually all of it.
            var total = 0
            var changed = 0
            for (y in 560 until 630) {
                for (x in 20 until 340) {
                    val p0 = bmpOff.getPixel(x, y)
                    val p1 = bmpFull.getPixel(x, y)
                    if (Color.alpha(p0) < 200) continue
                    total++
                    if (p0 != p1) changed++
                }
            }
            val coverage = changed.toFloat() / total.coerceAtLeast(1)
            assertTrue("Expected near-full leaf carpet coverage at density=100, got ${(coverage * 100).toInt()}%",
                coverage > 0.90f)
        } finally {
            layer.destroy()
            bmpOff.recycle()
            bmpFull.recycle()
        }
    }
}
