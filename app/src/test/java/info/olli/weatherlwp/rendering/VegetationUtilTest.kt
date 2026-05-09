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
    fun `getHillY returns consistent result for same worldX`() {
        val hill = TreeRenderer.HillInfo(
            noiseSeed = 42,
            depthAmplitudeScale = 1.0f,
            depthFactor = 0.5f,
            heightOffset = 0.5f,
            hillFrequency = 2.0f,
            hillRoughness = 1.0f,
            hillAmplitude = 1.0f
        )
        val y1 = VegetationUtil.getHillY(100f, hill)
        val y2 = VegetationUtil.getHillY(100f, hill)
        assertEquals(y1, y2, 0.001f)
    }

    @Test
    fun `getHillY varies with worldX`() {
        val hill = TreeRenderer.HillInfo(
            noiseSeed = 42,
            depthAmplitudeScale = 1.0f,
            depthFactor = 0.5f,
            heightOffset = 0.5f,
            hillFrequency = 2.0f,
            hillRoughness = 1.0f,
            hillAmplitude = 1.0f
        )
        // Sample many positions — not all can produce the same float
        val values = (0..10).map { VegetationUtil.getHillY(it * 200f, hill) }
        val distinct = values.toSet().size
        assertTrue("Expected multiple distinct Y values but got $distinct", distinct > 1)
    }

    @Test
    fun `getHillY returns value near heightOffset`() {
        val hill = TreeRenderer.HillInfo(
            noiseSeed = 123,
            depthAmplitudeScale = 1.0f,
            depthFactor = 0.5f,
            heightOffset = 0.6f,
            hillFrequency = 2.0f,
            hillRoughness = 1.0f,
            hillAmplitude = 1.0f
        )
        val y = VegetationUtil.getHillY(0f, hill)
        // Y should be near heightOffset (within 0.12 range)
        assertTrue("Y ($y) should be between 0.4 and 0.8", y in 0.4f..0.8f)
    }

    @Test
    fun `getHillY low amplitude produces values close to heightOffset`() {
        val hill = TreeRenderer.HillInfo(
            noiseSeed = 42,
            depthAmplitudeScale = 0.1f,
            depthFactor = 0.5f,
            heightOffset = 0.5f,
            hillFrequency = 2.0f,
            hillRoughness = 1.0f,
            hillAmplitude = 0.1f
        )
        val y = VegetationUtil.getHillY(200f, hill)
        // With very low amplitude, variation is tiny so Y stays near
        // heightOffset + 0.12*(1 - HILL_BASE_HEIGHT) ≈ 0.578
        assertTrue("Y ($y) should be close to base value", y in 0.56f..0.60f)
    }

    @Test
    fun `getHillY with different seeds produces different terrain`() {
        val hill1 = TreeRenderer.HillInfo(
            noiseSeed = 42,
            depthAmplitudeScale = 1.0f,
            depthFactor = 0.5f,
            heightOffset = 0.5f,
            hillFrequency = 2.0f,
            hillRoughness = 1.0f,
            hillAmplitude = 1.0f
        )
        val hill2 = TreeRenderer.HillInfo(
            noiseSeed = 999,
            depthAmplitudeScale = 1.0f,
            depthFactor = 0.5f,
            heightOffset = 0.5f,
            hillFrequency = 2.0f,
            hillRoughness = 1.0f,
            hillAmplitude = 1.0f
        )
        // Sample many positions — at least some must differ between seeds
        val diffs = (0..10).count {
            val x = it * 200f
            VegetationUtil.getHillY(x, hill1) != VegetationUtil.getHillY(x, hill2)
        }
        assertTrue("Expected different terrain from different seeds but only $diffs/11 differed", diffs > 0)
    }

    @Test
    fun `noise function is deterministic`() {
        val v1 = VegetationUtil.noise(3.14f, 42)
        val v2 = VegetationUtil.noise(3.14f, 42)
        assertEquals(v1, v2, 0.0001f)
    }

    @Test
    fun `hash function produces values in 0 to 1`() {
        for (n in -100..100) {
            val h = VegetationUtil.hash(n)
            assertTrue("hash($n) = $h should be in [0, 1]", h in 0f..1f)
        }
    }
}
