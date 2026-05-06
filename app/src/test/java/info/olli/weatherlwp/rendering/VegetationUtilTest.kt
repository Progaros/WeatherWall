package info.olli.weatherlwp.rendering

import android.graphics.Color
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VegetationUtilTest {

    @Test
    fun `autumnColor returns base color when weight is zero`() {
        val green = Color.rgb(30, 80, 40)
        val result = VegetationUtil.autumnColor(green, 0f, 0)
        assertEquals(green, result)
    }

    @Test
    fun `autumnColor returns base color when weight is negative`() {
        val green = Color.rgb(30, 80, 40)
        val result = VegetationUtil.autumnColor(green, -0.5f, 0)
        assertEquals(green, result)
    }

    @Test
    fun `autumnColor blends towards target at full weight`() {
        val green = Color.rgb(30, 80, 40)
        val result = VegetationUtil.autumnColor(green, 1f, 0)
        // hueShift = (0*37) % 3 = 0 -> target (200, 90, 20) Orange
        val r = Color.red(result)
        val g = Color.green(result)
        val b = Color.blue(result)
        assertEquals(200, r)
        assertEquals(90, g)
        assertEquals(20, b)
    }

    @Test
    fun `autumnColor partial weight blends proportionally`() {
        val green = Color.rgb(0, 100, 0)
        val result = VegetationUtil.autumnColor(green, 0.5f, 0)
        // hueShift=0 -> target (200, 90, 20)
        // r = 0 + (200-0)*0.5 = 100
        // g = 100 + (90-100)*0.5 = 95
        // b = 0 + (20-0)*0.5 = 10
        val r = Color.red(result)
        val g = Color.green(result)
        val b = Color.blue(result)
        assertEquals(100, r)
        assertEquals(95, g)
        assertEquals(10, b)
    }

    @Test
    fun `autumnColor hueShift 1 is golden`() {
        val green = Color.rgb(0, 0, 0)
        // (1*37) % 3 = 1 -> target (180, 140, 30) Golden
        val result = VegetationUtil.autumnColor(green, 1f, 1)
        val r = Color.red(result)
        val g = Color.green(result)
        val b = Color.blue(result)
        assertEquals(180, r)
        assertEquals(140, g)
        assertEquals(30, b)
    }

    @Test
    fun `autumnColor hueShift 2 is red-orange`() {
        val green = Color.rgb(0, 0, 0)
        // (2*37) % 3 = 2 -> target (190, 60, 25) Red-orange
        val result = VegetationUtil.autumnColor(green, 1f, 2)
        val r = Color.red(result)
        val g = Color.green(result)
        val b = Color.blue(result)
        assertEquals(190, r)
        assertEquals(60, g)
        assertEquals(25, b)
    }

    @Test
    fun `autumnColor clamps to 0-255 range`() {
        val bright = Color.rgb(255, 255, 255)
        val result = VegetationUtil.autumnColor(bright, 1f, 0)
        val r = Color.red(result)
        val g = Color.green(result)
        val b = Color.blue(result)
        assertTrue(r in 0..255)
        assertTrue(g in 0..255)
        assertTrue(b in 0..255)
    }

    @Test
    fun `getHillY returns heightOffset when less than 2 control points`() {
        val hill = TreeRenderer.HillInfo(
            controlPoints = listOf(0.8f),
            depthFactor = 0.5f,
            heightOffset = 0.5f
        )
        val y = VegetationUtil.getHillY(0.5f, hill)
        assertEquals(0.5f, y, 0.001f)
    }

    @Test
    fun `getHillY returns heightOffset with empty control points`() {
        val hill = TreeRenderer.HillInfo(
            controlPoints = emptyList(),
            depthFactor = 0.5f,
            heightOffset = 0.3f
        )
        val y = VegetationUtil.getHillY(0.5f, hill)
        assertEquals(0.3f, y, 0.001f)
    }

    @Test
    fun `getHillY interpolates between control points`() {
        val hill = TreeRenderer.HillInfo(
            controlPoints = listOf(1.0f, 0.5f, 1.0f),
            depthFactor = 0.5f,
            heightOffset = 0.4f
        )
        // xRatio=0.5 -> indexFloat=1.0, index=1, fraction=0.0
        // p1=0.5, p2=1.0, interpolated=0.5
        // result = 0.4 + 0.12*(1-0.5) = 0.4 + 0.06 = 0.46
        val y = VegetationUtil.getHillY(0.5f, hill)
        assertEquals(0.46f, y, 0.001f)
    }

    @Test
    fun `getHillY at xRatio 0 uses first control point`() {
        val hill = TreeRenderer.HillInfo(
            controlPoints = listOf(0.8f, 0.5f, 0.2f),
            depthFactor = 0.5f,
            heightOffset = 0.3f
        )
        // xRatio=0 -> indexFloat=0, index=0, fraction=0
        // p1=0.8, interpolated=0.8
        // result = 0.3 + 0.12*(1-0.8) = 0.3 + 0.024 = 0.324
        val y = VegetationUtil.getHillY(0f, hill)
        assertEquals(0.324f, y, 0.001f)
    }

    @Test
    fun `getHillY at xRatio 1 uses last control point`() {
        val hill = TreeRenderer.HillInfo(
            controlPoints = listOf(0.8f, 0.5f, 0.2f),
            depthFactor = 0.5f,
            heightOffset = 0.3f
        )
        // xRatio=1.0 -> indexFloat=2.0, index clamped to 1, fraction=1.0
        // p1=0.5, p2=0.2, interpolated = 0.5 + 1.0*(0.2-0.5) = 0.2
        // result = 0.3 + 0.12*(1-0.2) = 0.3 + 0.096 = 0.396
        val y = VegetationUtil.getHillY(1f, hill)
        assertEquals(0.396f, y, 0.001f)
    }

    @Test
    fun `getHillY with flat control points returns heightOffset plus base`() {
        val hill = TreeRenderer.HillInfo(
            controlPoints = listOf(1.0f, 1.0f, 1.0f),
            depthFactor = 0.5f,
            heightOffset = 0.5f
        )
        // All points are 1.0, so interpolated=1.0
        // result = 0.5 + 0.12*(1-1.0) = 0.5
        val y = VegetationUtil.getHillY(0.5f, hill)
        assertEquals(0.5f, y, 0.001f)
    }
}
