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
    fun `getCloudCount returns 2 for LIGHT density`() {
        val state = SimulationState.default().copy(cloudDensity = CloudDensity.LIGHT)
        assertEquals(2, state.getCloudCount())
    }

    @Test
    fun `getCloudCount returns 6 for MEDIUM density`() {
        val state = SimulationState.default().copy(cloudDensity = CloudDensity.MEDIUM)
        assertEquals(6, state.getCloudCount())
    }

    @Test
    fun `getCloudCount returns 18 for HEAVY density`() {
        val state = SimulationState.default().copy(cloudDensity = CloudDensity.HEAVY)
        assertEquals(18, state.getCloudCount())
    }

    @Test
    fun `getCloudCount returns 35 for OVERCAST density`() {
        val state = SimulationState.default().copy(cloudDensity = CloudDensity.OVERCAST)
        assertEquals(35, state.getCloudCount())
    }

    @Test
    fun `getCloudCount for AUTO depends on weather - CLEAR`() {
        val state = SimulationState.default().copy(
            cloudDensity = CloudDensity.AUTO,
            weather = WeatherType.CLEAR
        )
        assertEquals(1, state.getCloudCount())
    }

    @Test
    fun `getCloudCount for AUTO depends on weather - PARTLY_CLOUDY`() {
        val state = SimulationState.default().copy(
            cloudDensity = CloudDensity.AUTO,
            weather = WeatherType.PARTLY_CLOUDY
        )
        assertEquals(5, state.getCloudCount())
    }

    @Test
    fun `getCloudCount for AUTO depends on weather - CLOUDY`() {
        val state = SimulationState.default().copy(
            cloudDensity = CloudDensity.AUTO,
            weather = WeatherType.CLOUDY
        )
        assertEquals(16, state.getCloudCount())
    }

    @Test
    fun `getCloudCount for AUTO depends on weather - OVERCAST`() {
        val state = SimulationState.default().copy(
            cloudDensity = CloudDensity.AUTO,
            weather = WeatherType.OVERCAST
        )
        assertEquals(35, state.getCloudCount())
    }

    @Test
    fun `getCloudCount for AUTO depends on weather - RAIN`() {
        val state = SimulationState.default().copy(
            cloudDensity = CloudDensity.AUTO,
            weather = WeatherType.RAIN
        )
        assertEquals(30, state.getCloudCount())
    }

    @Test
    fun `getCloudCount for AUTO depends on weather - THUNDERSTORM`() {
        val state = SimulationState.default().copy(
            cloudDensity = CloudDensity.AUTO,
            weather = WeatherType.THUNDERSTORM
        )
        assertEquals(30, state.getCloudCount())
    }

    @Test
    fun `getCloudCount for AUTO depends on weather - SNOW`() {
        val state = SimulationState.default().copy(
            cloudDensity = CloudDensity.AUTO,
            weather = WeatherType.SNOW
        )
        assertEquals(20, state.getCloudCount())
    }

    @Test
    fun `getCloudCount for AUTO depends on weather - FOG`() {
        val state = SimulationState.default().copy(
            cloudDensity = CloudDensity.AUTO,
            weather = WeatherType.FOG
        )
        assertEquals(35, state.getCloudCount())
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
    fun `getEffectiveShadowStrength returns zero when disabled`() {
        val state = SimulationState.default().copy(
            shadowsEnabled = false,
            shadowStrength = 1f,
            timeOfDayHours = 12f  // Bright day
        )
        assertEquals(0f, state.getEffectiveShadowStrength(), 0.01f)
    }
    
    @Test
    fun `getEffectiveShadowStrength returns full strength at midday`() {
        val state = SimulationState.default().copy(
            shadowsEnabled = true,
            shadowStrength = 0.5f,
            timeOfDayHours = 12f
        )
        // Light level at noon = 1.0, so effective = 0.5 * 1.0 = 0.5
        assertEquals(0.5f, state.getEffectiveShadowStrength(), 0.01f)
    }
    
    @Test
    fun `getEffectiveShadowStrength scales with light level at night`() {
        val state = SimulationState.default().copy(
            shadowsEnabled = true,
            shadowStrength = 1f,
            timeOfDayHours = 0f  // Midnight
        )
        // Light level at midnight = 0.15, so effective = 1.0 * 0.15 = 0.15
        assertEquals(0.15f, state.getEffectiveShadowStrength(), 0.01f)
    }
    
    @Test
    fun `default state has shadows enabled`() {
        val state = SimulationState.default()
        assertTrue(state.shadowsEnabled)
        assertTrue(state.cloudShadowsEnabled)
        assertEquals(0.5f, state.shadowStrength, 0.01f)
    }
}
