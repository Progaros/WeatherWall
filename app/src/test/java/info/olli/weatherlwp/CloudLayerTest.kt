package info.olli.weatherlwp

import info.olli.weatherlwp.rendering.CloudLayer
import info.olli.weatherlwp.rendering.DeterministicLayout
import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for CloudLayer world-space band, cell-based generation and bitmap padding constants.
 */
class CloudLayerTest {

    // ============== Cloud Y Bounds Tests (world-space band) ==============

    @Test
    fun `cloud worldY placement within world-space band`() {
        // bandTop = worldRect.top - margin, bandBottom = worldRect.top + bandHeight + margin
        val worldHeight = 1920f
        val worldTop = 0.5f - worldHeight / 2f
        val bandHeight = worldHeight * CloudLayer.BAND_HEIGHT_FRACTION
        val margin = worldHeight * CloudLayer.BAND_MARGIN_FRACTION
        val bandTop = worldTop - margin
        val bandBottom = worldTop + bandHeight + margin

        // Band should span above visible area through upper ~55% of screen
        assertTrue("Band top ($bandTop) should be above visible area ($worldTop)", bandTop < worldTop)
        assertTrue("Band bottom ($bandBottom) should be in upper half", bandBottom < 0.5f + worldHeight / 2f)
        assertTrue("Band has positive height", bandBottom > bandTop)
    }

    @Test
    fun `cloud worldY accounts for half-height clamping`() {
        val worldHeight = 1920f
        val worldTop = 0.5f - worldHeight / 2f
        val bandHeight = worldHeight * CloudLayer.BAND_HEIGHT_FRACTION
        val margin = worldHeight * CloudLayer.BAND_MARGIN_FRACTION
        val bandTop = worldTop - margin
        val bandBottom = worldTop + bandHeight + margin

        val groupHeightMin = CloudLayer.GROUP_HEIGHT_MIN
        val groupHeightMax = CloudLayer.GROUP_HEIGHT_MAX

        for (gh in listOf(groupHeightMin, groupHeightMax, (groupHeightMin + groupHeightMax) / 2f)) {
            val halfHWorld = gh * 0.5f * worldHeight
            val minY = bandTop + halfHWorld
            val maxY = (bandBottom - halfHWorld).coerceAtLeast(minY)

            // Cloud center at minY: top edge stays within band
            assertTrue("Top edge should not exceed band top (gh=$gh)",
                minY - halfHWorld >= bandTop - 0.001f)

            // Cloud center at maxY: bottom edge stays within band
            assertTrue("Bottom edge should not exceed band bottom (gh=$gh)",
                maxY + halfHWorld <= bandBottom + 0.001f)

            // Range should be non-negative
            assertTrue("Y range should be non-negative (gh=$gh)", maxY >= minY)
        }
    }

    // ============== World-Space Band Tests ==============

    @Test
    fun `band margin extends above visible area`() {
        val worldHeight = 1920f
        val worldTop = 0.5f - worldHeight / 2f
        val margin = worldHeight * CloudLayer.BAND_MARGIN_FRACTION
        val bandTop = worldTop - margin

        assertTrue("Band top should extend above visible world top",
            bandTop < worldTop)
    }

    @Test
    fun `parallax cannot clip clouds within band margin`() {
        val worldHeight = 1920f
        val margin = worldHeight * CloudLayer.BAND_MARGIN_FRACTION
        assertTrue("Band margin ($margin) should be > 0 for parallax safety", margin > 0f)
        assertTrue("Band margin should be >= 15% of world height", margin >= worldHeight * 0.15f)
    }

    // ============== Cell-Based Generation Tests ==============

    @Test
    fun `cell spawn probability increases with coverage`() {
        val lowCoverage = 0.1f
        val highCoverage = 0.9f
        val lowProb = CloudLayer.SPAWN_PROB_MIN + (CloudLayer.SPAWN_PROB_MAX - CloudLayer.SPAWN_PROB_MIN) * lowCoverage
        val highProb = CloudLayer.SPAWN_PROB_MIN + (CloudLayer.SPAWN_PROB_MAX - CloudLayer.SPAWN_PROB_MIN) * highCoverage
        assertTrue("High coverage spawn prob ($highProb) > low ($lowProb)", highProb > lowProb)
        assertTrue("Low prob >= SPAWN_PROB_MIN", lowProb >= CloudLayer.SPAWN_PROB_MIN)
        assertTrue("High prob <= SPAWN_PROB_MAX", highProb <= CloudLayer.SPAWN_PROB_MAX)
    }

