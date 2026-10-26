package info.olli.weatherlwp.rendering.events

import info.olli.weatherlwp.simulation.CloudDensity
import info.olli.weatherlwp.weather.WeatherType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class EventSpecTest {

    @Test
    fun `spawn order and seed offsets match the legacy spawnRandomEvent`() {
        // Changing either would make a given time bucket spawn a different event.
        val legacy = listOf(
            "airplane" to 0L, "birds" to 1L, "eagle" to 2L, "butterfly" to 3L, "dragonfly" to 11L,
            "balloon" to 4L, "rabbit" to 5L, "fox" to 6L, "squirrel" to 12L, "shootingstar" to 7L,
            "fireflies" to 8L, "owl" to 9L, "snowflake" to 13L, "bat" to 14L, "deer" to 15L,
            "alien" to 10L, "fish" to 16L, "duck" to 17L, "frog" to 18L,
            "perseids" to 19L, "starfall" to 20L
        )
        assertEquals(legacy, EventSpec.entries.map { it.id to it.spawnSeedOffset })
    }

    @Test
    fun `random spawn order keeps legacy entries and appends new types`() {
        val legacy = listOf(
            "airplane", "birds", "eagle", "butterfly", "rabbit", "fox", "shootingstar", "fireflies",
            "owl", "balloon", "alien", "squirrel", "dragonfly", "snowflake", "bat", "deer", "fish",
            "duck", "frog", "perseids", "starfall"
        )
        assertEquals(legacy, EventSpec.DEBUG_SPAWN_ORDER.map { it.id })
    }

    @Test
    fun `ids and pref keys are unique and resolve back`() {
        assertEquals(EventSpec.entries.size, EventSpec.entries.map { it.id }.toSet().size)
        for (spec in EventSpec.entries) {
            assertEquals("pref_event_${spec.id}", spec.prefKey)
            assertSame(spec, EventSpec.fromId(spec.id))
            assertSame(spec, EventSpec.fromPrefKey(spec.prefKey))
        }
        assertEquals(null, EventSpec.fromId("unknown"))
        assertEquals(null, EventSpec.fromPrefKey(null))
    }

    @Test
    fun `squirrel condition draws from the spawn random only outside autumn`() {
        val autumn = conditions(season = "autumn")
        val spring = conditions(season = "spring")

        val a = Random(7)
        assertTrue(EventSpec.SQUIRREL.canSpawn(autumn, a))
        assertEquals(Random(7).nextFloat(), a.nextFloat(), 0f)

        val b = Random(7)
        val expected = Random(7).nextFloat() < 0.3f
        assertEquals(expected, EventSpec.SQUIRREL.canSpawn(spring, b))
        val skipped = Random(7).also { it.nextFloat() }
        assertEquals(skipped.nextFloat(), b.nextFloat(), 0f)
    }

    @Test
    fun `alien condition only draws at night`() {
        val day = Random(3)
        assertFalse(EventSpec.ALIEN.canSpawn(conditions(isNight = false), day))
        assertEquals(Random(3).nextFloat(), day.nextFloat(), 0f)
    }

    @Test
    fun `seasonal and weather conditions`() {
        assertTrue(EventSpec.FROG.canSpawn(conditions(season = "summer", isDusk = true), Random(0)))
        assertFalse(EventSpec.FROG.canSpawn(conditions(season = "autumn", isDusk = true), Random(0)))
        assertFalse(EventSpec.BALLOON.canSpawn(conditions(weather = WeatherType.RAIN), Random(0)))
        assertFalse(EventSpec.SNOWFLAKE.canSpawn(conditions(season = "winter", weather = WeatherType.SNOW), Random(0)))
        assertTrue(EventSpec.SHOOTING_STAR.canSpawn(conditions(isNight = true, clouds = CloudDensity.LIGHT), Random(0)))
    }

    @Test
    fun `createForSpawn seeds with spawn time plus offset`() {
        val t = 1_234_500L
        val spawned = EventSpec.FOX.createForSpawn(t, 0.8f)
        val manual = FoxEvent(Random(t + 6), t, 0.8f)
        assertTrue(spawned is FoxEvent)
        assertEquals(manual.durationMs, spawned.durationMs)
        assertEquals(manual.depthFactor, spawned.depthFactor, 0f)
    }

    private fun conditions(
        isNight: Boolean = false,
        isDusk: Boolean = false,
        season: String = "summer",
        weather: WeatherType = WeatherType.CLEAR,
        clouds: CloudDensity = CloudDensity.LIGHT
    ) = EventSpawnConditions(isNight, isDusk, season, weather, clouds)
}
