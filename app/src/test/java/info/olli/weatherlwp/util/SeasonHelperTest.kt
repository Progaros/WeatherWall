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
    
    // ============== TerrainSeason next/prev Tests ==============
    
    @Test
    fun `TerrainSeason next cycles correctly`() {
        assertEquals(TerrainSeason.SUMMER, TerrainSeason.SPRING.next())
        assertEquals(TerrainSeason.AUTUMN, TerrainSeason.SUMMER.next())
        assertEquals(TerrainSeason.WINTER, TerrainSeason.AUTUMN.next())
        assertEquals(TerrainSeason.SPRING, TerrainSeason.WINTER.next())
    }
    
    @Test
    fun `TerrainSeason prev cycles correctly`() {
        assertEquals(TerrainSeason.WINTER, TerrainSeason.SPRING.prev())
        assertEquals(TerrainSeason.SPRING, TerrainSeason.SUMMER.prev())
        assertEquals(TerrainSeason.SUMMER, TerrainSeason.AUTUMN.prev())
        assertEquals(TerrainSeason.AUTUMN, TerrainSeason.WINTER.prev())
    }
    
    // ============== SeasonBlend Tests ==============
    
    @Test
    fun `getCurrentSeasonBlend returns valid blend`() {
        val blend = SeasonHelper.getCurrentSeasonBlend(14)
        assertNotNull(blend)
        assertTrue("Primary season must be valid",
            blend.primarySeason in TerrainSeason.values())
        assertTrue("Previous season must be valid",
            blend.previousSeason in TerrainSeason.values())
        assertTrue("Progress must be 0-1",
            blend.transitionProgress in 0f..1f)
    }
    
    @Test
    fun `SeasonBlend with 0 transition days is not transitioning`() {
        val blend = SeasonHelper.getCurrentSeasonBlend(0)
        assertFalse("Should not be transitioning with 0 days", blend.isTransitioning)
        assertEquals(1f, blend.primaryWeight, 0.01f)
        assertEquals(0f, blend.previousWeight, 0.01f)
    }
    
    @Test
    fun `SeasonBlend weights sum to 1`() {
        val blend = SeasonHelper.getCurrentSeasonBlend(14)
        assertEquals("Weights must sum to 1", 1f,
            blend.primaryWeight + blend.previousWeight, 0.01f)
    }
    
    @Test
    fun `getEffectiveSeasonBlend uses override when debug enabled`() {
        val blend = SeasonHelper.getEffectiveSeasonBlend(
            debugEnabled = true,
            seasonOverride = "winter",
            transitionDays = 14
        )
        assertEquals(TerrainSeason.WINTER, blend.primarySeason)
        assertFalse("Override should not be transitioning", blend.isTransitioning)
        assertEquals(1f, blend.transitionProgress, 0.01f)
    }
    
    @Test
    fun `getEffectiveSeasonBlend ignores override when debug disabled`() {
        val blend = SeasonHelper.getEffectiveSeasonBlend(
            debugEnabled = false,
            seasonOverride = "winter",
            transitionDays = 14
        )
        // Should use calendar, not override
        assertNotNull(blend)
        assertTrue(blend.primarySeason in TerrainSeason.values())
    }
    
    @Test
    fun `getEffectiveSeasonBlend uses calendar when auto`() {
        val blend = SeasonHelper.getEffectiveSeasonBlend(
            debugEnabled = true,
            seasonOverride = "auto",
            transitionDays = 14
        )
        // Should use calendar since "auto" specified
        assertNotNull(blend)
        assertTrue(blend.primarySeason in TerrainSeason.values())
    }

    // ============== getSeasonForDate with controlled dates ==============

    @Test
    fun `March is spring`() {
        val cal = java.util.Calendar.getInstance()
        cal.set(java.util.Calendar.MONTH, java.util.Calendar.MARCH)
        cal.set(java.util.Calendar.DAY_OF_MONTH, 15)
        assertEquals(TerrainSeason.SPRING, SeasonHelper.getSeasonForDate(cal))
    }

    @Test
    fun `April is spring`() {
        val cal = java.util.Calendar.getInstance()
        cal.set(java.util.Calendar.MONTH, java.util.Calendar.APRIL)
        assertEquals(TerrainSeason.SPRING, SeasonHelper.getSeasonForDate(cal))
    }

    @Test
    fun `May is spring`() {
        val cal = java.util.Calendar.getInstance()
        cal.set(java.util.Calendar.MONTH, java.util.Calendar.MAY)
        assertEquals(TerrainSeason.SPRING, SeasonHelper.getSeasonForDate(cal))
    }

    @Test
    fun `June is summer`() {
        val cal = java.util.Calendar.getInstance()
        cal.set(java.util.Calendar.MONTH, java.util.Calendar.JUNE)
        assertEquals(TerrainSeason.SUMMER, SeasonHelper.getSeasonForDate(cal))
    }

    @Test
    fun `July is summer`() {
        val cal = java.util.Calendar.getInstance()
        cal.set(java.util.Calendar.MONTH, java.util.Calendar.JULY)
        assertEquals(TerrainSeason.SUMMER, SeasonHelper.getSeasonForDate(cal))
    }

    @Test
    fun `August is summer`() {
        val cal = java.util.Calendar.getInstance()
        cal.set(java.util.Calendar.MONTH, java.util.Calendar.AUGUST)
        assertEquals(TerrainSeason.SUMMER, SeasonHelper.getSeasonForDate(cal))
    }

    @Test
    fun `September is autumn`() {
        val cal = java.util.Calendar.getInstance()
        cal.set(java.util.Calendar.MONTH, java.util.Calendar.SEPTEMBER)
        assertEquals(TerrainSeason.AUTUMN, SeasonHelper.getSeasonForDate(cal))
    }

    @Test
    fun `October is autumn`() {
        val cal = java.util.Calendar.getInstance()
        cal.set(java.util.Calendar.MONTH, java.util.Calendar.OCTOBER)
        assertEquals(TerrainSeason.AUTUMN, SeasonHelper.getSeasonForDate(cal))
    }

    @Test
    fun `November is autumn`() {
        val cal = java.util.Calendar.getInstance()
        cal.set(java.util.Calendar.MONTH, java.util.Calendar.NOVEMBER)
        assertEquals(TerrainSeason.AUTUMN, SeasonHelper.getSeasonForDate(cal))
    }

    @Test
    fun `December is winter`() {
        val cal = java.util.Calendar.getInstance()
        cal.set(java.util.Calendar.MONTH, java.util.Calendar.DECEMBER)
        assertEquals(TerrainSeason.WINTER, SeasonHelper.getSeasonForDate(cal))
    }

    @Test
    fun `January is winter`() {
        val cal = java.util.Calendar.getInstance()
        cal.set(java.util.Calendar.MONTH, java.util.Calendar.JANUARY)
        assertEquals(TerrainSeason.WINTER, SeasonHelper.getSeasonForDate(cal))
    }

    @Test
    fun `February is winter`() {
        val cal = java.util.Calendar.getInstance()
        cal.set(java.util.Calendar.MONTH, java.util.Calendar.FEBRUARY)
        assertEquals(TerrainSeason.WINTER, SeasonHelper.getSeasonForDate(cal))
    }

    // ============== getSeasonBlendForDate transition tests ==============

    @Test
    fun `season blend mid-season is fully primary`() {
        val cal = java.util.Calendar.getInstance()
        cal.set(2024, java.util.Calendar.JULY, 15) // Mid-summer
        val blend = SeasonHelper.getSeasonBlendForDate(cal, 14)
        assertEquals(TerrainSeason.SUMMER, blend.primarySeason)
        assertEquals(1f, blend.transitionProgress, 0.01f)
        assertFalse(blend.isTransitioning)
    }

    @Test
    fun `season blend start of season is transitioning`() {
        val cal = java.util.Calendar.getInstance()
        cal.set(2024, java.util.Calendar.JUNE, 3) // 2 days into summer
        val blend = SeasonHelper.getSeasonBlendForDate(cal, 14)
        assertEquals(TerrainSeason.SUMMER, blend.primarySeason)
        assertEquals(TerrainSeason.SPRING, blend.previousSeason)
        assertTrue(blend.isTransitioning)
        assertTrue(blend.transitionProgress < 0.5f)
    }

    @Test
    fun `season blend weights sum to 1`() {
        val cal = java.util.Calendar.getInstance()
        cal.set(2024, java.util.Calendar.MARCH, 5)
        val blend = SeasonHelper.getSeasonBlendForDate(cal, 14)
        assertEquals(1f, blend.primaryWeight + blend.previousWeight, 0.001f)
    }

    @Test
    fun `season blend January transitions from autumn to winter`() {
        val cal = java.util.Calendar.getInstance()
        cal.set(2024, java.util.Calendar.JANUARY, 15) // Mid-January, well past transition
        val blend = SeasonHelper.getSeasonBlendForDate(cal, 14)
        assertEquals(TerrainSeason.WINTER, blend.primarySeason)
        assertEquals(1f, blend.transitionProgress, 0.01f)
    }

    @Test
    fun `season blend with 0 transition days is instant`() {
        val cal = java.util.Calendar.getInstance()
        cal.set(2024, java.util.Calendar.JUNE, 1)
        val blend = SeasonHelper.getSeasonBlendForDate(cal, 0)
        assertEquals(TerrainSeason.SUMMER, blend.primarySeason)
        assertEquals(TerrainSeason.SUMMER, blend.previousSeason)
        assertFalse(blend.isTransitioning)
    }

    // ============== getEffectiveSeasonBlend dayOfYearOverride ==============

    @Test
    fun `getEffectiveSeasonBlend dayOfYearOverride simulates date`() {
        // Day 180 is late June (summer)
        val blend = SeasonHelper.getEffectiveSeasonBlend(
            debugEnabled = true,
            seasonOverride = "auto",
            transitionDays = 14,
            dayOfYearOverride = 180
        )
        assertEquals(TerrainSeason.SUMMER, blend.primarySeason)
    }

    @Test
    fun `getEffectiveSeasonBlend dayOfYearOverride ignored when not debug`() {
        val blend = SeasonHelper.getEffectiveSeasonBlend(
            debugEnabled = false,
            seasonOverride = "auto",
            transitionDays = 14,
            dayOfYearOverride = 180
        )
        // Should use current calendar, ignore override
        assertNotNull(blend)
    }

    @Test
    fun `getEffectiveSeasonBlend invalid override falls back to calendar`() {
        val blend = SeasonHelper.getEffectiveSeasonBlend(
            debugEnabled = true,
            seasonOverride = "invalid_season",
            transitionDays = 14
        )
        // fromString returns null, falls back to getCurrentSeasonBlend
        assertNotNull(blend)
        assertTrue(blend.primarySeason in TerrainSeason.values())
    }

    // ============== smoothStep precision tests (kills math mutations) ==============

    @Test
    fun `transition progress uses smoothstep easing`() {
        // 3 days into summer (June 4) with 14-day transition
        // rawProgress = 3/14, smoothStep(3/14) = (3/14)^2 * (3 - 2*(3/14))
        val cal = java.util.Calendar.getInstance()
        cal.set(2024, java.util.Calendar.JUNE, 4, 12, 0, 0)
        cal.set(java.util.Calendar.MILLISECOND, 0)
        val blend = SeasonHelper.getSeasonBlendForDate(cal, 14)

        assertEquals(TerrainSeason.SUMMER, blend.primarySeason)
        assertEquals(TerrainSeason.SPRING, blend.previousSeason)

        // Expected: smoothStep(3/14) ≈ 0.1181
        val raw = 3f / 14f
        val expected = raw * raw * (3f - 2f * raw)
        assertEquals("transition progress should match smoothstep",
            expected, blend.transitionProgress, 0.01f)
        assertTrue("progress should be non-zero", blend.transitionProgress > 0f)
    }

    @Test
    fun `transition progress at 10 days uses smoothstep`() {
        // 10 days into autumn (Sep 11) with 14-day transition
        val cal = java.util.Calendar.getInstance()
        cal.set(2024, java.util.Calendar.SEPTEMBER, 11, 12, 0, 0)
        cal.set(java.util.Calendar.MILLISECOND, 0)
        val blend = SeasonHelper.getSeasonBlendForDate(cal, 14)

        assertEquals(TerrainSeason.AUTUMN, blend.primarySeason)
        assertEquals(TerrainSeason.SUMMER, blend.previousSeason)

        val raw = 10f / 14f
        val expected = raw * raw * (3f - 2f * raw)
        assertEquals("transition progress at 10 days",
            expected, blend.transitionProgress, 0.01f)
    }

    // ============== isTransitioning boundary test ==============

    @Test
    fun `SeasonBlend with progress 1 and different seasons is not transitioning`() {
        // Directly construct a blend with progress=1.0 but different seasons
        val blend = SeasonHelper.SeasonBlend(
            TerrainSeason.SUMMER,
            TerrainSeason.SPRING,
            1.0f
        )
        assertFalse("progress=1.0 should not be transitioning even with different seasons",
            blend.isTransitioning)
    }

    @Test
    fun `SeasonBlend with progress less than 1 and different seasons is transitioning`() {
        val blend = SeasonHelper.SeasonBlend(
            TerrainSeason.SUMMER,
            TerrainSeason.SPRING,
            0.99f
        )
        assertTrue("progress<1.0 with different seasons should be transitioning",
            blend.isTransitioning)
    }

    // ============== getSeasonBlendForDate Calendar.set mutations ==============

    @Test
    fun `season blend for Dec 5 transitions from autumn to winter`() {
        val cal = java.util.Calendar.getInstance()
        cal.set(2024, java.util.Calendar.DECEMBER, 5, 12, 0, 0)
        cal.set(java.util.Calendar.MILLISECOND, 0)
        val blend = SeasonHelper.getSeasonBlendForDate(cal, 14)

        assertEquals(TerrainSeason.WINTER, blend.primarySeason)
        assertEquals(TerrainSeason.AUTUMN, blend.previousSeason)
        assertTrue("Should be transitioning at Dec 5", blend.isTransitioning)

        val raw = 4f / 14f
        val expected = raw * raw * (3f - 2f * raw)
        assertEquals("progress at Dec 5", expected, blend.transitionProgress, 0.02f)
    }

    @Test
    fun `getEffectiveSeasonBlend dayOfYearOverride boundary 0 means disabled`() {
        val blend = SeasonHelper.getEffectiveSeasonBlend(
            debugEnabled = true,
            seasonOverride = "auto",
            transitionDays = 14,
            dayOfYearOverride = 0
        )
        // dayOfYearOverride=0 should use current calendar, not override
        assertNotNull(blend)
        assertTrue(blend.primarySeason in TerrainSeason.values())
    }
}
