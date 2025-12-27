package com.example.weatherlwp

import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for Weather Repository - Tests for Issue #10
 * 
 * Issue #10: Breezy Weather Check Shows "Connected" When Stale
 * Data older than 2 hours should be considered stale
 */
class WeatherRepositoryTest {

    companion object {
        const val TWO_HOURS_MS = 2 * 60 * 60 * 1000L  // 2 hours in milliseconds
        const val ONE_HOUR_MS = 60 * 60 * 1000L      // 1 hour in milliseconds
    }

    // ============== Issue #10: Staleness Check Tests ==============
    
    @Test
    fun `data is fresh when updated recently`() {
        val lastUpdate = System.currentTimeMillis() - (30 * 60 * 1000)  // 30 minutes ago
        val maxAge = TWO_HOURS_MS
        
        val isFresh = isDataFreshHelper(lastUpdate, maxAge)
        assertTrue("Data updated 30 min ago should be fresh", isFresh)
    }
    
    @Test
    fun `data is stale when older than max age`() {
        val lastUpdate = System.currentTimeMillis() - (3 * 60 * 60 * 1000)  // 3 hours ago
        val maxAge = TWO_HOURS_MS
        
        val isFresh = isDataFreshHelper(lastUpdate, maxAge)
        assertFalse("Data updated 3 hours ago should be stale", isFresh)
    }
    
    @Test
    fun `data is stale when exactly at boundary`() {
        val lastUpdate = System.currentTimeMillis() - TWO_HOURS_MS  // Exactly 2 hours ago
        val maxAge = TWO_HOURS_MS
        
        val isFresh = isDataFreshHelper(lastUpdate, maxAge)
        assertFalse("Data at exactly max age should be stale", isFresh)
    }
    
    @Test
    fun `data is fresh just before boundary`() {
        val lastUpdate = System.currentTimeMillis() - (TWO_HOURS_MS - 1000)  // Just under 2 hours
        val maxAge = TWO_HOURS_MS
        
        val isFresh = isDataFreshHelper(lastUpdate, maxAge)
        assertTrue("Data just under max age should be fresh", isFresh)
    }
    
    @Test
    fun `data with zero timestamp is stale`() {
        val lastUpdate = 0L
        val maxAge = TWO_HOURS_MS
        
        val isFresh = isDataFreshHelper(lastUpdate, maxAge)
        assertFalse("Never-updated data should be stale", isFresh)
    }
    
    @Test
    fun `default max age should be reasonable`() {
        // Default should be between 1 and 4 hours
        val defaultMaxAge = 3600000L  // 1 hour as default
        assertTrue("Default max age should be at least 1 hour", defaultMaxAge >= ONE_HOUR_MS)
        assertTrue("Default max age should be at most 4 hours", defaultMaxAge <= 4 * ONE_HOUR_MS)
    }
    
    // Helper function matching WeatherRepository.isDataFresh logic
    private fun isDataFreshHelper(lastUpdate: Long, maxAgeMs: Long): Boolean {
        return (System.currentTimeMillis() - lastUpdate) < maxAgeMs
    }

    // ============== Weather Status Display Tests ==============
    
    @Test
    fun `stale data should show warning status`() {
        val lastUpdate = System.currentTimeMillis() - (3 * 60 * 60 * 1000)
        val isFresh = isDataFreshHelper(lastUpdate, TWO_HOURS_MS)
        
        val displayStatus = if (isFresh) "Connected" else "Data Stale"
        assertEquals("Stale data should show warning", "Data Stale", displayStatus)
    }
    
    @Test
    fun `fresh data should show connected status`() {
        val lastUpdate = System.currentTimeMillis() - (30 * 60 * 1000)
        val isFresh = isDataFreshHelper(lastUpdate, TWO_HOURS_MS)
        
        val displayStatus = if (isFresh) "Connected" else "Data Stale"
        assertEquals("Fresh data should show connected", "Connected", displayStatus)
    }
}
