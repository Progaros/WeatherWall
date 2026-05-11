package info.olli.weatherlwp.rendering

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ViewportTest {

    // ===================== WorldRect =====================

    @Test
    fun `WorldRect width and height computed correctly`() {
        val r = WorldRect(-100f, -50f, 300f, 150f)
        assertEquals(400f, r.width, 0.001f)
        assertEquals(200f, r.height, 0.001f)
    }

    @Test
    fun `WorldRect center is midpoint`() {
        val r = WorldRect(-100f, -50f, 300f, 150f)
        assertEquals(100f, r.centerX, 0.001f)
        assertEquals(50f, r.centerY, 0.001f)
    }

    @Test
    fun `WorldRect expand adds padding symmetrically`() {
        val r = WorldRect(0f, 0f, 100f, 100f)
        val e = r.expand(10f, 20f)
        assertEquals(-10f, e.left, 0.001f)
        assertEquals(-20f, e.top, 0.001f)
        assertEquals(110f, e.right, 0.001f)
        assertEquals(120f, e.bottom, 0.001f)
    }

    // ===================== Viewport basics =====================

    @Test
    fun `aspect ratio portrait`() {
        val v = Viewport(1080, 1920)
        assertEquals(1080f / 1920f, v.aspect, 0.001f)
    }

    @Test
    fun `aspect ratio landscape`() {
        val v = Viewport(1920, 1080)
        assertEquals(1920f / 1080f, v.aspect, 0.001f)
    }

    @Test
    fun `worldWidth equals worldHeight times aspect`() {
        val v = Viewport(1080, 1920, worldHeight = 100f)
        assertEquals(100f * (1080f / 1920f), v.worldWidth, 0.001f)
    }

    @Test
    fun `worldWidth changes with worldHeight`() {
        val v1 = Viewport(1080, 1920, worldHeight = 100f)
        val v2 = Viewport(1080, 1920, worldHeight = 50f)
        assertTrue(v2.worldWidth < v1.worldWidth)
        assertEquals(v1.worldWidth / 2f, v2.worldWidth, 0.001f)
    }

    @Test
    fun `horizontalZoomFactor scales worldWidth`() {
        val v1 = Viewport(1080, 1920, worldHeight = 100f, horizontalZoomFactor = 1f)
        val v2 = Viewport(1080, 1920, worldHeight = 100f, horizontalZoomFactor = 2f)
        assertEquals(v1.worldWidth * 2f, v2.worldWidth, 0.001f)
    }

    @Test
    fun `worldRect centered at origin`() {
        val v = Viewport(1080, 1920, centerWorldX = 0f, centerWorldY = 0f, worldHeight = 100f)
        val r = v.worldRect
        assertEquals(-v.worldWidth / 2f, r.left, 0.001f)
        assertEquals(v.worldWidth / 2f, r.right, 0.001f)
        assertEquals(-50f, r.top, 0.001f)
        assertEquals(50f, r.bottom, 0.001f)
    }

    @Test
    fun `worldRect shifts with centerWorldX`() {
        val v = Viewport(1080, 1920, centerWorldX = 200f, worldHeight = 100f)
        val r = v.worldRect
        assertEquals(200f, r.centerX, 0.001f)
    }

    @Test
    fun `pixelsPerUnit uniform scaling`() {
        val v = Viewport(1080, 1920, worldHeight = 1920f)
        assertEquals(1f, v.pixelsPerUnit, 0.001f)

        val v2 = Viewport(1080, 960, worldHeight = 1920f)
        assertEquals(0.5f, v2.pixelsPerUnit, 0.001f)
    }

    // ===================== Zoom behavior: smaller worldHeight = zoom in =====================

    @Test
    fun `smaller worldHeight produces narrower worldWidth`() {
        val zoomedOut = Viewport(1080, 1920, worldHeight = 200f)
        val zoomedIn = Viewport(1080, 1920, worldHeight = 100f)
        assertTrue(zoomedIn.worldWidth < zoomedOut.worldWidth)
    }

    @Test
    fun `smaller worldHeight means higher pixelsPerUnit`() {
        val zoomedOut = Viewport(1080, 1920, worldHeight = 200f)
        val zoomedIn = Viewport(1080, 1920, worldHeight = 100f)
        assertTrue("zoom in → more pixels per unit", zoomedIn.pixelsPerUnit > zoomedOut.pixelsPerUnit)
    }

    // ===================== Aspect ratio consistency =====================

    @Test
    fun `portrait and landscape same worldHeight produce correct widths`() {
        val portrait = Viewport(1080, 1920, worldHeight = 1920f)
        val landscape = Viewport(1920, 1080, worldHeight = 1920f)
        // Portrait sees narrower world, landscape sees wider
        assertTrue(landscape.worldWidth > portrait.worldWidth)
        assertEquals(1080f, portrait.worldWidth, 0.1f) // 1920 * 1080/1920
        assertEquals(1920f * 1920f / 1080f, landscape.worldWidth, 0.1f)
    }

    @Test
    fun `short height wide screen has wider world`() {
        val normal = Viewport(1080, 1920, worldHeight = 1920f)
        val shortWide = Viewport(1080, 500, worldHeight = 1920f)
        assertTrue("short-wide sees more world width", shortWide.worldWidth > normal.worldWidth)
    }

    // ===================== WorldToScreenMapper =====================

    @Test
    fun `center of world maps to center of screen`() {
        val v = Viewport(1080, 1920, centerWorldX = 0f, centerWorldY = 0f, worldHeight = 100f)
        val m = WorldToScreenMapper(v)
        assertEquals(540f, m.x(0f), 0.1f)
        assertEquals(960f, m.y(0f), 0.1f)
    }

    @Test
    fun `left edge of worldRect maps to screen x 0`() {
        val v = Viewport(1080, 1920, centerWorldX = 0f, worldHeight = 100f)
        val m = WorldToScreenMapper(v)
        assertEquals(0f, m.x(v.worldRect.left), 0.1f)
    }

    @Test
    fun `right edge of worldRect maps to screen width`() {
        val v = Viewport(1080, 1920, centerWorldX = 0f, worldHeight = 100f)
        val m = WorldToScreenMapper(v)
        assertEquals(1080f, m.x(v.worldRect.right), 0.1f)
    }

    @Test
    fun `top of worldRect maps to screen y 0`() {
        val v = Viewport(1080, 1920, centerWorldY = 0f, worldHeight = 100f)
        val m = WorldToScreenMapper(v)
        assertEquals(0f, m.y(v.worldRect.top), 0.1f)
    }

    @Test
    fun `bottom of worldRect maps to screen height`() {
        val v = Viewport(1080, 1920, centerWorldY = 0f, worldHeight = 100f)
        val m = WorldToScreenMapper(v)
        assertEquals(1920f, m.y(v.worldRect.bottom), 0.1f)
    }

    @Test
    fun `length uses pixelsPerUnit`() {
        val v = Viewport(1080, 1920, worldHeight = 960f)
        val m = WorldToScreenMapper(v)
        // pixelsPerUnit = 1920/960 = 2
        assertEquals(200f, m.length(100f), 0.01f)
    }

    @Test
    fun `worldX round-trips through mapper`() {
        val v = Viewport(1080, 1920, centerWorldX = 50f, worldHeight = 400f)
        val m = WorldToScreenMapper(v)
        val original = 123.4f
        val screen = m.x(original)
        val recovered = m.worldX(screen)
        assertEquals(original, recovered, 0.01f)
    }

    @Test
    fun `worldY round-trips through mapper`() {
        val v = Viewport(1080, 1920, centerWorldY = 0.3f, worldHeight = 400f)
        val m = WorldToScreenMapper(v)
        val original = 77.7f
        val screen = m.y(original)
        val recovered = m.worldY(screen)
        assertEquals(original, recovered, 0.01f)
    }

    // ===================== Zoom-in regression: smaller worldHeight narrows visible range =====================

    @Test
    fun `zoom in reduces visible world rect width`() {
        val wide = Viewport(1080, 1920, worldHeight = 2000f)
        val narrow = Viewport(1080, 1920, worldHeight = 1000f)
        assertTrue(narrow.worldRect.width < wide.worldRect.width)
    }

    @Test
    fun `zoom in moves edge world positions further from screen edges`() {
        // A world point at x=500 should be further from screen edge when zoomed in
        val zoomedOut = Viewport(1080, 1920, worldHeight = 2000f)
        val zoomedIn = Viewport(1080, 1920, worldHeight = 1000f)
        val mOut = WorldToScreenMapper(zoomedOut)
        val mIn = WorldToScreenMapper(zoomedIn)
        // Distance from center on screen
        val distOut = mOut.x(500f) - 540f
        val distIn = mIn.x(500f) - 540f
        assertTrue("zoom-in pushes worldX=500 further from center on screen", distIn > distOut)
    }

    @Test
    fun `landscape short height consistent zoom`() {
        // Landscape 1920x1080: aspect = 1.78
        // worldHeight = 1920 → worldWidth = 1920*1.78 = 3413
        // Compare with portrait 1080x1920: worldWidth = 1920*0.5625 = 1080
        // Landscape sees wider world — correct behavior
        val portrait = Viewport(1080, 1920, worldHeight = 1920f)
        val landscape = Viewport(1920, 1080, worldHeight = 1920f)
        assertTrue("landscape sees wider world", landscape.worldWidth > portrait.worldWidth)
        // Verify screen center maps consistently
        val mP = WorldToScreenMapper(portrait)
        val mL = WorldToScreenMapper(landscape)
        assertEquals(540f, mP.x(0f), 0.1f)
        assertEquals(960f, mL.x(0f), 0.1f)
    }

    // ===================== Parallax viewport construction =====================

    @Test
    fun `parallax far layer has less camera shift`() {
        val cameraCenterX = 100f
        val worldAnchor = 0f
        val farParallax = 0.2f
        val nearParallax = 0.8f

        val farCenter = worldAnchor + (cameraCenterX - worldAnchor) * farParallax
        val nearCenter = worldAnchor + (cameraCenterX - worldAnchor) * nearParallax

        assertEquals(20f, farCenter, 0.001f)
        assertEquals(80f, nearCenter, 0.001f)
        assertTrue(farCenter < nearCenter)
    }

    @Test
    fun `parallax layers share same worldHeight and worldWidth`() {
        val base = Viewport(1080, 1920, centerWorldX = 0f, worldHeight = 1920f)
        val far = base.copy(centerWorldX = 0f + (100f - 0f) * 0.2f)
        val near = base.copy(centerWorldX = 0f + (100f - 0f) * 0.8f)
        assertEquals(base.worldHeight, far.worldHeight, 0f)
        assertEquals(base.worldWidth, far.worldWidth, 0.001f)
        assertEquals(base.worldHeight, near.worldHeight, 0f)
        assertEquals(base.worldWidth, near.worldWidth, 0.001f)
    }
}
