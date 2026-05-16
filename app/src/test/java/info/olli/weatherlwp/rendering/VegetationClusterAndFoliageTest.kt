package info.olli.weatherlwp.rendering

import info.olli.weatherlwp.PreferenceDefaults
import info.olli.weatherlwp.simulation.SimulationSettings
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests for vegetation clustering (flowers, mushrooms) and foliage accumulation
 * under trees with wind scatter.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VegetationClusterAndFoliageTest {

    // ===================== PreferenceDefaults constants exist =====================

    @Test
    fun `flower cluster defaults are within valid ranges`() {
        assertFalse(PreferenceDefaults.FLOWER_CLUSTER_ENABLED)
        assertTrue(PreferenceDefaults.FLOWER_CLUSTER_DENSITY in 0..100)
        assertTrue(PreferenceDefaults.FLOWER_CLUSTER_RADIUS in 0..100)
        assertTrue(PreferenceDefaults.FLOWER_CLUSTER_MIN_COUNT >= 1)
        assertTrue(PreferenceDefaults.FLOWER_CLUSTER_MAX_COUNT >= PreferenceDefaults.FLOWER_CLUSTER_MIN_COUNT)
    }

    @Test
    fun `mushroom cluster defaults are within valid ranges`() {
        assertFalse(PreferenceDefaults.MUSHROOM_CLUSTER_ENABLED)
        assertTrue(PreferenceDefaults.MUSHROOM_CLUSTER_DENSITY in 0..100)
        assertTrue(PreferenceDefaults.MUSHROOM_CLUSTER_RADIUS in 0..100)
        assertTrue(PreferenceDefaults.MUSHROOM_CLUSTER_MIN_COUNT >= 1)
        assertTrue(PreferenceDefaults.MUSHROOM_CLUSTER_MAX_COUNT >= PreferenceDefaults.MUSHROOM_CLUSTER_MIN_COUNT)
    }

    @Test
    fun `foliage accumulation defaults are within valid ranges`() {
        assertTrue(PreferenceDefaults.FOLIAGE_UNDER_TREE_BIAS in 0..100)
        assertTrue(PreferenceDefaults.FOLIAGE_MATCHING_TREE_BIAS in 0..100)
        assertTrue(PreferenceDefaults.FOLIAGE_WIND_SCATTER_STRENGTH in 0..100)
        assertTrue(PreferenceDefaults.FOLIAGE_WIND_DIRECTION in 0..360)
        assertTrue(PreferenceDefaults.FOLIAGE_RANDOM_SCATTER in 0..100)
    }

    // ===================== DeterministicLayout for cluster scatter =====================

    @Test
    fun `cluster scatter with same seed is deterministic`() {
        val random1 = DeterministicLayout.random(42, section = 610, index = 5)
        val random2 = DeterministicLayout.random(42, section = 610, index = 5)
        // Same seed/section/index produces same sequence
        assertEquals(random1.nextFloat(), random2.nextFloat(), 0f)
    }

    @Test
    fun `different cluster cells produce different positions`() {
        val random1 = DeterministicLayout.random(42, section = 610, index = 0)
        val random2 = DeterministicLayout.random(42, section = 610, index = 1)
        // Different cell indices should produce different values (with very high probability)
        val v1 = random1.nextFloat()
        val v2 = random2.nextFloat()
        assertNotEquals("different cells should produce different values", v1, v2, 0.001f)
    }

    @Test
    fun `mushroom cluster section differs from flower cluster section`() {
        val flower = DeterministicLayout.random(42, section = 610, index = 5)
        val mushroom = DeterministicLayout.random(42, section = 910, index = 5)
        assertNotEquals(flower.nextFloat(), mushroom.nextFloat(), 0.001f)
    }

    // ===================== Tree type metadata available for foliage bias =====================

    @Test
    fun `tree type LEAF and NEEDLE exist`() {
        val leaf = TreeRenderer.TreeType.LEAF
        val needle = TreeRenderer.TreeType.NEEDLE
        assertNotEquals(leaf, needle)
    }

    @Test
    fun `generated trees have correct type based on ratio`() {
        val hills = listOf(
            TreeRenderer.HillInfo(42, 1.0f, 0.6f, 0.7f, 2.0f, 1.0f, 1.0f)
        )
        // All leaf trees
        val allLeaf = TreeRenderer()
        allLeaf.reloadSettings(SimulationSettings(snapshot = mapOf(
            "pref_tree_density" to 100,
            "pref_leaf_tree_ratio" to 100
        )))
        allLeaf.generateTrees(hills, 1080f, 1920f, 42)
        val leafTrees = allLeaf.treesByDepth.values.flatten()
        assertTrue("should have trees", leafTrees.isNotEmpty())
        assertTrue("all should be LEAF", leafTrees.all { it.treeType == TreeRenderer.TreeType.LEAF })

        // All needle trees
        val allNeedle = TreeRenderer()
        allNeedle.reloadSettings(SimulationSettings(snapshot = mapOf(
            "pref_tree_density" to 100,
            "pref_leaf_tree_ratio" to 0
        )))
        allNeedle.generateTrees(hills, 1080f, 1920f, 42)
        val needleTrees = allNeedle.treesByDepth.values.flatten()
        assertTrue("should have trees", needleTrees.isNotEmpty())
        assertTrue("all should be NEEDLE", needleTrees.all { it.treeType == TreeRenderer.TreeType.NEEDLE })
    }

    // ===================== SimulationSettings reads new cluster/foliage keys =====================

    @Test
    fun `SimulationSettings reads flower cluster enabled`() {
        val settings = SimulationSettings(snapshot = mapOf(
            "pref_flower_cluster_enabled" to true
        ))
        assertTrue(settings.getBoolean("pref_flower_cluster_enabled", false))
    }

    @Test
    fun `SimulationSettings reads mushroom cluster density`() {
        val settings = SimulationSettings(snapshot = mapOf(
            "pref_mushroom_cluster_density" to 75
        ))
        assertEquals(75, settings.getInt("pref_mushroom_cluster_density", 30))
    }

    @Test
    fun `SimulationSettings reads foliage wind direction`() {
        val settings = SimulationSettings(snapshot = mapOf(
            "pref_foliage_wind_direction" to 180
        ))
        assertEquals(180, settings.getInt("pref_foliage_wind_direction", 0))
    }

    @Test
    fun `SimulationSettings uses defaults when keys are absent`() {
        val settings = SimulationSettings(snapshot = emptyMap<String, Any?>())
        assertFalse(settings.getBoolean("pref_flower_cluster_enabled", PreferenceDefaults.FLOWER_CLUSTER_ENABLED))
        assertEquals(PreferenceDefaults.FOLIAGE_UNDER_TREE_BIAS,
            settings.getInt("pref_foliage_under_tree_bias", PreferenceDefaults.FOLIAGE_UNDER_TREE_BIAS))
    }

    // ===================== VegetationUtil noise for hill Y =====================

    @Test
    fun `getHillY returns valid Y ratio for foliage placement`() {
        val hill = TreeRenderer.HillInfo(42, 1.0f, 0.6f, 0.7f, 2.0f, 1.0f, 1.0f)
        val y = VegetationUtil.getHillY(0f, hill)
        assertTrue("Y should be valid screen ratio", y in 0f..1.5f)
    }

    @Test
    fun `getHillY is continuous at adjacent positions`() {
        val hill = TreeRenderer.HillInfo(42, 1.0f, 0.6f, 0.7f, 2.0f, 1.0f, 1.0f)
        val y1 = VegetationUtil.getHillY(100f, hill)
        val y2 = VegetationUtil.getHillY(100.1f, hill)
        // Adjacent positions should have similar Y values (continuity)
        assertTrue("adjacent positions should be close", kotlin.math.abs(y1 - y2) < 0.05f)
    }

    // ===================== Parallax optimization: depth factors match hill =====================

    @Test
    fun `trees on same hill share identical depth factor`() {
        val hills = listOf(
            TreeRenderer.HillInfo(42, 1.0f, 0.65f, 0.7f, 2.0f, 1.0f, 1.0f)
        )
        val renderer = TreeRenderer()
        renderer.reloadSettings(SimulationSettings(snapshot = mapOf("pref_tree_density" to 100)))
        renderer.generateTrees(hills, 1080f, 1920f, 42)

        val treesForHill = renderer.treesByDepth[0.65f] ?: emptyList()
        assertTrue("should have trees on this hill", treesForHill.isNotEmpty())
        assertTrue("all trees should share hill depth factor",
            treesForHill.all { it.depthFactor == 0.65f })
    }

    @Test
    fun `bushes on same hill share identical depth factor`() {
        val hills = listOf(
            TreeRenderer.HillInfo(42, 1.0f, 0.65f, 0.7f, 2.0f, 1.0f, 1.0f)
        )
        val renderer = BushRenderer()
        renderer.reloadSettings(SimulationSettings(snapshot = mapOf(
            "pref_bushes_enabled" to true,
            "pref_bush_density" to 50
        )))
        renderer.generateBushes(hills, 1080f, 1920f, 42)

        val bushesForHill = renderer.bushesByDepth[0.65f] ?: emptyList()
        assertTrue("should have bushes on this hill", bushesForHill.isNotEmpty())
        assertTrue("all bushes should share hill depth factor",
            bushesForHill.all { it.depthFactor == 0.65f })
    }
}
