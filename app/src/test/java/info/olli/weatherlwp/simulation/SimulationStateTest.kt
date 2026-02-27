package info.olli.weatherlwp.simulation

import info.olli.weatherlwp.weather.WeatherType
import org.junit.Assert.*
import org.junit.Test

/**
 * Tests for SimulationState immutable data class.
 */
class SimulationStateTest {

    @Test
    fun `default state is created with sane values`() {
        val state = SimulationState.default()
        
        assertEquals(12f, state.timeOfDayHours, 0.01f)
        assertEquals(0.5f, state.dayProgress, 0.01f)
        assertFalse(state.isNight)
        assertFalse(state.isDawn)
        assertFalse(state.isDusk)
        assertEquals(WeatherType.CLEAR, state.weather)
        assertEquals(CloudDensity.AUTO, state.cloudDensity)
        assertEquals(0.5f, state.windStrength, 0.01f)
        assertEquals(0f, state.windDirection, 0.01f)
        assertEquals(1080f, state.screenWidth, 0.01f)
        assertEquals(1920f, state.screenHeight, 0.01f)
        assertFalse(state.isDebugMode)
    }

    @Test
    fun `getWindX returns correct value for right wind`() {
        val state = SimulationState.default().copy(
            windStrength = 1f,
            windDirection = 0f
        )
        
        assertEquals(1f, state.getWindX(), 0.01f)
        assertEquals(0f, state.getWindY(), 0.01f)
    }

    @Test
    fun `getWindY returns correct value for up wind`() {
        val state = SimulationState.default().copy(
            windStrength = 1f,
            windDirection = 90f
        )
        
        assertEquals(0f, state.getWindX(), 0.01f)
        assertEquals(1f, state.getWindY(), 0.01f)
    }

    @Test
    fun `getCloudCount returns 0 for NONE density`() {
        val state = SimulationState.default().copy(cloudDensity = CloudDensity.NONE)
        assertEquals(0, state.getCloudCount())
    }

    @Test
    fun `getCloudCount returns 3 for LIGHT density`() {
        val state = SimulationState.default().copy(cloudDensity = CloudDensity.LIGHT)
        assertEquals(3, state.getCloudCount())
    }

    @Test
    fun `getCloudCount returns 10 for MEDIUM density`() {
        val state = SimulationState.default().copy(cloudDensity = CloudDensity.MEDIUM)
        assertEquals(10, state.getCloudCount())
    }

    @Test
    fun `getCloudCount returns 25 for HEAVY density`() {
        val state = SimulationState.default().copy(cloudDensity = CloudDensity.HEAVY)
        assertEquals(25, state.getCloudCount())
    }

    @Test
    fun `getCloudCount returns 50 for OVERCAST density`() {
        val state = SimulationState.default().copy(cloudDensity = CloudDensity.OVERCAST)
        assertEquals(50, state.getCloudCount())
    }

    @Test
    fun `getCloudCount for AUTO depends on weather - CLEAR with cloudCover`() {
        // CLEAR with 10% cloud cover = 2 clouds
        val state = SimulationState.default().copy(
            cloudDensity = CloudDensity.AUTO,
            weather = WeatherType.CLEAR,
            cloudCover = 0.1f
        )
        assertEquals(2, state.getCloudCount())
    }

    @Test
    fun `getCloudCount for AUTO depends on weather - PARTLY_CLOUDY uses cloudCover`() {
        // PARTLY_CLOUDY with 40% cloud cover = ~11 clouds
        val state = SimulationState.default().copy(
            cloudDensity = CloudDensity.AUTO,
            weather = WeatherType.PARTLY_CLOUDY,
            cloudCover = 0.4f
        )
        assertTrue(state.getCloudCount() in 10..14)
    }

    @Test
    fun `getCloudCount for AUTO depends on weather - CLOUDY uses cloudCover`() {
        // CLOUDY with 70% cloud cover = ~31 clouds
        val state = SimulationState.default().copy(
            cloudDensity = CloudDensity.AUTO,
            weather = WeatherType.CLOUDY,
            cloudCover = 0.7f
        )
        assertTrue(state.getCloudCount() in 28..35)
    }

