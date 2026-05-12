package info.olli.weatherlwp.rendering

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests proving the viewport-based camera zoom model works correctly.
 *
 * These tests would have FAILED on the previous broken implementation where:
 *   screenX = screenWidth/2 + worldX * heightScale
 * and generation expanded to compensate for height changes.
 */
class CameraZoomTest {

    // ===================== 1. Zoom test: worldX → different screenX when height changes =====================

    @Test
    fun `same worldX projects to different screenX when worldHeight changes`() {
        val worldX = 400f
        val screenWidth = 1080f
        val screenHeight = 1920f

        // Default zoom (worldHeight = REFERENCE_HEIGHT = 1920)
        val normal = WorldViewportTransform(screenWidth, screenHeight, worldHeight = 1920f)
        val screenXNormal = normal.worldToScreenX(worldX)

        // Zoomed in (worldHeight = 960 → half the visible world)
        val zoomedIn = WorldViewportTransform(screenWidth, screenHeight, worldHeight = 960f)
        val screenXZoomed = zoomedIn.worldToScreenX(worldX)

        // Screen position MUST change — this is the core zoom property
        assertTrue("screenX must differ under zoom",
            kotlin.math.abs(screenXNormal - screenXZoomed) > 50f)
    }

    @Test
    fun `zoom changes screenX on different screen heights`() {
        val worldX = 300f
        val screenWidth = 1080f

        // Portrait
        val portrait = WorldViewportTransform(screenWidth, 1920f)
        val screenXPortrait = portrait.worldToScreenX(worldX)

        // Short screen (e.g. preview)
        val shortScreen = WorldViewportTransform(screenWidth, 960f)
        val screenXShort = shortScreen.worldToScreenX(worldX)

        // Screen position MUST change when screen height changes
        // (because aspect ratio changes, so worldWidth changes)
        assertNotEquals("screenX must differ on different screen heights",
            screenXPortrait, screenXShort, 1f)
    }

    // ===================== 2. Width shrink test =====================

    @Test
    fun `visibleWorldWidth decreases when visibleWorldHeight decreases`() {
        val screenWidth = 1080f
        val screenHeight = 1920f

        val bigView = Viewport(screenWidth.toInt(), screenHeight.toInt(), worldHeight = 2000f)
        val smallView = Viewport(screenWidth.toInt(), screenHeight.toInt(), worldHeight = 1000f)

        assertTrue("smaller worldHeight must produce smaller worldWidth",
            smallView.worldWidth < bigView.worldWidth)

        // Specifically: worldWidth = worldHeight * aspect, so halving worldHeight halves worldWidth
        assertEquals("worldWidth should halve when worldHeight halves",
            bigView.worldWidth / 2f, smallView.worldWidth, 0.01f)
    }

    @Test
    fun `worldRect shrinks proportionally with zoom`() {
        val vp1 = Viewport(1080, 1920, worldHeight = 1920f)
        val vp2 = Viewport(1080, 1920, worldHeight = 960f)

        assertTrue("zoomed-in rect should be narrower",
            vp2.worldRect.width < vp1.worldRect.width)
        assertTrue("zoomed-in rect should be shorter",
            vp2.worldRect.height < vp1.worldRect.height)

        assertEquals("width ratio matches height ratio",
            vp1.worldRect.width / vp2.worldRect.width,
            vp1.worldRect.height / vp2.worldRect.height,
            0.01f)
    }

    // ===================== 3. Landscape regression =====================

    @Test
    fun `landscape short-height zoom behaves same as portrait zoom`() {
        val worldX = 200f

        // Portrait: zoom from worldHeight 1920 to 960 (2x zoom)
        val portraitNormal = WorldViewportTransform(1080f, 1920f, worldHeight = 1920f)
        val portraitZoomed = WorldViewportTransform(1080f, 1920f, worldHeight = 960f)
        val portraitDistNormal = portraitNormal.worldToScreenX(worldX) - portraitNormal.centerX
        val portraitDistZoomed = portraitZoomed.worldToScreenX(worldX) - portraitZoomed.centerX
        val portraitRatio = portraitDistZoomed / portraitDistNormal

        // Landscape: zoom from worldHeight 1920 to 960 (2x zoom)
        val landscapeNormal = WorldViewportTransform(1920f, 1080f, worldHeight = 1920f)
        val landscapeZoomed = WorldViewportTransform(1920f, 1080f, worldHeight = 960f)
        val landscapeDistNormal = landscapeNormal.worldToScreenX(worldX) - landscapeNormal.centerX
        val landscapeDistZoomed = landscapeZoomed.worldToScreenX(worldX) - landscapeZoomed.centerX
        val landscapeRatio = landscapeDistZoomed / landscapeDistNormal

        // 2x zoom should double the distance-from-center in BOTH orientations
        assertEquals("portrait: 2x zoom should double distance from center",
            2f, portraitRatio, 0.01f)
        assertEquals("landscape: 2x zoom should double distance from center",
            2f, landscapeRatio, 0.01f)
        assertEquals("zoom ratio must be orientation-independent",
            portraitRatio, landscapeRatio, 0.01f)
    }