    @Test
    fun `coverage scale increases with cloud count`() {
        val lowCoverage = 0.1f
        val highCoverage = 0.9f
        val lowScale = CloudLayer.COVERAGE_SCALE_MIN + (CloudLayer.COVERAGE_SCALE_MAX - CloudLayer.COVERAGE_SCALE_MIN) * lowCoverage
        val highScale = CloudLayer.COVERAGE_SCALE_MIN + (CloudLayer.COVERAGE_SCALE_MAX - CloudLayer.COVERAGE_SCALE_MIN) * highCoverage
        assertTrue("High coverage scale ($highScale) > low ($lowScale)", highScale > lowScale)
        assertTrue("Low scale >= COVERAGE_SCALE_MIN", lowScale >= CloudLayer.COVERAGE_SCALE_MIN)
        assertTrue("High scale <= COVERAGE_SCALE_MAX", highScale <= CloudLayer.COVERAGE_SCALE_MAX)
    }

    @Test
    fun `cell size derived from band height`() {
        // FIXED_CELL_SIZE is pre-computed from REFERENCE_HEIGHT * BAND_HEIGHT_FRACTION * CELL_SIZE_FRACTION
        val cellSize = CloudLayer.FIXED_CELL_SIZE
        val worldHeight = 1920f
        val bandHeight = worldHeight * CloudLayer.BAND_HEIGHT_FRACTION
        assertTrue("Fixed cell size ($cellSize) should be > 0", cellSize > 0f)
        assertTrue("Fixed cell size should be < band height", cellSize < bandHeight)
    }

    @Test
    fun `deterministic cells produce same result for same seed`() {
        val seed = 42
        val cellX = 5
        val seed1 = DeterministicLayout.mix(seed + 5000, 500, cellX)
        val seed2 = DeterministicLayout.mix(seed + 5000, 500, cellX)
        assertEquals("Same cell + seed → same hash", seed1, seed2)
    }

    @Test
    fun `different cells produce different seeds`() {
        val seed = 42
        val seed1 = DeterministicLayout.mix(seed + 5000, 500, 0)
        val seed2 = DeterministicLayout.mix(seed + 5000, 500, 1)
        assertNotEquals("Different cells → different seeds", seed1, seed2)
    }

    // ============== Rendering Stability Tests ==============

    @Test
    fun `culling margin is generous enough for parallax movement`() {
        // CULL_MARGIN_FRACTION must cover typical parallax offsets (up to 20% of screen)
        val screenHeight = 1920f
        val margin = screenHeight * CloudLayer.CULL_MARGIN_FRACTION
        val maxParallaxOffset = screenHeight * 0.20f

        assertTrue("Cull margin ($margin) should exceed max parallax offset ($maxParallaxOffset)",
            margin > maxParallaxOffset)
    }

    @Test
    fun `large cloud near top boundary not clipped by band placement`() {
        // Large cloud (max height) with conservative placement factor should still fit within band
        val bandTop = CloudLayer.ABS_BAND_TOP
        val bandBottom = CloudLayer.ABS_BAND_BOTTOM
        val bandHeight = bandBottom - bandTop
        val maxGroupHeight = 0.30f  // GROUP_HEIGHT_MAX
        val placementSafety = 1.35f  // Matches conservative placement in createCloudGroup
        val halfH = maxGroupHeight * 0.5f * bandHeight * placementSafety

        val minY = bandTop + halfH
        val maxY = bandBottom - halfH

        assertTrue("Even max-height cloud should have valid placement range (minY=$minY, maxY=$maxY)",
            maxY >= minY)
    }

    // ============== Bitmap Padding & Draw Stability Tests ==============

    @Test
    fun `vertical bitmap padding prevents internal puff clipping`() {
        // Max puff extent from center: offsetY(0.25) + radiusY(0.55) = 0.80 * bitmapHeight
        // Need: paddedHalf >= 0.80 * bitmapHeight
        val bitmapHeight = 200  // representative
        val paddingY = (bitmapHeight * CloudLayer.BLUR_PADDING_VERTICAL).toInt()
        val paddedHeight = bitmapHeight + paddingY * 2
        val paddedHalfFromCenter = paddedHeight / 2f

        val maxPuffOffset = bitmapHeight * 0.25f  // offsetY max
        val maxPuffRadius = bitmapHeight * 0.55f  // radiusY max
        val maxExtent = maxPuffOffset + maxPuffRadius

        assertTrue(
            "Padded half ($paddedHalfFromCenter) must exceed max puff extent ($maxExtent)",
            paddedHalfFromCenter >= maxExtent
        )
    }

    @Test
    fun `placement with conservative bounds fits drawn cloud`() {
        // Verify the 1.35× placement factor covers the actual drawn size
        val bandTop = CloudLayer.ABS_BAND_TOP
        val bandBottom = CloudLayer.ABS_BAND_BOTTOM
        val bandHeight = bandBottom - bandTop
        val maxGroupHeight = 0.30f
        val placementSafety = 1.35f

        val safeHalfH = maxGroupHeight * 0.5f * bandHeight * placementSafety
        val worstY = bandTop + safeHalfH

        // With padding: actual extent = halfH * (1 + BLUR_PADDING_VERTICAL)
        val logicalHalfH = maxGroupHeight * 0.5f * bandHeight
        val actualDrawnHalf = logicalHalfH * (1f + CloudLayer.BLUR_PADDING_VERTICAL)

        assertTrue(
            "Cloud at worst placement ($worstY) should keep drawn extent (${worstY - actualDrawnHalf}) above bandTop ($bandTop)",
            worstY - actualDrawnHalf >= bandTop
        )
    }

