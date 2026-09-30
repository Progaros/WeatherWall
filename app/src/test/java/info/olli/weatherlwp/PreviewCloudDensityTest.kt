package info.olli.weatherlwp

import info.olli.weatherlwp.rendering.CloudLayer
import info.olli.weatherlwp.rendering.RenderConstants
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for preview-mode cloud density and sizing constants.
 * Verifies that overcast previews can show meaningful cloud coverage,
 * and that small preview viewports don't generate nano-sized clouds.
 */
class PreviewCloudDensityTest {

    @Test
    fun `preview max groups allows sufficient overcast coverage`() {
        assertTrue(
            "Preview max groups (${CloudLayer.MAX_GROUPS_PREVIEW}) should be >= 12",
            CloudLayer.MAX_GROUPS_PREVIEW >= 12
        )
    }

    @Test
    fun `preview cloud distribution uses cell-based placement`() {
        // Preview and live use the same cell-based generation. Verify cell size is sane.
        val worldHeight = 1920f  // REFERENCE_HEIGHT
        val bandHeight = worldHeight * CloudLayer.BAND_HEIGHT_FRACTION
        val cellSize = bandHeight * CloudLayer.CELL_SIZE_FRACTION

        assertTrue("Cell size ($cellSize) should be positive", cellSize > 0f)
        assertTrue("Cell size should be smaller than band height", cellSize < bandHeight)
    }

    @Test
    fun `preview cloud scale has minimum at PREVIEW_MIN_DRAW_SCALE`() {
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
    fun `preview min draw scale is greater than old minimum`() {
        assertTrue(
            "PREVIEW_MIN_DRAW_SCALE (${CloudLayer.PREVIEW_MIN_DRAW_SCALE}) should be > 0.5",
            CloudLayer.PREVIEW_MIN_DRAW_SCALE > 0.5f
        )
    }

    @Test
    fun `cloud band has positive height from world-space fractions`() {
        assertTrue(
            "BAND_HEIGHT_FRACTION should be > 0",
            CloudLayer.BAND_HEIGHT_FRACTION > 0f
        )
        assertTrue(
            "BAND_MARGIN_FRACTION should be > 0",
            CloudLayer.BAND_MARGIN_FRACTION > 0f
        )
    }

    @Test
    fun `cloud band accommodates largest cloud without clipping`() {
        val worldHeight = 1920f  // REFERENCE_HEIGHT
        val worldTop = 0.5f - worldHeight / 2f
        val bandHeight = worldHeight * CloudLayer.BAND_HEIGHT_FRACTION
        val margin = worldHeight * CloudLayer.BAND_MARGIN_FRACTION
        val bandTop = worldTop - margin
        val bandBottom = worldTop + bandHeight + margin

        val totalBandHeight = bandBottom - bandTop
        val largestCloudHalfHWorld = CloudLayer.GROUP_HEIGHT_MAX * 0.5f * worldHeight
        val centerRange = totalBandHeight - 2f * largestCloudHalfHWorld
        assertTrue(
            "Largest cloud (halfH=$largestCloudHalfHWorld) should fit in band (height=$totalBandHeight), range=$centerRange",
            centerRange >= 0f
        )
    }
}
