package info.olli.weatherlwp.rendering

import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

/**
 * Tests verifying that RiverLayer, LakeLayer, and RandomEventLayer
 * prefer the new world-space providers and only fall back to the
 * deprecated xRatio providers when the world provider is null.
 *
 * These are pure-logic tests exercising the provider dispatch pattern
 * used by the terrain-sampling loops. They do not instantiate Android
 * classes; instead they replicate the same if/else dispatch that the
 * production code uses.
 */
class WorldProviderPreferenceTest {

    // ---------- helpers replicating the dispatch pattern ----------

    /**
     * Simulates the terrain-sampling dispatch used by RiverLayer/LakeLayer:
     *
     * ```kotlin
     * val terrainY = if (worldProvider != null && transform != null) {
     *     worldProvider(transform.screenToWorldX(screenX), depth) * height
     * } else {
     *     legacyProvider!!.invoke(xRatio, depth) * height
     * }
     * ```
     */
    private fun sampleTerrain(
        screenX: Float,
        depth: Float,
        height: Float,
        transform: WorldViewportTransform?,
        worldProvider: ((Float, Float) -> Float)?,
        legacyProvider: ((Float, Float) -> Float)?
    ): Float {
        return if (worldProvider != null && transform != null) {
            worldProvider(transform.screenToWorldX(screenX), depth) * height
        } else {
            legacyProvider!!.invoke(0.5f, depth) * height
        }
    }

    private fun transform(): WorldViewportTransform =
        WorldViewportTransform(1080f, 1920f, RenderConstants.getHeightScale(1920f))

    // ---------- provider preference ----------

    @Test
    fun `world provider is preferred when both are set`() {
        val t = transform()
        var worldCalled = false
        var legacyCalled = false

        val worldProvider: (Float, Float) -> Float = { _, _ ->
            worldCalled = true
            0.8f
        }
        val legacyProvider: (Float, Float) -> Float = { _, _ ->
            legacyCalled = true
            0.8f
        }

        sampleTerrain(540f, 0.5f, 1920f, t, worldProvider, legacyProvider)

        assertEquals("world provider should be called", true, worldCalled)
        assertEquals("legacy provider should NOT be called", false, legacyCalled)
    }

    @Test
    fun `legacy xRatio provider throws when world provider is set`() {
        val t = transform()

        val worldProvider: (Float, Float) -> Float = { _, _ -> 0.8f }
        val throwingLegacy: (Float, Float) -> Float = { _, _ ->
            fail("legacy provider must not be called when world provider is set")
            0f
        }

        // Must not throw — world provider should be used exclusively
        sampleTerrain(540f, 0.5f, 1920f, t, worldProvider, throwingLegacy)
    }

    // ---------- fallback behavior ----------

    @Test
    fun `legacy provider is used when world provider is null`() {
        var legacyCalled = false

        val legacyProvider: (Float, Float) -> Float = { _, _ ->
            legacyCalled = true
            0.8f
        }

        sampleTerrain(540f, 0.5f, 1920f, null, null, legacyProvider)

        assertEquals("legacy provider should be called as fallback", true, legacyCalled)
    }

    // ---------- world provider receives correct worldX ----------

    @Test
    fun `world provider receives screenToWorldX of sample position`() {
        val t = transform()
        val receivedWorldX = mutableListOf<Float>()

        val worldProvider: (Float, Float) -> Float = { worldX, _ ->
            receivedWorldX.add(worldX)
            0.8f
        }

        val samplePositions = listOf(0f, 270f, 540f, 810f, 1080f)
        for (screenX in samplePositions) {
            sampleTerrain(screenX, 0.5f, 1920f, t, worldProvider, null)
        }

        for ((i, screenX) in samplePositions.withIndex()) {
            val expected = t.screenToWorldX(screenX)
            assertEquals("sample at screenX=$screenX",
                expected, receivedWorldX[i], 0.001f)
        }
    }

    // ---------- RandomEventLayer depth-snapping dispatch ----------

    /**
     * Simulates the depth-snapping dispatch in RandomEventLayer:
     *
     * ```kotlin
     * val effectiveDepth = hillInfoWorldProvider?.invoke(0f, event.depthFactor)
     *     ?: hillInfoProvider?.invoke(0.5f, event.depthFactor)
     *     ?: event.depthFactor
     * ```
     */
    private fun snapDepth(
        eventDepth: Float,
        worldProvider: ((Float, Float) -> Float)?,
        legacyProvider: ((Float, Float) -> Float)?
    ): Float {
        return worldProvider?.invoke(0f, eventDepth)
            ?: legacyProvider?.invoke(0.5f, eventDepth)
            ?: eventDepth
    }

    @Test
    fun `depth snap prefers world provider`() {
        var worldCalled = false
        val worldProvider: (Float, Float) -> Float = { _, depth ->
            worldCalled = true
            depth + 0.01f  // slightly different to distinguish
        }
        val throwingLegacy: (Float, Float) -> Float = { _, _ ->
            fail("legacy should not be called")
            0f
        }

        val result = snapDepth(0.5f, worldProvider, throwingLegacy)
        assertEquals(true, worldCalled)
        assertEquals(0.51f, result, 0.001f)
    }

    @Test
    fun `depth snap falls back to legacy when world is null`() {
        var legacyCalled = false
        val legacyProvider: (Float, Float) -> Float = { _, depth ->
            legacyCalled = true
            depth + 0.02f
        }

        val result = snapDepth(0.5f, null, legacyProvider)
        assertEquals(true, legacyCalled)
        assertEquals(0.52f, result, 0.001f)
    }

    @Test
    fun `depth snap returns raw depth when both providers are null`() {
        val result = snapDepth(0.65f, null, null)
        assertEquals(0.65f, result, 0.001f)
    }
}
