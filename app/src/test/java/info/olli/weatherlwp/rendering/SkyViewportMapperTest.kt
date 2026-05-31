package info.olli.weatherlwp.rendering

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SkyViewportMapperTest {
    @Test
    fun `reference device maps normalized to full viewport`() {
        // On reference device (1080x1920), fieldWidth = max(1920*0.5625, 1080) = 1080
        val width = 1080f
        val height = 1920f

        assertEquals(0f, SkyViewportMapper.toScreenX(0f, width, height), 0.0001f)
        assertEquals(width, SkyViewportMapper.toScreenX(1f, width, height), 0.0001f)
        assertEquals(0f, SkyViewportMapper.toScreenY(0f, width, height), 0.0001f)
        assertEquals(height, SkyViewportMapper.toScreenY(1f, width, height), 0.0001f)
    }

    @Test
    fun `taller device spreads X beyond screen edges`() {
        // On 1080x2400, fieldWidth = 2400*0.5625 = 1350, offset = 135
        val width = 1080f
        val height = 2400f

        val left = SkyViewportMapper.toScreenX(0f, width, height)
        val right = SkyViewportMapper.toScreenX(1f, width, height)
        val center = SkyViewportMapper.toScreenX(0.5f, width, height)

        assertTrue("Left edge should be off-screen", left < 0f)
        assertTrue("Right edge should be off-screen", right > width)
        assertEquals(width / 2f, center, 0.0001f)
        // Field extent equals height * REFERENCE_ASPECT_RATIO
        assertEquals(height * RenderConstants.REFERENCE_ASPECT_RATIO, right - left, 0.01f)
    }

    @Test
    fun `landscape 16-9 maps to screen width with no overflow`() {
        // On 1920x1080 (16:9, same ratio as reference), scaleFactor = 1.0
        val width = 1920f
        val height = 1080f

        assertEquals(0f, SkyViewportMapper.toScreenX(0f, width, height), 0.0001f)
        assertEquals(width, SkyViewportMapper.toScreenX(1f, width, height), 0.0001f)
    }

    @Test
    fun `wider landscape expands in all directions`() {
        // On 2400x1080 (20:9), scaleFactor = (2400/1080) / (16/9) = 1.25
        // fieldWidth = 2400 * 1.25 = 3000, offset = 300
        val width = 2400f
        val height = 1080f

        val left = SkyViewportMapper.toScreenX(0f, width, height)
        val right = SkyViewportMapper.toScreenX(1f, width, height)
        val center = SkyViewportMapper.toScreenX(0.5f, width, height)

        assertTrue("Left edge should be off-screen", left < 0f)
        assertTrue("Right edge should be off-screen", right > width)
        assertEquals(width / 2f, center, 0.0001f)
        assertEquals(3000f, right - left, 0.01f)

        // Y should also overflow symmetrically
        val top = SkyViewportMapper.toScreenY(0f, width, height)
        val bottom = SkyViewportMapper.toScreenY(1f, width, height)
        val centerY = SkyViewportMapper.toScreenY(0.5f, width, height)

        assertTrue("Top edge should be off-screen", top < 0f)
        assertTrue("Bottom edge should be off-screen", bottom > height)
        assertEquals(height / 2f, centerY, 0.0001f)
    }

    @Test
    fun `taller device spreads Y beyond screen edges`() {
        // On 1080x2400, fieldWidth=1350, fieldHeight=3000, offsetY=300
        val width = 1080f
        val height = 2400f

        val top = SkyViewportMapper.toScreenY(0f, width, height)
        val bottom = SkyViewportMapper.toScreenY(1f, width, height)
        val center = SkyViewportMapper.toScreenY(0.5f, width, height)

        assertTrue("Top edge should be off-screen", top < 0f)
        assertTrue("Bottom edge should be off-screen", bottom > height)
        assertEquals(height / 2f, center, 0.0001f)
        // fieldHeight = height * fieldWidth / width = 2400 * 1350 / 1080 = 3000
        assertEquals(3000f, bottom - top, 0.01f)
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
