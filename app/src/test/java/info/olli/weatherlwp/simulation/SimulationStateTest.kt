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
    fun `getLightLevel transitions during dawn`() {
        // At 5am, just starting dawn
        val state5am = SimulationState.default().copy(timeOfDayHours = 5f)
        assertEquals(0.15f, state5am.getLightLevel(), 0.01f)
        
        // At 6am, halfway through dawn
        val state6am = SimulationState.default().copy(timeOfDayHours = 6f)
        assertTrue(state6am.getLightLevel() > 0.4f)
        assertTrue(state6am.getLightLevel() < 0.7f)
        
        // At 7am, dawn complete
        val state7am = SimulationState.default().copy(timeOfDayHours = 7f)
        assertEquals(1f, state7am.getLightLevel(), 0.01f)
    }
    
    @Test
    fun `getLightLevel returns full value during day`() {
        val stateNoon = SimulationState.default().copy(timeOfDayHours = 12f)
        assertEquals(1f, stateNoon.getLightLevel(), 0.01f)
        
        val state3pm = SimulationState.default().copy(timeOfDayHours = 15f)
        assertEquals(1f, state3pm.getLightLevel(), 0.01f)
    }
    
    @Test
    fun `getLightLevel transitions during dusk`() {
        // At 17:00, just starting dusk
        val state5pm = SimulationState.default().copy(timeOfDayHours = 17f)
        assertEquals(1f, state5pm.getLightLevel(), 0.01f)
        
        // At 18:30, midway through dusk
        val state630pm = SimulationState.default().copy(timeOfDayHours = 18.5f)
        assertTrue(state630pm.getLightLevel() > 0.4f)
        assertTrue(state630pm.getLightLevel() < 0.7f)
        
        // At 20:00, night begins
        val state8pm = SimulationState.default().copy(timeOfDayHours = 20f)
        assertEquals(0.15f, state8pm.getLightLevel(), 0.01f)
    }
    
    @Test
    fun `getLightLevel returns low value at 11pm`() {
        val state = SimulationState.default().copy(timeOfDayHours = 23f)
        assertEquals(0.15f, state.getLightLevel(), 0.01f)
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
        assertEquals(50, state.getCloudCount())
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
}