    @Test
    fun `no landscape special case in worldWidth calculation`() {
        // worldWidth = worldHeight * aspect — no max(w,h) hack
        val portrait = Viewport(1080, 1920, worldHeight = 1920f)
        val landscape = Viewport(1920, 1080, worldHeight = 1920f)

        assertEquals("portrait worldWidth", 1920f * 1080f / 1920f, portrait.worldWidth, 0.01f)
        assertEquals("landscape worldWidth", 1920f * 1920f / 1080f, landscape.worldWidth, 0.01f)

        // Landscape wider than portrait (correct: wider screen shows more)
        assertTrue("landscape should have wider world",
            landscape.worldWidth > portrait.worldWidth)
    }

    // ===================== 4. Spacing invariant =====================

    @Test
    fun `two objects deltaWorldX apart project to deltaScreenX equals deltaWorldX times pixelsPerUnit`() {
        val worldX1 = 100f
        val worldX2 = 350f
        val deltaWorldX = worldX2 - worldX1

        for (wh in listOf(960f, 1920f, 3840f)) {
            val vp = Viewport(1080, 1920, worldHeight = wh)
            val mapper = WorldToScreenMapper(vp)
            val screenX1 = mapper.x(worldX1)
            val screenX2 = mapper.x(worldX2)
            val deltaScreenX = screenX2 - screenX1

            assertEquals("spacing invariant at worldHeight=$wh",
                deltaWorldX * vp.pixelsPerUnit, deltaScreenX, 0.01f)
        }
    }

    // ===================== 5. No fixed-X regression (structural) =====================

    @Test
    fun `WorldViewportTransform delegates to Viewport mapper`() {
        // Verify the transform's worldToScreenX matches the Viewport mapper exactly.
        // This proves the old screenWidth div 2 + worldX * heightScale is gone.
        val t = WorldViewportTransform(1080f, 1920f)
        val mapper = WorldToScreenMapper(t.viewport)

        for (wx in listOf(-500f, -200f, 0f, 200f, 500f)) {
            assertEquals("transform must match mapper at worldX=$wx",
                mapper.x(wx), t.worldToScreenX(wx), 0.001f)
        }
    }

    @Test
    fun `WorldViewportTransform exposes viewport and worldWidth`() {
        val t = WorldViewportTransform(1080f, 1920f, worldHeight = 1920f)

        // worldWidth must equal worldHeight * aspect
        val expectedWidth = 1920f * (1080f / 1920f)
        assertEquals("worldWidth must match worldHeight * aspect",
            expectedWidth, t.worldWidth, 0.01f)

        // viewport must be accessible
        assertEquals("viewport worldHeight", 1920f, t.viewport.worldHeight, 0.01f)
    }

    // ===================== 6. Culling: objects outside viewport =====================

    @Test
    fun `visibleWorldRangeX contracts under zoom`() {
        val normal = WorldViewportTransform(1080f, 1920f, worldHeight = 1920f)
        val zoomed = WorldViewportTransform(1080f, 1920f, worldHeight = 960f)

        val rangeNormal = normal.visibleWorldRangeX()
        val rangeZoomed = zoomed.visibleWorldRangeX()

        assertTrue("zoomed range should be narrower",
            rangeZoomed.endInclusive < rangeNormal.endInclusive)
        assertTrue("zoomed range left should be closer to center",
            rangeZoomed.start > rangeNormal.start)
    }

    @Test
    fun `object outside viewport range is culled`() {
        val t = WorldViewportTransform(1080f, 1920f, worldHeight = 960f)
        val range = t.visibleWorldRangeX()

        // Object well outside the visible range
        val farObject = range.endInclusive + 500f
        assertTrue("object at worldX=$farObject should be outside visible range",
            farObject > range.endInclusive)

        // Its screen position is off-screen
        val screenX = t.worldToScreenX(farObject)
        assertTrue("off-world object should project off screen",
            screenX > 1080f)
    }

    // ===================== 7. Generation range is height-independent =====================

    @Test
    fun `getVisibleWorldHalf does not change with height`() {
        val half1 = RenderConstants.getVisibleWorldHalf(1080f, 1920f)
        val half2 = RenderConstants.getVisibleWorldHalf(1080f, 960f)
        val half3 = RenderConstants.getVisibleWorldHalf(1080f, 480f)

        assertEquals("same width should give same generation half regardless of height",
            half1, half2, 0.01f)
        assertEquals("same width should give same generation half regardless of height",
            half1, half3, 0.01f)
    }

    @Test
    fun `getVisibleWorldHalf increases with screen width`() {
        val narrow = RenderConstants.getVisibleWorldHalf(720f)
        val normal = RenderConstants.getVisibleWorldHalf(1080f)
        val wide = RenderConstants.getVisibleWorldHalf(1920f)

        assertTrue("wider screen should have wider generation range",
            wide > normal && normal > narrow)
    }
}
