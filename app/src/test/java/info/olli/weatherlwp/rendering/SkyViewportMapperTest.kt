package info.olli.weatherlwp.rendering

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SkyViewportMapperTest {
    @Test
    fun `normalized coordinates fill full viewport`() {
        val width = 1080f
        val height = 2400f

        assertEquals(0f, SkyViewportMapper.toScreenX(0f, width), 0.0001f)
        assertEquals(width, SkyViewportMapper.toScreenX(1f, width), 0.0001f)
        assertEquals(0f, SkyViewportMapper.toScreenY(0f, height), 0.0001f)
        assertEquals(height, SkyViewportMapper.toScreenY(1f, height), 0.0001f)
    }

    @Test
    fun `projection applies parallax offsets`() {
        val x = SkyViewportMapper.toScreenX(0.5f, 1000f, parallaxX = 12f)
        val y = SkyViewportMapper.toScreenY(0.5f, 2000f, parallaxY = -20f)

        assertEquals(512f, x, 0.0001f)
        assertEquals(980f, y, 0.0001f)
    }

    @Test
    fun `radius scale stays bounded`() {
        val tinyScale = SkyViewportMapper.starRadiusScale(200f, 300f)
        val largeScale = SkyViewportMapper.starRadiusScale(2160f, 3840f)

        assertTrue(tinyScale >= 0.5f)
        assertTrue(largeScale > tinyScale)
    }
}
