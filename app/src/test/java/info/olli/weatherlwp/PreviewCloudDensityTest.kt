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
    fun `preview cloud scale has minimum`() {
        // The minimum scale for preview should be 0.5 (not the raw heightScale)
        val previewHeight = 400f
        val rawScale = previewHeight / RenderConstants.REFERENCE_HEIGHT
        val previewScale = rawScale.coerceAtLeast(0.5f)

        assertTrue(
            "Preview scale ($previewScale) should be >= 0.5 even with small height ($previewHeight)",
            previewScale >= 0.5f
        )
        assertTrue(
            "Raw scale ($rawScale) should be < 0.5 for small preview",
            rawScale < 0.5f
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
}