    @Test
    fun `getCloudCount for AUTO depends on weather - OVERCAST uses cloudCover`() {
        // OVERCAST with 95% cloud cover = ~48 clouds
        val state = SimulationState.default().copy(
            cloudDensity = CloudDensity.AUTO,
            weather = WeatherType.OVERCAST,
            cloudCover = 0.95f
        )
        assertTrue(state.getCloudCount() in 45..50)
    }

    @Test
    fun `getCloudCount for AUTO - RAIN uses cloudCover not forced clouds`() {
        // RAIN with 80% cloud cover uses cloudCover, not forced 45
        val state = SimulationState.default().copy(
            cloudDensity = CloudDensity.AUTO,
            weather = WeatherType.RAIN,
            cloudCover = 0.8f
        )
        assertTrue(state.getCloudCount() in 36..42)
    }
    
    @Test
    fun `getCloudCount for AUTO - RAIN with low cloudCover has few clouds`() {
        // RAIN with only 20% cloud cover = few clouds (not forced 45)
        val state = SimulationState.default().copy(
            cloudDensity = CloudDensity.AUTO,
            weather = WeatherType.RAIN,
            cloudCover = 0.2f
        )
        assertTrue(state.getCloudCount() in 4..6)
    }

    @Test
    fun `getCloudCount for AUTO depends on weather - THUNDERSTORM always forces clouds`() {
        // THUNDERSTORM always forces 45 clouds regardless of cloudCover
        val state = SimulationState.default().copy(
            cloudDensity = CloudDensity.AUTO,
            weather = WeatherType.THUNDERSTORM,
            cloudCover = 0.1f  // Low cloudCover, but THUNDERSTORM ignores it
        )
        assertEquals(45, state.getCloudCount())
    }

    @Test
    fun `getCloudCount for AUTO - SNOW uses cloudCover not forced clouds`() {
        // SNOW with 50% cloud cover uses cloudCover, not forced 30
        val state = SimulationState.default().copy(
            cloudDensity = CloudDensity.AUTO,
            weather = WeatherType.SNOW,
            cloudCover = 0.5f
        )
        assertTrue(state.getCloudCount() in 14..18)
    }

    @Test
    fun `getCloudCount for AUTO depends on weather - FOG always forces clouds`() {
        // FOG always forces 50 clouds for atmospheric effect
        val state = SimulationState.default().copy(
            cloudDensity = CloudDensity.AUTO,
            weather = WeatherType.FOG
        )
        assertEquals(50, state.getCloudCount())
    }

    @Test
    fun `state is immutable - copy creates new instance`() {
        val state1 = SimulationState.default()
        val state2 = state1.copy(windStrength = 0.8f)
        
        assertNotSame(state1, state2)
        assertEquals(0.5f, state1.windStrength, 0.01f)
        assertEquals(0.8f, state2.windStrength, 0.01f)
    }

    @Test
    fun `absoluteTimeMs is captured correctly`() {
        val beforeTime = System.currentTimeMillis()
        val state = SimulationState.default()
        val afterTime = System.currentTimeMillis()
        
        assertTrue(state.absoluteTimeMs >= beforeTime)
        assertTrue(state.absoluteTimeMs <= afterTime)
    }

    @Test
    fun `diagonal wind has both X and Y components`() {
        val state = SimulationState.default().copy(
            windStrength = 1f,
            windDirection = 45f
        )
        
        // At 45 degrees, both components should be ~0.707
        val expected = kotlin.math.sqrt(2f) / 2f
        assertEquals(expected, state.getWindX(), 0.01f)
        assertEquals(expected, state.getWindY(), 0.01f)
    }

    @Test
    fun `zero wind strength gives zero components`() {
        val state = SimulationState.default().copy(
            windStrength = 0f,
            windDirection = 45f
        )
        
        assertEquals(0f, state.getWindX(), 0.01f)
        assertEquals(0f, state.getWindY(), 0.01f)
    }
    
    // ============== Light Level Tests ==============
    // Note: Default sunrise=6:00, sunset=20:00, transition=1 hour
    // Dawn: 5:00-6:00, Day: 6:00-20:00, Dusk: 20:00-21:00
    
    @Test
    fun `getLightLevel returns low value at midnight`() {
        val state = SimulationState.default().copy(timeOfDayHours = 0f)
        assertEquals(0.15f, state.getLightLevel(), 0.01f)
    }
    
