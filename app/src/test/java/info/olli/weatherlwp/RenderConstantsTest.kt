package info.olli.weatherlwp

import info.olli.weatherlwp.rendering.RenderConstants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the RenderConstants layout helpers (height scale, extended canvas,
 * density scale, hill parallax depth, event duration).
 */
class RenderConstantsTest {

    @Test
    fun `getHeightScale at reference height returns 1`() {
        assertEquals(1.0f, RenderConstants.getHeightScale(1920f), 0.001f)
    }

    @Test
    fun `getHeightScale at double height returns 2`() {
        assertEquals(2.0f, RenderConstants.getHeightScale(3840f), 0.001f)
    }

    @Test
    fun `getHeightScale clamps at minimum`() {
        assertEquals(0.5f, RenderConstants.getHeightScale(100f), 0.001f)
    }

    @Test
    fun `getHeightScale at zero still returns minimum`() {
        assertEquals(0.5f, RenderConstants.getHeightScale(0f), 0.001f)
    }

    @Test
    fun `getHeightScale in landscape uses height not width`() {
        // 1920×1080 landscape: viewport model uses screenHeight → scale = 1080/1920 = 0.5625
        val scale = RenderConstants.getHeightScale(1080f, 1920f)
        assertEquals(1080f / 1920f, scale, 0.001f)
    }

    @Test
    fun `getHeightScale in portrait ignores width`() {
        // 1080×1920 portrait: height > width, so uses height (1920) → scale = 1.0
        val scale = RenderConstants.getHeightScale(1920f, 1080f)
        assertEquals(1.0f, scale, 0.001f)
    }

    @Test
    fun `getExtendedWidth is wider than screen`() {
        val extended = RenderConstants.getExtendedWidth(1080f, 1920f)
        assertTrue(extended > 1080f)
    }

    @Test
    fun `getExtendedWidth equals genWorldRange at reference height`() {
        // At reference height, heightScale = 1.0, so extendedWidth = genWorldRange
        val extended = RenderConstants.getExtendedWidth(1080f, 1920f)
        val genRange = RenderConstants.getGenerationWorldRange(1080f)
        assertEquals(genRange, extended, 0.01f)
    }

    @Test
    fun `getExtendedWidth scales linearly with height`() {
        // extendedWidth = genWorldRange * heightScale
        // Doubling height doubles heightScale (if not clamped) → doubles extendedWidth
        val ext1 = RenderConstants.getExtendedWidth(1080f, 1920f)
        val ext2 = RenderConstants.getExtendedWidth(1080f, 3840f)
        assertEquals(ext1 * 2f, ext2, 1f)
    }

    @Test
    fun `getExtendedWidth scales with screen width`() {
        // Different screen widths give different genWorldRange → different extendedWidth
        val ext1 = RenderConstants.getExtendedWidth(1080f, 1920f)
        val ext2 = RenderConstants.getExtendedWidth(2160f, 1920f)
        assertTrue(ext2 > ext1)
    }

    @Test
    fun `getExtendedWidth at min height uses MIN_HEIGHT_SCALE`() {
        // Heights below 960 (MIN_HEIGHT_SCALE * REFERENCE_HEIGHT) should clamp
        val extAt960 = RenderConstants.getExtendedWidth(1080f, 960f)
        val extAt400 = RenderConstants.getExtendedWidth(1080f, 400f)
        assertEquals(extAt960, extAt400, 0.01f)
    }

    @Test
    fun `getHorizontalDensityScale is stable across height changes`() {
        // Density must not depend on height — only on screen width
        val scale1 = RenderConstants.getHorizontalDensityScale(0f, 1080f, 1920f)
        val scale2 = RenderConstants.getHorizontalDensityScale(0f, 1080f, 800f)
        assertEquals(scale1, scale2, 0.001f)
    }

    @Test
    fun `getHorizontalDensityScale grows with screen width`() {
        val scaleSmall = RenderConstants.getHorizontalDensityScale(0f, 720f, 1920f)
        val scaleLarge = RenderConstants.getHorizontalDensityScale(0f, 1440f, 1920f)
        assertTrue(scaleLarge > scaleSmall)
    }

    @Test
    fun `getHorizontalDensityScale clamps at minimum`() {
        val scale = RenderConstants.getHorizontalDensityScale(10f)
        assertEquals(0.25f, scale, 0.001f)
    }

    @Test
    fun `getExtendedOffset centers content`() {
        val offset = RenderConstants.getExtendedOffset(1080f, 1900.8f)
        assertEquals((1900.8f - 1080f) / 2f, offset, 0.01f)
    }

    @Test
    fun `getExtendedOffset is zero when widths match`() {
        val offset = RenderConstants.getExtendedOffset(1000f, 1000f)
        assertEquals(0f, offset, 0.001f)
    }

    @Test
    fun `getHillParallaxDepth at 0 returns HILLS_MIN`() {
        val depth = RenderConstants.getHillParallaxDepth(0f)
        assertEquals(0.50f, depth, 0.001f)
    }

    @Test
    fun `getHillParallaxDepth at 1 returns HILLS_MAX`() {
        val depth = RenderConstants.getHillParallaxDepth(1f)
        assertEquals(0.80f, depth, 0.001f)
    }

    @Test
    fun `getHillParallaxDepth at 0-5 returns midpoint`() {
        val depth = RenderConstants.getHillParallaxDepth(0.5f)
        assertEquals(0.65f, depth, 0.001f)
    }

    @Test
    fun `calculateEventDuration basic case`() {
        val dur = RenderConstants.calculateEventDuration(1.3f, 0.0001f)
        assertEquals(13000L, dur)
    }

    @Test
    fun `calculateEventDuration clamps to minimum`() {
        val dur = RenderConstants.calculateEventDuration(0.001f, 1f)
        assertEquals(1000L, dur)
    }

    @Test
    fun `calculateEventDuration clamps to maximum`() {
        val dur = RenderConstants.calculateEventDuration(100f, 0.00001f)
        assertEquals(180000L, dur)
    }

    @Test
    fun `calculateEventDuration handles zero speed`() {
        val dur = RenderConstants.calculateEventDuration(1f, 0f)
        // Speed coerced to 0.00001, distance=1 -> 100000ms (below the 180000 max)
        assertEquals(100000L, dur)
    }

    @Test
    fun `calculateEventDuration custom min max`() {
        val dur = RenderConstants.calculateEventDuration(
            1f, 0.001f, minMs = 2000L, maxMs = 5000L
        )
        // raw: 1/0.001 = 1000 -> clamped to minMs=2000
        assertEquals(2000L, dur)
    }
}
