package info.olli.weatherlwp.rendering

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SkyViewportMapperTest {
    @Test
    fun `reference device centers the star field and overflows symmetrically`() {
        // Isotropic square field: extent = max(1080, 1920) * 1.15 = 2208
        val width = 1080f
        val height = 1920f

        // Midpoint maps to the screen center in both axes
        assertEquals(width / 2f, SkyViewportMapper.toScreenX(0.5f, width, height), 0.0001f)
        assertEquals(height / 2f, SkyViewportMapper.toScreenY(0.5f, width, height), 0.0001f)

        // Edges overflow the screen symmetrically about the center
        val left = SkyViewportMapper.toScreenX(0f, width, height)
        val right = SkyViewportMapper.toScreenX(1f, width, height)
        assertTrue("Left edge off-screen", left < 0f)
        assertTrue("Right edge off-screen", right > width)
        assertEquals("X edges symmetric about center", width, left + right, 0.001f)

        val top = SkyViewportMapper.toScreenY(0f, width, height)
        val bottom = SkyViewportMapper.toScreenY(1f, width, height)
        assertTrue("Top edge off-screen", top < 0f)
        assertTrue("Bottom edge off-screen", bottom > height)
        assertEquals("Y edges symmetric about center", height, top + bottom, 0.001f)
    }

    @Test
    fun `star field is isotropic - equal pixel span in X and Y`() {
        // A unit step in X must cover the same pixels as a unit step in Y in every orientation,
        // so the star pattern keeps round, undistorted spacing.
        for ((w, h) in listOf(1080f to 1920f, 1920f to 1080f, 2400f to 1080f, 1080f to 2400f, 1440f to 1440f)) {
            val spanX = SkyViewportMapper.toScreenX(1f, w, h) - SkyViewportMapper.toScreenX(0f, w, h)
            val spanY = SkyViewportMapper.toScreenY(1f, w, h) - SkyViewportMapper.toScreenY(0f, w, h)
            assertEquals("Span should be isotropic for ${w}x${h}", spanX, spanY, 0.0001f)
        }
    }

    @Test
    fun `field overflows every edge in all orientations`() {
        for ((w, h) in listOf(1080f to 1920f, 1920f to 1080f, 2400f to 1080f, 1080f to 2400f)) {
            assertTrue("left<0 for ${w}x${h}", SkyViewportMapper.toScreenX(0f, w, h) < 0f)
            assertTrue("right>w for ${w}x${h}", SkyViewportMapper.toScreenX(1f, w, h) > w)
            assertTrue("top<0 for ${w}x${h}", SkyViewportMapper.toScreenY(0f, w, h) < 0f)
            assertTrue("bottom>h for ${w}x${h}", SkyViewportMapper.toScreenY(1f, w, h) > h)
        }
    }

    @Test
    fun `widening viewport does not stretch stars horizontally`() {
        // While height stays the dominant dimension, widening the screen keeps the field
        // extent constant, so existing stars keep their spacing (no horizontal stretch);
        // the wider viewport simply reveals more of the centered field.
        val height = 1920f
        val narrowSpan = SkyViewportMapper.toScreenX(1f, 1080f, height) - SkyViewportMapper.toScreenX(0f, 1080f, height)
        val wideSpan = SkyViewportMapper.toScreenX(1f, 1400f, height) - SkyViewportMapper.toScreenX(0f, 1400f, height)
        assertEquals("Star spacing unchanged when widening", narrowSpan, wideSpan, 0.0001f)
    }

    @Test
    fun `extent grows with the larger dimension and is square`() {
        // Extending the long axis scales the whole isotropic field, so stars spread out
        // uniformly in all directions rather than only along one axis.
        val baseExtent = SkyViewportMapper.starFieldExtent(1080f, 1920f)
        val taller = SkyViewportMapper.starFieldExtent(1080f, 2400f)
        val wider = SkyViewportMapper.starFieldExtent(2400f, 1080f)
        assertTrue("Taller screen grows field", taller > baseExtent)
        assertTrue("Wider screen grows field", wider > baseExtent)
        // Field is square: extent is identical whether the long side is width or height
        assertEquals(taller, wider, 0.0001f)
    }

    @Test
    fun `projection applies parallax offsets`() {
        val width = 1080f
        val height = 1920f
        val x = SkyViewportMapper.toScreenX(0.5f, width, height, parallaxX = 12f)
        val y = SkyViewportMapper.toScreenY(0.5f, width, height, parallaxY = -20f)

        assertEquals(552f, x, 0.0001f)
        assertEquals(940f, y, 0.0001f)
    }

    @Test
    fun `radius scale uses height as reference dimension`() {
        val tinyScale = SkyViewportMapper.starRadiusScale(200f, 300f)
        val largeScale = SkyViewportMapper.starRadiusScale(2160f, 3840f)

        assertTrue(tinyScale >= 0.5f)
        assertTrue(largeScale > tinyScale)
        // At reference height, scale should be 1.0
        assertEquals(1.0f, SkyViewportMapper.starRadiusScale(1080f, 1920f), 0.0001f)
    }
}
