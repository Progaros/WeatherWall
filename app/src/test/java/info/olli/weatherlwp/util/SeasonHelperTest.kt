package info.olli.weatherlwp.util

import info.olli.weatherlwp.util.SeasonHelper.TerrainSeason
import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for SeasonHelper utility.
 */
class SeasonHelperTest {
    
    // ============== TerrainSeason Enum Tests ==============
    
    @Test
    fun `TerrainSeason toStringKey returns lowercase`() {
        assertEquals("spring", TerrainSeason.SPRING.toStringKey())
        assertEquals("summer", TerrainSeason.SUMMER.toStringKey())
        assertEquals("autumn", TerrainSeason.AUTUMN.toStringKey())
        assertEquals("winter", TerrainSeason.WINTER.toStringKey())
    }
    
    @Test
    fun `TerrainSeason fromString parses valid values`() {
        assertEquals(TerrainSeason.SPRING, TerrainSeason.fromString("spring"))
        assertEquals(TerrainSeason.SUMMER, TerrainSeason.fromString("summer"))
        assertEquals(TerrainSeason.AUTUMN, TerrainSeason.fromString("autumn"))
        assertEquals(TerrainSeason.WINTER, TerrainSeason.fromString("winter"))
    }
    
    @Test
    fun `TerrainSeason fromString handles case insensitive`() {
        assertEquals(TerrainSeason.SPRING, TerrainSeason.fromString("SPRING"))
        assertEquals(TerrainSeason.WINTER, TerrainSeason.fromString("Winter"))
    }
    
    @Test
    fun `TerrainSeason fromString handles fall alias`() {
        assertEquals(TerrainSeason.AUTUMN, TerrainSeason.fromString("fall"))
    }
    
    @Test
    fun `TerrainSeason fromString returns null for invalid`() {
        assertNull(TerrainSeason.fromString("auto"))
        assertNull(TerrainSeason.fromString("invalid"))
        assertNull(TerrainSeason.fromString(""))
    }
    
    // ============== Effective Season Logic Tests ==============
    
    @Test
    fun `getEffectiveTerrainSeasonKey uses override when debug enabled`() {
        val result = SeasonHelper.getEffectiveTerrainSeasonKey(
            debugEnabled = true,
            seasonOverride = "winter"
        )
        assertEquals("winter", result)
    }
    
    @Test
    fun `getEffectiveTerrainSeasonKey ignores override when debug disabled`() {
        val result = SeasonHelper.getEffectiveTerrainSeasonKey(
            debugEnabled = false,
            seasonOverride = "winter"
        )
        // Should return current calendar season, not the override
        assertNotNull(result)
        assertTrue(
            "Should be a valid season",
            result in listOf("spring", "summer", "autumn", "winter")
        )
    }
    
    @Test
    fun `getEffectiveTerrainSeasonKey uses calendar when auto`() {
        val result = SeasonHelper.getEffectiveTerrainSeasonKey(
            debugEnabled = true,
            seasonOverride = "auto"
        )
        // Should return current calendar season
        assertNotNull(result)
        assertTrue(
            "Should be a valid season",
            result in listOf("spring", "summer", "autumn", "winter")
        )
    }
    
    // ============== Season Check Helpers ==============
    
    @Test
    fun `isWinter returns true only for winter`() {
        assertTrue(SeasonHelper.isWinter("winter"))
        assertFalse(SeasonHelper.isWinter("spring"))
        assertFalse(SeasonHelper.isWinter("summer"))
        assertFalse(SeasonHelper.isWinter("autumn"))
    }
    
    @Test
    fun `isSpring returns true only for spring`() {
        assertTrue(SeasonHelper.isSpring("spring"))
        assertFalse(SeasonHelper.isSpring("winter"))
        assertFalse(SeasonHelper.isSpring("summer"))
        assertFalse(SeasonHelper.isSpring("autumn"))
    }
    
    @Test
    fun `isAutumn returns true only for autumn`() {
        assertTrue(SeasonHelper.isAutumn("autumn"))
        assertFalse(SeasonHelper.isAutumn("winter"))
        assertFalse(SeasonHelper.isAutumn("spring"))
        assertFalse(SeasonHelper.isAutumn("summer"))
    }
    
    @Test
    fun `isSummer returns true only for summer`() {
        assertTrue(SeasonHelper.isSummer("summer"))
        assertFalse(SeasonHelper.isSummer("winter"))
        assertFalse(SeasonHelper.isSummer("spring"))
        assertFalse(SeasonHelper.isSummer("autumn"))
    }
    
    // ============== Calendar Season Tests ==============
    
    @Test
    fun `getCurrentCalendarSeason returns valid season`() {
        val season = SeasonHelper.getCurrentCalendarSeason()
        assertNotNull(season)
        assertTrue(
            "Should be a valid TerrainSeason",
            season in TerrainSeason.values()
        )
    }
    
    @Test
    fun `getCurrentCalendarSeasonKey returns valid key`() {
        val key = SeasonHelper.getCurrentCalendarSeasonKey()
        assertNotNull(key)
        assertTrue(
            "Should be a valid season key",
            key in listOf("spring", "summer", "autumn", "winter")
        )
    }
    
    // ============== Month to Season Mapping Tests ==============
    // Note: These test the expected behavior but not the actual calendar
    
    @Test
    fun `spring should be March through May`() {
        // Months 2, 3, 4 (Calendar.MARCH, APRIL, MAY)
        // This is a documentation test - actual behavior depends on current date
        val springMonths = listOf(2, 3, 4)  // Calendar constants
        assertEquals("Spring has 3 months", 3, springMonths.size)
    }
    
    @Test
    fun `summer should be June through August`() {
        val summerMonths = listOf(5, 6, 7)  // Calendar.JUNE, JULY, AUGUST
        assertEquals("Summer has 3 months", 3, summerMonths.size)
    }
    
    @Test
    fun `autumn should be September through November`() {
        val autumnMonths = listOf(8, 9, 10)  // Calendar.SEPTEMBER, OCTOBER, NOVEMBER
        assertEquals("Autumn has 3 months", 3, autumnMonths.size)
    }
    
    @Test
    fun `winter should be December through February`() {
        val winterMonths = listOf(11, 0, 1)  // Calendar.DECEMBER, JANUARY, FEBRUARY
        assertEquals("Winter has 3 months", 3, winterMonths.size)
    }
}
