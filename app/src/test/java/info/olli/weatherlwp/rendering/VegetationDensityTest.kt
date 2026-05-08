package info.olli.weatherlwp.rendering

import info.olli.weatherlwp.simulation.SimulationSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests verifying that density/count settings affect generated tree and bush counts,
 * and that generation remains deterministic.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VegetationDensityTest {

    private val sampleHills = listOf(
        TreeRenderer.HillInfo(
            controlPoints = List(100) { 0.5f },
            depthFactor = 0.6f,
            heightOffset = 0.7f
        ),
        TreeRenderer.HillInfo(
            controlPoints = List(100) { 0.5f },
            depthFactor = 0.7f,
            heightOffset = 0.8f
        )
    )

    private val extendedWidth = 2000f
    private val extendedOffset = 100f
    private val height = 1920f
    private val seed = 42

    // ===================== Tree density affects count =====================

    @Test
    fun `tree density 10 produces fewer trees than density 50`() {
        val renderer10 = TreeRenderer()
        renderer10.reloadSettings(settingsWithTreeDensity(10))
        renderer10.generateTrees(sampleHills, extendedWidth, extendedOffset, height, seed)

        val renderer50 = TreeRenderer()
        renderer50.reloadSettings(settingsWithTreeDensity(50))
        renderer50.generateTrees(sampleHills, extendedWidth, extendedOffset, height, seed)

        assertTrue(
            "density 10 (${treeCount(renderer10)}) should produce fewer trees than density 50 (${treeCount(renderer50)})",
            treeCount(renderer10) < treeCount(renderer50)
        )
    }

    @Test
    fun `tree density 100 produces more trees than density 50`() {
        val renderer50 = TreeRenderer()
        renderer50.reloadSettings(settingsWithTreeDensity(50))
        renderer50.generateTrees(sampleHills, extendedWidth, extendedOffset, height, seed)

        val renderer100 = TreeRenderer()
        renderer100.reloadSettings(settingsWithTreeDensity(100))
        renderer100.generateTrees(sampleHills, extendedWidth, extendedOffset, height, seed)

        assertTrue(
            "density 100 (${treeCount(renderer100)}) should produce more trees than density 50 (${treeCount(renderer50)})",
            treeCount(renderer100) > treeCount(renderer50)
        )
    }

    @Test
    fun `tree density 0 produces no trees`() {
        val renderer = TreeRenderer()
        renderer.reloadSettings(settingsWithTreeDensity(0))
        renderer.generateTrees(sampleHills, extendedWidth, extendedOffset, height, seed)

        assertEquals(0, treeCount(renderer))
    }

    // ===================== Tree generation is deterministic =====================

    @Test
    fun `same seed and density produces identical tree count`() {
        val r1 = TreeRenderer()
        r1.reloadSettings(settingsWithTreeDensity(50))
        r1.generateTrees(sampleHills, extendedWidth, extendedOffset, height, seed)

        val r2 = TreeRenderer()
        r2.reloadSettings(settingsWithTreeDensity(50))
        r2.generateTrees(sampleHills, extendedWidth, extendedOffset, height, seed)

        assertEquals(treeCount(r1), treeCount(r2))
    }

    @Test
    fun `same seed and density produces identical tree positions`() {
        val r1 = TreeRenderer()
        r1.reloadSettings(settingsWithTreeDensity(50))
        r1.generateTrees(sampleHills, extendedWidth, extendedOffset, height, seed)

        val r2 = TreeRenderer()
        r2.reloadSettings(settingsWithTreeDensity(50))
        r2.generateTrees(sampleHills, extendedWidth, extendedOffset, height, seed)

        val trees1 = allTrees(r1)
        val trees2 = allTrees(r2)
        for (i in trees1.indices) {
            assertEquals(trees1[i].worldX, trees2[i].worldX, 0f)
            assertEquals(trees1[i].yRatio, trees2[i].yRatio, 0f)
        }
    }

    @Test
    fun `different density changes tree count`() {
        val r1 = TreeRenderer()
        r1.reloadSettings(settingsWithTreeDensity(50))
        r1.generateTrees(sampleHills, extendedWidth, extendedOffset, height, seed)

        val r2 = TreeRenderer()
        r2.reloadSettings(settingsWithTreeDensity(80))
        r2.generateTrees(sampleHills, extendedWidth, extendedOffset, height, seed)

        assertNotEquals("different density should produce different count", treeCount(r1), treeCount(r2))
    }

    // ===================== Height-only resize does not change tree count =====================

    @Test
    fun `height-only change does not affect tree count`() {
        val w = 1080f

        val r1 = TreeRenderer()
        r1.reloadSettings(settingsWithTreeDensity(50))
        val ew1 = RenderConstants.getExtendedWidth(w, 1920f)
        val eo1 = RenderConstants.getExtendedOffset(w, ew1)
        r1.generateTrees(sampleHills, ew1, eo1, 1920f, seed)

        val r2 = TreeRenderer()
        r2.reloadSettings(settingsWithTreeDensity(50))
        val ew2 = RenderConstants.getExtendedWidth(w, 2400f)
        val eo2 = RenderConstants.getExtendedOffset(w, ew2)
        r2.generateTrees(sampleHills, ew2, eo2, 2400f, seed)

        // Same width → same generation world range → same cell indices → same count
        assertEquals(
            "height-only resize should not change tree count",
            treeCount(r1), treeCount(r2)
        )
    }

    // ===================== Bush density affects count =====================

    @Test
    fun `bush density 10 produces fewer bushes than density 50`() {
        val r10 = BushRenderer()
        r10.reloadSettings(settingsWithBushDensity(10))
        r10.generateBushes(sampleHills, extendedWidth, extendedOffset, height, seed)

        val r50 = BushRenderer()
        r50.reloadSettings(settingsWithBushDensity(50))
        r50.generateBushes(sampleHills, extendedWidth, extendedOffset, height, seed)

        assertTrue(
            "bush density 10 (${bushCount(r10)}) < density 50 (${bushCount(r50)})",
            bushCount(r10) < bushCount(r50)
        )
    }

    @Test
    fun `bush density 0 produces no bushes`() {
        val renderer = BushRenderer()
        renderer.reloadSettings(settingsWithBushDensity(0))
        renderer.generateBushes(sampleHills, extendedWidth, extendedOffset, height, seed)

        assertEquals(0, bushCount(renderer))
    }

    @Test
    fun `same seed and bush density produces identical count`() {
        val r1 = BushRenderer()
        r1.reloadSettings(settingsWithBushDensity(30))
        r1.generateBushes(sampleHills, extendedWidth, extendedOffset, height, seed)

        val r2 = BushRenderer()
        r2.reloadSettings(settingsWithBushDensity(30))
        r2.generateBushes(sampleHills, extendedWidth, extendedOffset, height, seed)

        assertEquals(bushCount(r1), bushCount(r2))
    }

    // ===================== reloadSettings returns change flag =====================

    @Test
    fun `TreeRenderer reloadSettings returns true when density changes`() {
        val renderer = TreeRenderer()
        renderer.reloadSettings(settingsWithTreeDensity(50))

        val changed = renderer.reloadSettings(settingsWithTreeDensity(80))
        assertTrue("should report change", changed)
    }

    @Test
    fun `TreeRenderer reloadSettings returns false when nothing changes`() {
        val renderer = TreeRenderer()
        renderer.reloadSettings(settingsWithTreeDensity(50))

        val changed = renderer.reloadSettings(settingsWithTreeDensity(50))
        assertEquals("should not report change", false, changed)
    }

    @Test
    fun `BushRenderer reloadSettings returns true when density changes`() {
        val renderer = BushRenderer()
        renderer.reloadSettings(settingsWithBushDensity(30))

        val changed = renderer.reloadSettings(settingsWithBushDensity(60))
        assertTrue("should report change", changed)
    }

    @Test
    fun `BushRenderer reloadSettings returns false when nothing changes`() {
        val renderer = BushRenderer()
        renderer.reloadSettings(settingsWithBushDensity(30))

        val changed = renderer.reloadSettings(settingsWithBushDensity(30))
        assertEquals("should not report change", false, changed)
    }

    // ===================== VegetationUtil.getHillY raw overload =====================

    @Test
    fun `getHillY raw overload matches HillInfo overload`() {
        val points = listOf(1.0f, 0.5f, 1.0f)
        val offset = 0.4f
        val hill = TreeRenderer.HillInfo(points, 0.5f, offset)

        for (xRatio in listOf(0f, 0.25f, 0.5f, 0.75f, 1f)) {
            val fromHill = VegetationUtil.getHillY(xRatio, hill)
            val fromRaw = VegetationUtil.getHillY(xRatio, points, offset)
            assertEquals("mismatch at xRatio=$xRatio", fromHill, fromRaw, 0f)
        }
    }

    @Test
    fun `getHillY raw overload returns heightOffset for single point`() {
        val y = VegetationUtil.getHillY(0.5f, listOf(0.8f), 0.5f)
        assertEquals(0.5f, y, 0.001f)
    }

    @Test
    fun `getHillY raw overload returns heightOffset for empty points`() {
        val y = VegetationUtil.getHillY(0.5f, emptyList(), 0.3f)
        assertEquals(0.3f, y, 0.001f)
    }

    // ===================== Helpers =====================

    private fun treeCount(renderer: TreeRenderer): Int =
        renderer.treesByDepth.values.sumOf { it.size }

    private fun allTrees(renderer: TreeRenderer): List<TreeRenderer.TreeData> =
        renderer.treesByDepth.values.flatten()

    private fun bushCount(renderer: BushRenderer): Int =
        renderer.bushesByDepth.values.sumOf { it.size }

    private fun settingsWithTreeDensity(density: Int): SimulationSettings {
        return SimulationSettings(snapshot = mapOf("pref_tree_density" to density))
    }

    private fun settingsWithBushDensity(density: Int): SimulationSettings {
        return SimulationSettings(snapshot = mapOf(
            "pref_bushes_enabled" to true,
            "pref_bush_density" to density
        ))
    }
}
