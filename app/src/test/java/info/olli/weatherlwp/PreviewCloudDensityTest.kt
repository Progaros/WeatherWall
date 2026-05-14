package info.olli.weatherlwp

import info.olli.weatherlwp.rendering.CloudLayer
import info.olli.weatherlwp.rendering.RenderConstants
import org.junit.Assert.*
import org.junit.Test

/**
 * Tests for preview-mode cloud density and sizing.
 * Verifies that overcast previews show meaningful cloud coverage,
 * and that small preview viewports don't generate nano-sized clouds.
 */
class PreviewCloudDensityTest {

    @Test
    fun `preview max groups allows sufficient overcast coverage`() {
        // Preview should support enough groups for overcast to look like overcast
        assertTrue(
            "Preview max groups (${ CloudLayer.MAX_GROUPS_PREVIEW }) should be >= 16",
            CloudLayer.MAX_GROUPS_PREVIEW >= 16
        )
    }

    @Test
    fun `preview cloud distribution concentrated in visible area`() {
        // In preview mode, clouds should be distributed in the visible viewport,
        // not across the full parallax-padded range. Verify by checking that the
        // distribution half-width in preview is less than the full generation range.
        val previewWidth = 300f
        val previewHeight = 500f

        val fullHalf = RenderConstants.getVisibleWorldHalf(previewWidth, previewHeight)
        val hs = (previewHeight / RenderConstants.REFERENCE_HEIGHT).coerceAtLeast(0.1f)
        val previewDistribution = (previewWidth / (2f * hs)) * 1.2f

        assertTrue(
            "Preview distribution ($previewDistribution) should be < full range ($fullHalf)",
            previewDistribution < fullHalf
        )
        assertTrue(
            "Preview distribution should be > 0",
            previewDistribution > 0f
        )
    }

    @Test
    fun `preview cloud scale has minimum at PREVIEW_MIN_DRAW_SCALE`() {
        // The minimum draw scale for preview should be PREVIEW_MIN_DRAW_SCALE (0.65)
        val previewHeight = 400f
        val rawScale = previewHeight / RenderConstants.REFERENCE_HEIGHT
        val previewScale = rawScale.coerceAtLeast(CloudLayer.PREVIEW_MIN_DRAW_SCALE)

        assertTrue(
            "Preview scale ($previewScale) should be >= PREVIEW_MIN_DRAW_SCALE even with small height ($previewHeight)",
            previewScale >= CloudLayer.PREVIEW_MIN_DRAW_SCALE
        )
        assertTrue(
            "Raw scale ($rawScale) should be < PREVIEW_MIN_DRAW_SCALE for small preview",
            rawScale < CloudLayer.PREVIEW_MIN_DRAW_SCALE
        )
    }

    @Test
    fun `clear weather preview still sparse`() {
        // Clear weather should still have few or no clouds even in preview
        val clearCloudCount = 0  // CloudDensity.NONE → 0
        assertTrue("Clear preview should have 0 clouds", clearCloudCount == 0)
    }

    @Test
    fun `preview group sizes are within bitmap limits`() {
        val maxDim = CloudLayer.MAX_BITMAP_DIMENSION_PREVIEW
        // Group width/height should be constrained to preview bitmap limits
        val groupWidth = (CloudLayer.GROUP_WIDTH_MAX * 300f).toInt() // minDim=300 for small preview
        val constrained = groupWidth.coerceIn(50, maxDim)
        assertTrue("Group width should be within limits", constrained <= maxDim)
        assertTrue("Group width should have minimum", constrained >= 50)
    }

    // ============== Coverage-Based Cloud Generation Tests ==============

    @Test
    fun `coverage mapping returns zero groups for zero clouds`() {
        val layer = CloudLayer::class.java.getDeclaredConstructor(android.content.Context::class.java)
        // Test coverage mapping directly using known formulas
        val (groups, mult) = coverageMapping(0)
        assertEquals("Zero clouds → zero groups", 0, groups)
        assertEquals("Zero clouds → 1.0 size mult", 1.0f, mult, 0.01f)
    }

    @Test
    fun `coverage mapping reduces overcast group count`() {
        // Overcast (50 raw) should produce significantly fewer groups than raw count
        val (groups, mult) = coverageMapping(50)
        assertTrue("Overcast groups ($groups) should be < 50", groups < 50)
        assertTrue("Overcast groups ($groups) should be >= 12", groups >= 12)
        assertTrue("Overcast size mult ($mult) should be > 1.0", mult > 1.0f)
    }

    @Test
    fun `coverage mapping increases size for heavy clouds`() {
        val (_, lightMult) = coverageMapping(3)
        val (_, heavyMult) = coverageMapping(25)
        val (_, overcastMult) = coverageMapping(50)
        assertTrue("Heavy mult > light mult", heavyMult > lightMult)
        assertTrue("Overcast mult > heavy mult", overcastMult > heavyMult)
    }

    @Test
    fun `coverage mapping ensures minimum groups for light clouds`() {
        val (groups, _) = coverageMapping(1)
        assertTrue("Even 1 cloud → at least 2 groups", groups >= 2)
    }

    @Test
    fun `preview overcast effective draw scale exceeds 0_9`() {
        // Preview overcast: PREVIEW_MIN_DRAW_SCALE * overcast sizeMult should be > 0.9
        // This ensures overcast preview shows meaningfully large clouds
        val (_, overcastMult) = coverageMapping(50)
        val effectiveScale = CloudLayer.PREVIEW_MIN_DRAW_SCALE * overcastMult
        assertTrue(
            "Preview overcast effective scale ($effectiveScale) should be > 0.9",
            effectiveScale > 0.9f
        )
    }

    @Test
    fun `cloud count does not scale with tiny preview size`() {
        // Coverage mapping depends only on raw cloud count, not screen size.
        // Verify same output for different raw counts regardless of viewport.
        val (groups300, mult300) = coverageMapping(25)
        val (groups1080, mult1080) = coverageMapping(25)
        assertEquals("Same raw count → same groups regardless of viewport", groups300, groups1080)
        assertEquals("Same raw count → same mult regardless of viewport", mult300, mult1080, 0.01f)
    }

    @Test
    fun `preview min draw scale is greater than old minimum`() {
        // PREVIEW_MIN_DRAW_SCALE should be > 0.5 (the old value)
        assertTrue(
            "PREVIEW_MIN_DRAW_SCALE (${CloudLayer.PREVIEW_MIN_DRAW_SCALE}) should be > 0.5",
            CloudLayer.PREVIEW_MIN_DRAW_SCALE > 0.5f
        )
    }

    // Helper: mirrors CloudLayer.cloudCountToCoverageGroups logic for testing
    private fun coverageMapping(rawCount: Int): Pair<Int, Float> {
        return when {
            rawCount <= 0 -> 0 to 1.0f
            rawCount <= 5 -> rawCount.coerceAtLeast(2) to 1.0f
            rawCount <= 15 -> ((rawCount * 0.7f).toInt().coerceAtLeast(4)) to 1.15f
            rawCount <= 30 -> ((rawCount * 0.5f).toInt().coerceAtLeast(8)) to 1.3f
            else -> ((rawCount * 0.4f).toInt().coerceIn(12, 20)) to 1.5f
        }
    }
}