    @Test
    fun `getLightLevel returns low value at 3am`() {
        val state = SimulationState.default().copy(timeOfDayHours = 3f)
        assertEquals(0.15f, state.getLightLevel(), 0.01f)
    }
    
    @Test
    fun `getLightLevel transitions during dawn with default sun times`() {
        // At 5am, just starting dawn (default sunrise=6, so dawn starts at 5)
        val state5am = SimulationState.default().copy(timeOfDayHours = 5f)
        assertEquals(0.15f, state5am.getLightLevel(), 0.01f)
        
        // At 5:30am, halfway through dawn transition (smoothStep gives ~0.5)
        val state530am = SimulationState.default().copy(timeOfDayHours = 5.5f)
        assertTrue("Light at 5:30am should be > 0.4", state530am.getLightLevel() > 0.4f)
        assertTrue("Light at 5:30am should be < 0.7", state530am.getLightLevel() < 0.7f)
        
        // At 6am, dawn complete - start of day
        val state6am = SimulationState.default().copy(timeOfDayHours = 6f)
        assertEquals(1f, state6am.getLightLevel(), 0.01f)
    }
    
    @Test
    fun `getLightLevel returns full value during day`() {
        val stateNoon = SimulationState.default().copy(timeOfDayHours = 12f)
        assertEquals(1f, stateNoon.getLightLevel(), 0.01f)
        
        val state3pm = SimulationState.default().copy(timeOfDayHours = 15f)
        assertEquals(1f, state3pm.getLightLevel(), 0.01f)
        
        // 17:00 is now daytime (before sunset at 20:00)
        val state5pm = SimulationState.default().copy(timeOfDayHours = 17f)
        assertEquals(1f, state5pm.getLightLevel(), 0.01f)
    }
    
    @Test
    fun `getLightLevel transitions during dusk with default sun times`() {
        // At 19:59, still day (sunset at 20:00)
        val state759pm = SimulationState.default().copy(timeOfDayHours = 19.9f)
        assertEquals(1f, state759pm.getLightLevel(), 0.01f)
        
        // At 20:30, halfway through dusk transition (smoothStep gives ~0.5)
        val state830pm = SimulationState.default().copy(timeOfDayHours = 20.5f)
        assertTrue("Light at 20:30 should be > 0.4", state830pm.getLightLevel() > 0.4f)
        assertTrue("Light at 20:30 should be < 0.7", state830pm.getLightLevel() < 0.7f)
        
        // At 21:00, dusk complete - night begins
        val state9pm = SimulationState.default().copy(timeOfDayHours = 21f)
        assertEquals(0.15f, state9pm.getLightLevel(), 0.01f)
    }
    
    @Test
    fun `getLightLevel returns low value at 11pm`() {
        val state = SimulationState.default().copy(timeOfDayHours = 23f)
        assertEquals(0.15f, state.getLightLevel(), 0.01f)
    }
    
    @Test
    fun `getLightLevel uses dynamic sunrise sunset times`() {
        // Winter day: sunrise at 7:30, sunset at 15:30
        val winterState = SimulationState.default().copy(
            timeOfDayHours = 16f,  // After winter sunset
            sunriseHours = 7.5f,
            sunsetHours = 15.5f
        )
        // At 16:00 with sunset at 15:30, we're in dusk transition
        assertTrue("Winter 16:00 should be in dusk (< 1.0)", winterState.getLightLevel() < 1f)
        assertTrue("Winter 16:00 should still have some light (> 0.15)", winterState.getLightLevel() > 0.15f)
        
        // Summer day: sunrise at 5:00, sunset at 21:30
        val summerState = SimulationState.default().copy(
            timeOfDayHours = 21f,  // Still daylight in summer
            sunriseHours = 5f,
            sunsetHours = 21.5f
        )
        assertEquals(1f, summerState.getLightLevel(), 0.01f)
    }
    
    @Test
    fun `getLightLevel uses smoothStep for natural transitions`() {
        // Test that transition is non-linear (smoothStep has S-curve)
        // At 25% progress through dawn, light should be less than 25% (slow start)
        val state525am = SimulationState.default().copy(timeOfDayHours = 5.25f)
        val expectedLinear = 0.15f + 0.25f * 0.85f  // ~0.36
        val actual = state525am.getLightLevel()
        assertTrue("SmoothStep should start slow (actual=$actual)", actual < expectedLinear)
        
        // At 75% progress, light should be more than 75% (slow end)
        val state575am = SimulationState.default().copy(timeOfDayHours = 5.75f)
        val expectedLinear75 = 0.15f + 0.75f * 0.85f  // ~0.79
        val actual75 = state575am.getLightLevel()
        assertTrue("SmoothStep should accelerate then decelerate (actual=$actual75)", actual75 > expectedLinear75 * 0.9f)
    }
    