    // ============== Cloud Bitmap Hardening Tests ==============

    @Test
    fun `puff max extent fits within padded bitmap for all content heights`() {
        // offsetY max = 0.25 * bitmapHeight, radiusY max = 0.55 * bitmapHeight
        // centerY = paddedHeight / 2 = h * (1 + 2*BLUR_PADDING_VERTICAL) / 2
        val blurPadV = CloudLayer.BLUR_PADDING_VERTICAL

        for (bitmapHeight in listOf(30, 100, 256, 512)) {
            val paddedHeight = bitmapHeight * (1f + 2f * blurPadV)
            val centerY = paddedHeight / 2f
            val maxBottom = centerY + 0.25f * bitmapHeight + 0.55f * bitmapHeight
            val minTop = centerY - 0.25f * bitmapHeight - 0.55f * bitmapHeight

            assertTrue(
                "Puff bottom ($maxBottom) must fit within paddedHeight ($paddedHeight) at height=$bitmapHeight",
                maxBottom <= paddedHeight
            )
            assertTrue(
                "Puff top ($minTop) must be >= 0 at height=$bitmapHeight",
                minTop >= 0f
            )
        }
    }

    @Test
    fun `padding computation is symmetric between render and draw paths`() {
        // Mirrors CloudLayer.computePadding: both paths must produce identical padding.
        val blurPad = CloudLayer.BLUR_PADDING
        val blurPadV = CloudLayer.BLUR_PADDING_VERTICAL

        for (stretch in listOf(2.0f, 3.0f, 4.0f, 6.0f)) {
            for (contentWidth in listOf(50, 200, 512)) {
                for (contentHeight in listOf(30, 200, 512)) {
                    val stretchMult = (stretch / 2f).coerceIn(1f, 3f)
                    val paddingX = (contentWidth * blurPad * stretchMult).toInt()
                    val paddingY = (contentHeight * blurPadV).toInt()
                    val paddedWidth = contentWidth + paddingX * 2
                    val paddedHeight = contentHeight + paddingY * 2

                    assertTrue("paddingX must be positive (w=$contentWidth, s=$stretch)", paddingX > 0)
                    assertTrue("paddingY must be positive (h=$contentHeight)", paddingY > 0)
                    assertTrue("paddedWidth must exceed content", paddedWidth > contentWidth)
                    assertTrue("paddedHeight must exceed content", paddedHeight > contentHeight)
                    // Padded dimensions should not exceed 3x content (sanity bound)
                    assertTrue("paddedWidth sanity", paddedWidth <= contentWidth * 3)
                    assertTrue("paddedHeight sanity", paddedHeight <= contentHeight * 3)
                }
            }
        }
    }

    @Test
    fun `MAX_BITMAP_DIMENSION padded size stays within GPU texture limits`() {
        // Padded bitmap at max content size and max stretch must stay below 4096
        // (minimum Android GPU texture limit)
        val maxDim = CloudLayer.MAX_BITMAP_DIMENSION
        val blurPad = CloudLayer.BLUR_PADDING
        val blurPadV = CloudLayer.BLUR_PADDING_VERTICAL
        val maxStretchMult = 3f  // cloudStretch=6 → mult=3

        val maxPaddedWidth = maxDim + 2 * (maxDim * blurPad * maxStretchMult).toInt()
        val maxPaddedHeight = maxDim + 2 * (maxDim * blurPadV).toInt()

        assertTrue(
            "Max padded width ($maxPaddedWidth) must be < 4096",
            maxPaddedWidth < 4096
        )
        assertTrue(
            "Max padded height ($maxPaddedHeight) must be < 4096",
            maxPaddedHeight < 4096
        )
    }

    @Test
    fun `minimum bitmap height has positive puff margin`() {
        // At minimum bitmapHeight (30), puffs must still fit with at least 1px margin
        val minHeight = 30
        val blurPadV = CloudLayer.BLUR_PADDING_VERTICAL
        val paddedHeight = minHeight * (1f + 2f * blurPadV)
        val centerY = paddedHeight / 2f
        val maxExtentBottom = centerY + 0.25f * minHeight + 0.55f * minHeight
        val marginBottom = paddedHeight - maxExtentBottom
        val minExtentTop = centerY - 0.25f * minHeight - 0.55f * minHeight

        assertTrue("Bottom margin ($marginBottom) must be >= 1px at minHeight", marginBottom >= 1f)
        assertTrue("Top margin ($minExtentTop) must be >= 0 at minHeight", minExtentTop >= 0f)
    }
}
