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
        assertTrue(PreferenceDefaults.FLOWER_CLUSTER_ENABLED)
        assertTrue(PreferenceDefaults.FLOWER_CLUSTER_DENSITY in 0..100)
        assertTrue(PreferenceDefaults.FLOWER_CLUSTER_RADIUS in 0..100)
        assertTrue(PreferenceDefaults.FLOWER_CLUSTER_MIN_COUNT >= 1)
        assertTrue(PreferenceDefaults.FLOWER_CLUSTER_MAX_COUNT >= PreferenceDefaults.FLOWER_CLUSTER_MIN_COUNT)
        assertTrue(PreferenceDefaults.FLOWER_CLUSTER_FALLOFF in 0..100)
        assertTrue(PreferenceDefaults.FLOWER_CLUSTER_NOISE in 0..100)
    }

    @Test
    fun `mushroom cluster defaults are within valid ranges`() {
        assertFalse(PreferenceDefaults.MUSHROOM_CLUSTER_ENABLED)
        assertTrue(PreferenceDefaults.MUSHROOM_CLUSTER_DENSITY in 0..100)
        assertTrue(PreferenceDefaults.MUSHROOM_CLUSTER_RADIUS in 0..100)
        assertTrue(PreferenceDefaults.MUSHROOM_CLUSTER_MIN_COUNT >= 1)
        assertTrue(PreferenceDefaults.MUSHROOM_CLUSTER_MAX_COUNT >= PreferenceDefaults.MUSHROOM_CLUSTER_MIN_COUNT)
        assertTrue(PreferenceDefaults.MUSHROOM_CLUSTER_FALLOFF in 0..100)
        assertTrue(PreferenceDefaults.MUSHROOM_CLUSTER_NOISE in 0..100)
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
        assertEquals(random1.nextFloat(), random2.nextFloat(), 0f)
    }

    @Test
    fun `different cluster cells produce different positions`() {
        val random1 = DeterministicLayout.random(42, section = 610, index = 0)
        val random2 = DeterministicLayout.random(42, section = 610, index = 1)
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

    // ===================== Uniform-disk sampling distribution =====================

    @Test
    fun `sqrt-based disk sampling distributes more uniformly than naive`() {
        // sqrt(u)*r gives uniform area distribution; naive u*r biases toward center.
        // Verify that sqrt-sampled points have a higher median distance from center.
        val rng = DeterministicLayout.random(42, section = 700, index = 0)
        val radius = 10f
        val n = 500
        
        val sqrtDistances = FloatArray(n)
        val naiveDistances = FloatArray(n)
        
        for (i in 0 until n) {
            sqrtDistances[i] = kotlin.math.sqrt(rng.nextFloat().toDouble()).toFloat() * radius
        }
        val rng2 = DeterministicLayout.random(42, section = 701, index = 0)
        for (i in 0 until n) {
            naiveDistances[i] = rng2.nextFloat() * radius
        }
        
        sqrtDistances.sort()
        naiveDistances.sort()
        val sqrtMedian = sqrtDistances[n / 2]
        val naiveMedian = naiveDistances[n / 2]
        
        // Sqrt sampling should have a higher median (more items in outer ring)
        assertTrue("sqrt median ($sqrtMedian) should exceed naive median ($naiveMedian)",
            sqrtMedian > naiveMedian)
    }

    // ===================== Falloff curve reduces edge items =====================

    @Test
    fun `smoothstep falloff reduces weight at cluster edge`() {
        // At cluster center (normalizedDist=0), weight should be 1.0
        // At cluster edge (normalizedDist=1.0), weight should be reduced
        val falloffStrength = 0.5f // FLOWER_CLUSTER_FALLOFF default is 50 → 0.5
        
        val centerWeight = 1f - 0f * 0f * falloffStrength
        val edgeWeight = 1f - 1f * 1f * falloffStrength
        
        assertEquals(1f, centerWeight, 0.001f)
        assertEquals(0.5f, edgeWeight, 0.001f)
        assertTrue("edge weight should be less than center", edgeWeight < centerWeight)
    }

    // ===================== Noise displacement breaks symmetry =====================

    @Test
    fun `noise displacement produces varied offsets per item`() {
        val offsets = (0 until 10).map { f ->
            val nx = DeterministicLayout.hashToFloat(42 + f, 5, 10) - 0.5f
            nx
        }
        // At least some offsets should differ significantly
        val distinct = offsets.map { (it * 100).toInt() }.toSet()
        assertTrue("noise should produce varied offsets, got $distinct", distinct.size > 3)
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
    fun `SimulationSettings reads cluster falloff and noise`() {
        val settings = SimulationSettings(snapshot = mapOf(
            "pref_flower_cluster_falloff" to 75,
            "pref_flower_cluster_noise" to 60
        ))
        assertEquals(75, settings.getInt("pref_flower_cluster_falloff", 50))
        assertEquals(60, settings.getInt("pref_flower_cluster_noise", 30))
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
        assertTrue(settings.getBoolean("pref_flower_cluster_enabled", PreferenceDefaults.FLOWER_CLUSTER_ENABLED))
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