    // ============== Atmosphere Phase Tests ==============
    
    @Test
    fun `getAtmospherePhase returns day during midday`() {
        val state = SimulationState.default().copy(timeOfDayHours = 12f)
        assertEquals(1.0f, state.getAtmospherePhase(), 0.01f)
    }
    
    @Test
    fun `getAtmospherePhase returns golden hour at sunrise`() {
        // Just after sunrise (6:00), should be golden hour
        val state = SimulationState.default().copy(timeOfDayHours = 6.3f)
        assertEquals(0.0f, state.getAtmospherePhase(), 0.01f)
    }
    
    @Test
    fun `getAtmospherePhase returns golden hour before sunset`() {
        // 45 min before sunset (20:00), should be golden hour
        val state = SimulationState.default().copy(timeOfDayHours = 19.5f)
        assertEquals(0.0f, state.getAtmospherePhase(), 0.01f)
    }
    
    @Test
    fun `getAtmospherePhase returns blue hour before sunrise`() {
        // 30 min before sunrise (6:00), should be blue hour
        val state = SimulationState.default().copy(timeOfDayHours = 5.7f)
        assertEquals(-0.5f, state.getAtmospherePhase(), 0.01f)
    }
    
    @Test
    fun `getAtmospherePhase returns blue hour after sunset`() {
        // Just after sunset (20:00), should be blue hour
        val state = SimulationState.default().copy(timeOfDayHours = 20.2f)
        assertEquals(-0.5f, state.getAtmospherePhase(), 0.01f)
    }
    
    @Test
    fun `getAtmospherePhase returns night at midnight`() {
        val state = SimulationState.default().copy(timeOfDayHours = 0f)
        assertEquals(-1.0f, state.getAtmospherePhase(), 0.01f)
    }
    
    @Test
    fun `getAtmospherePhase returns night late at night`() {
        val state = SimulationState.default().copy(timeOfDayHours = 3f)
        assertEquals(-1.0f, state.getAtmospherePhase(), 0.01f)
    }
    
    // ============== Shadow Tests ==============
    
    @Test
    fun `getEffectiveShadowStrength scales with light level at midday`() {
        val state = SimulationState.default().copy(
            mountainShadowsEnabled = true,
            treeShadowsEnabled = true,
            shadowStrength = 0.5f,
            timeOfDayHours = 12f
        )
        // Light level at noon = 1.0, so effective = 0.5 * 1.0 = 0.5
        assertEquals(0.5f, state.getEffectiveShadowStrength(), 0.01f)
    }
    
    @Test
    fun `getEffectiveShadowStrength scales with light level at night`() {
        val state = SimulationState.default().copy(
            mountainShadowsEnabled = true,
            treeShadowsEnabled = true,
            shadowStrength = 1f,
            timeOfDayHours = 0f  // Midnight
        )
        // Light level at midnight = 0.15, so effective = 1.0 * 0.15 = 0.15
        assertEquals(0.15f, state.getEffectiveShadowStrength(), 0.01f)
    }
    
    @Test
    fun `default state has shadows enabled`() {
        val state = SimulationState.default()
        assertTrue(state.shadowsEnabled)  // Combined getter from mountain || tree
        assertTrue(state.mountainShadowsEnabled)
        assertTrue(state.treeShadowsEnabled)
        assertEquals(0.5f, state.shadowStrength, 0.01f)
    }
    
    // ============== Moon Phase Default Tests ==============
    
    @Test
    fun `default state has full moon`() {
        val state = SimulationState.default()
        // Default moonPhase should be 0.5 (full moon)
        assertEquals("Default moon should be full (0.5)", 0.5f, state.moonPhase, 0.01f)
    }
    
    // ============== Weather History Fields Tests ==============
    
    @Test
    fun `default state has zero snow level`() {
        val state = SimulationState.default()
        assertEquals(0f, state.snowLevel, 0.01f)
    }
    
    @Test
    fun `default state has zero river level`() {
        val state = SimulationState.default()
        assertEquals(0f, state.riverLevel, 0.01f)
    }
    
    @Test
    fun `default state has NaN temperature`() {
        val state = SimulationState.default()
        assertTrue(state.temperatureCelsius.isNaN())
    }
    
    @Test
    fun `snow level can be set between 0 and 1`() {
        val state = SimulationState.default().copy(snowLevel = 0.75f)
        assertEquals(0.75f, state.snowLevel, 0.01f)
    }
    
    @Test
    fun `river level can be set between 0 and 1`() {
        val state = SimulationState.default().copy(riverLevel = 0.4f)
        assertEquals(0.4f, state.riverLevel, 0.01f)
    }
    
    @Test
    fun `temperature can be set`() {
        val state = SimulationState.default().copy(temperatureCelsius = -5f)
        assertEquals(-5f, state.temperatureCelsius, 0.01f)
    }
    
    // ============== Season Transition Fields Tests ==============
    
    @Test
    fun `default state has full season transition progress`() {
        val state = SimulationState.default()
        assertEquals(1f, state.seasonTransitionProgress, 0.01f)
    }
    
    @Test
    fun `default state has summer as current season`() {
        val state = SimulationState.default()
        assertEquals("summer", state.currentSeason)
    }
    
    @Test
    fun `season transition fields can be customized`() {
        val state = SimulationState.default().copy(
            seasonTransitionProgress = 0.6f,
            currentSeason = "autumn",
            previousSeason = "summer",
            seasonTransitionDays = 21
        )
        assertEquals(0.6f, state.seasonTransitionProgress, 0.01f)
        assertEquals("autumn", state.currentSeason)
        assertEquals("summer", state.previousSeason)
        assertEquals(21, state.seasonTransitionDays)
    }
    
    @Test
    fun `moonPhase of 0 is new moon`() {
        val state = SimulationState.default().copy(moonPhase = 0f)
        assertEquals(0f, state.moonPhase, 0.01f)
    }
    
    @Test
    fun `moonPhase of 1 is also new moon (cycle end)`() {
        val state = SimulationState.default().copy(moonPhase = 1f)
        assertEquals(1f, state.moonPhase, 0.01f)
    }
    
    // ============== CloudCover to CloudCount Mapping Tests ==============
    
    @Test
    fun `cloudCover 0 percent gives 0 clouds`() {
        val state = SimulationState.default().copy(
            cloudDensity = CloudDensity.AUTO,
            weather = WeatherType.CLEAR,
            cloudCover = 0f
        )
        assertEquals(0, state.getCloudCount())
    }
    
    @Test
    fun `cloudCover 5 percent gives 1 cloud`() {
        val state = SimulationState.default().copy(
            cloudDensity = CloudDensity.AUTO,
            weather = WeatherType.CLEAR,
            cloudCover = 0.05f
        )
        assertEquals(1, state.getCloudCount())
    }
    
    @Test
    fun `cloudCover 25 percent gives moderate clouds`() {
        val state = SimulationState.default().copy(
            cloudDensity = CloudDensity.AUTO,
            weather = WeatherType.PARTLY_CLOUDY,
            cloudCover = 0.25f
        )
        assertTrue("25% cover should give 5-7 clouds", state.getCloudCount() in 5..7)
    }
    
    @Test
    fun `cloudCover 100 percent gives maximum clouds`() {
        val state = SimulationState.default().copy(
            cloudDensity = CloudDensity.AUTO,
            weather = WeatherType.OVERCAST,
            cloudCover = 1f
        )
        // 100% coverage should give max clouds (>=48 for >80% sky coverage)
        assertTrue("100% cover should give near-max clouds", state.getCloudCount() >= 48)
    }
    
    @Test
    fun `HAIL uses cloudCover like rain and snow`() {
        // HAIL with 60% cloud cover uses cloudCover, not forced clouds
        val state = SimulationState.default().copy(
            cloudDensity = CloudDensity.AUTO,
            weather = WeatherType.HAIL,
            cloudCover = 0.6f
        )
        assertTrue("HAIL at 60% cover should be ~22 clouds", state.getCloudCount() in 18..25)
    }
    
    // ============== Visual Cloud Coverage Tests ==============
    
    @Test
    fun `light clouds leave sky mostly free`() {
        // With 10% cloud cover, should have very few clouds (sky mostly free)
        val state = SimulationState.default().copy(
            cloudDensity = CloudDensity.AUTO,
            weather = WeatherType.PARTLY_CLOUDY,
            cloudCover = 0.1f
        )
        // Less than 5 clouds = sky mostly free
        assertTrue("10% cover should leave sky mostly free", state.getCloudCount() <= 5)
    }
    
    @Test
    fun `overcast has greater than 80 percent sky coverage`() {
        // Overcast with 90%+ cloud cover should have >80% sky coverage
        // 50 clouds = full coverage, so need >= 40 for >80%
        val state = SimulationState.default().copy(
            cloudDensity = CloudDensity.AUTO,
            weather = WeatherType.OVERCAST,
            cloudCover = 0.90f
        )
        // At 90% cover, should have 45+ clouds for >80% visual coverage
        assertTrue("90% cover should give >80% visual coverage (40+ clouds)", state.getCloudCount() >= 40)
    }
    
    @Test
    fun `LIGHT density keeps sky mostly free`() {
        val state = SimulationState.default().copy(cloudDensity = CloudDensity.LIGHT)
        // LIGHT density = 3 clouds, sky mostly free
        assertTrue("LIGHT density should have <=5 clouds", state.getCloudCount() <= 5)
    }
    
    @Test
    fun `OVERCAST density covers more than 80 percent sky`() {
        val state = SimulationState.default().copy(cloudDensity = CloudDensity.OVERCAST)
        // OVERCAST density = 50 clouds = full sky coverage (>80%)
        assertTrue("OVERCAST should have 40+ clouds for >80% coverage", state.getCloudCount() >= 40)
    }
    
    // ============== Dynamic Sun Times Tests ==============
    
    @Test
    fun `default sunrise is 6am and sunset is 8pm`() {
        val state = SimulationState.default()
        assertEquals(6.0f, state.sunriseHours, 0.01f)
        assertEquals(20.0f, state.sunsetHours, 0.01f)
    }
    
    @Test
    fun `custom sunrise and sunset can be set`() {
        val state = SimulationState.default().copy(
            sunriseHours = 7.05f,  // 7:03 AM
            sunsetHours = 15.58f  // 3:35 PM (winter)
        )
        assertEquals(7.05f, state.sunriseHours, 0.01f)
        assertEquals(15.58f, state.sunsetHours, 0.01f)
    }
    
    @Test
    fun `isGoldenHour and isBlueHour can be set`() {
        val state = SimulationState.default().copy(
            isGoldenHour = true,
            isBlueHour = false
        )
        assertTrue(state.isGoldenHour)
        assertFalse(state.isBlueHour)
    }
    
    @Test
    fun `winter day with short daylight uses correct light levels`() {
        // Heimstetten January: sunrise 7:03, sunset 15:35
        val sunrise = 7.05f
        val sunset = 15.58f
        
        // At 16:00, should be in dusk transition (sunset was at 15:35)
        // Dusk: 15.58 to 16.58, at 16:00 progress = (16 - 15.58) / 1 = 0.42
        val state4pm = SimulationState.default().copy(
            timeOfDayHours = 16f,
            sunriseHours = sunrise,
            sunsetHours = sunset
        )
        assertTrue("Winter 16:00 after sunset should be dim", state4pm.getLightLevel() < 0.8f)
        assertTrue("Winter 16:00 still has some twilight", state4pm.getLightLevel() > 0.15f)
        
        // At 6:30, before sunrise (7:05) - should be in dawn transition
        // Dawn: 6.05 to 7.05, at 6:30 progress = (6.5 - 6.05) / 1 = 0.45
        val state630am = SimulationState.default().copy(
            timeOfDayHours = 6.5f,
            sunriseHours = sunrise,
            sunsetHours = sunset
        )
        assertTrue("Winter 6:30 during dawn should be dim", state630am.getLightLevel() < 0.8f)
        assertTrue("Winter 6:30 in dawn should have some light", state630am.getLightLevel() > 0.15f)
        
        // At noon, full daylight
        val stateNoon = SimulationState.default().copy(
            timeOfDayHours = 12f,
            sunriseHours = sunrise,
            sunsetHours = sunset
        )
        assertEquals(1f, stateNoon.getLightLevel(), 0.01f)
    }
}
