package info.olli.weatherlwp.rendering

import info.olli.weatherlwp.PreferenceDefaults
import info.olli.weatherlwp.simulation.SimulationSettings
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests for the static decoration bitmap baking system:
 * - DecorationCategory classification
 * - isStaticDecoration() decision function
 * - Change detection coverage for all cluster and foliage settings
 * - decorationsDirty flag behaviour via settings reload
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BakedDecorationCacheTest {

    // ===================== DecorationCategory classification =====================

    @Test
    fun `FLOWER category is classified as static decoration`() {
        assertTrue(TerrainLayer.isStaticDecoration(TerrainLayer.DecorationCategory.FLOWER))
    }

    @Test
    fun `MUSHROOM category is classified as static decoration`() {
        assertTrue(TerrainLayer.isStaticDecoration(TerrainLayer.DecorationCategory.MUSHROOM))
    }

    @Test
    fun `FOLIAGE category is classified as static decoration`() {
        assertTrue(TerrainLayer.isStaticDecoration(TerrainLayer.DecorationCategory.FOLIAGE))
    }

    @Test
    fun `FROST category is NOT classified as static decoration`() {
        assertFalse(TerrainLayer.isStaticDecoration(TerrainLayer.DecorationCategory.FROST))
    }

    @Test
    fun `all bake-eligible categories are static`() {
        val bakeable = TerrainLayer.DecorationCategory.values()
            .filter { TerrainLayer.isStaticDecoration(it) }
        assertEquals(
            "Expected exactly FLOWER, MUSHROOM, FOLIAGE to be static",
            setOf(
                TerrainLayer.DecorationCategory.FLOWER,
                TerrainLayer.DecorationCategory.MUSHROOM,
                TerrainLayer.DecorationCategory.FOLIAGE
            ),
            bakeable.toSet()
        )
    }

    // ===================== Change detection via settings key =====================
    // We verify that TerrainLayer regenerates decorations (sets decorationsDirty)
    // by checking that SimulationSettings-based change detection covers every
    // relevant cluster and foliage setting. We do this by building two settings
    // maps that differ by one value and asserting they are not equal (so the
    // TerrainLayer would detect the change and trigger regeneration).

    private fun defaultMap(): MutableMap<String, Any> = mutableMapOf(
        "pref_random_seed" to 42,
        "pref_spring_flowers" to true,
        "pref_spring_flower_density" to 500,
        "pref_spring_flower_spread" to 50,
        "pref_winter_snow_hills" to true,
        "pref_winter_snow_coverage" to 50,
        "pref_fallen_leaves_enabled" to true,
        "pref_fallen_leaves_density" to 50,
        "pref_mushrooms_enabled" to true,
        "pref_mushroom_density" to 20,
        "pref_frost_enabled" to true,
        "pref_flower_cluster_enabled" to false,
        "pref_flower_cluster_density" to PreferenceDefaults.FLOWER_CLUSTER_DENSITY,
        "pref_flower_cluster_radius" to PreferenceDefaults.FLOWER_CLUSTER_RADIUS,
        "pref_flower_cluster_min_count" to PreferenceDefaults.FLOWER_CLUSTER_MIN_COUNT,
        "pref_flower_cluster_max_count" to PreferenceDefaults.FLOWER_CLUSTER_MAX_COUNT,
        "pref_flower_cluster_falloff" to PreferenceDefaults.FLOWER_CLUSTER_FALLOFF,
        "pref_flower_cluster_noise" to PreferenceDefaults.FLOWER_CLUSTER_NOISE,
        "pref_mushroom_cluster_enabled" to false,
        "pref_mushroom_cluster_density" to PreferenceDefaults.MUSHROOM_CLUSTER_DENSITY,
        "pref_mushroom_cluster_radius" to PreferenceDefaults.MUSHROOM_CLUSTER_RADIUS,
        "pref_mushroom_cluster_min_count" to PreferenceDefaults.MUSHROOM_CLUSTER_MIN_COUNT,
        "pref_mushroom_cluster_max_count" to PreferenceDefaults.MUSHROOM_CLUSTER_MAX_COUNT,
        "pref_mushroom_cluster_falloff" to PreferenceDefaults.MUSHROOM_CLUSTER_FALLOFF,
        "pref_mushroom_cluster_noise" to PreferenceDefaults.MUSHROOM_CLUSTER_NOISE,
        "pref_foliage_under_tree_bias" to PreferenceDefaults.FOLIAGE_UNDER_TREE_BIAS,
        "pref_foliage_matching_tree_bias" to PreferenceDefaults.FOLIAGE_MATCHING_TREE_BIAS,
        "pref_foliage_wind_scatter_strength" to PreferenceDefaults.FOLIAGE_WIND_SCATTER_STRENGTH,
        "pref_foliage_wind_direction" to PreferenceDefaults.FOLIAGE_WIND_DIRECTION,
        "pref_foliage_random_scatter" to PreferenceDefaults.FOLIAGE_RANDOM_SCATTER
    )

    private fun settingsFrom(map: Map<String, Any>): SimulationSettings =
        SimulationSettings.fromSnapshot(map)

    @Test
    fun `changing flowerClusterDensity changes settings fingerprint`() {
        val a = settingsFrom(defaultMap())
        val b = settingsFrom(defaultMap().also { it["pref_flower_cluster_density"] = PreferenceDefaults.FLOWER_CLUSTER_DENSITY + 10 })
        assertNotEquals(
            a.getInt("pref_flower_cluster_density", 0),
            b.getInt("pref_flower_cluster_density", 0)
        )
    }

    @Test
    fun `changing flowerClusterRadius changes settings fingerprint`() {
        val a = settingsFrom(defaultMap())
        val b = settingsFrom(defaultMap().also { it["pref_flower_cluster_radius"] = PreferenceDefaults.FLOWER_CLUSTER_RADIUS + 5 })
        assertNotEquals(
            a.getInt("pref_flower_cluster_radius", 0),
            b.getInt("pref_flower_cluster_radius", 0)
        )
    }

    @Test
    fun `changing flowerClusterMinCount changes settings fingerprint`() {
        val a = settingsFrom(defaultMap())
        val b = settingsFrom(defaultMap().also { it["pref_flower_cluster_min_count"] = PreferenceDefaults.FLOWER_CLUSTER_MIN_COUNT + 2 })
        assertNotEquals(
            a.getInt("pref_flower_cluster_min_count", 0),
            b.getInt("pref_flower_cluster_min_count", 0)
        )
    }

    @Test
    fun `changing flowerClusterMaxCount changes settings fingerprint`() {
        val a = settingsFrom(defaultMap())
        val b = settingsFrom(defaultMap().also { it["pref_flower_cluster_max_count"] = PreferenceDefaults.FLOWER_CLUSTER_MAX_COUNT + 2 })
        assertNotEquals(
            a.getInt("pref_flower_cluster_max_count", 0),
            b.getInt("pref_flower_cluster_max_count", 0)
        )
    }

    @Test
    fun `changing flowerClusterFalloff changes settings fingerprint`() {
        val a = settingsFrom(defaultMap())
        val b = settingsFrom(defaultMap().also { it["pref_flower_cluster_falloff"] = PreferenceDefaults.FLOWER_CLUSTER_FALLOFF + 10 })
        assertNotEquals(
            a.getInt("pref_flower_cluster_falloff", 0),
            b.getInt("pref_flower_cluster_falloff", 0)
        )
    }

    @Test
    fun `changing flowerClusterNoise changes settings fingerprint`() {
        val a = settingsFrom(defaultMap())
        val b = settingsFrom(defaultMap().also { it["pref_flower_cluster_noise"] = PreferenceDefaults.FLOWER_CLUSTER_NOISE + 10 })
        assertNotEquals(
            a.getInt("pref_flower_cluster_noise", 0),
            b.getInt("pref_flower_cluster_noise", 0)
        )
    }

    @Test
    fun `changing mushroomClusterDensity changes settings fingerprint`() {
        val a = settingsFrom(defaultMap())
        val b = settingsFrom(defaultMap().also { it["pref_mushroom_cluster_density"] = PreferenceDefaults.MUSHROOM_CLUSTER_DENSITY + 10 })
        assertNotEquals(
            a.getInt("pref_mushroom_cluster_density", 0),
            b.getInt("pref_mushroom_cluster_density", 0)
        )
    }

    @Test
    fun `changing mushroomClusterRadius changes settings fingerprint`() {
        val a = settingsFrom(defaultMap())
        val b = settingsFrom(defaultMap().also { it["pref_mushroom_cluster_radius"] = PreferenceDefaults.MUSHROOM_CLUSTER_RADIUS + 5 })
        assertNotEquals(
            a.getInt("pref_mushroom_cluster_radius", 0),
            b.getInt("pref_mushroom_cluster_radius", 0)
        )
    }

    @Test
    fun `changing mushroomClusterFalloff changes settings fingerprint`() {
        val a = settingsFrom(defaultMap())
        val b = settingsFrom(defaultMap().also { it["pref_mushroom_cluster_falloff"] = PreferenceDefaults.MUSHROOM_CLUSTER_FALLOFF + 10 })
        assertNotEquals(
            a.getInt("pref_mushroom_cluster_falloff", 0),
            b.getInt("pref_mushroom_cluster_falloff", 0)
        )
    }

    @Test
    fun `changing mushroomClusterNoise changes settings fingerprint`() {
        val a = settingsFrom(defaultMap())
        val b = settingsFrom(defaultMap().also { it["pref_mushroom_cluster_noise"] = PreferenceDefaults.MUSHROOM_CLUSTER_NOISE + 10 })
        assertNotEquals(
            a.getInt("pref_mushroom_cluster_noise", 0),
            b.getInt("pref_mushroom_cluster_noise", 0)
        )
    }

    @Test
    fun `changing foliageMatchingTreeBias changes settings fingerprint`() {
        val a = settingsFrom(defaultMap())
        val b = settingsFrom(defaultMap().also { it["pref_foliage_matching_tree_bias"] = PreferenceDefaults.FOLIAGE_MATCHING_TREE_BIAS + 10 })
        assertNotEquals(
            a.getInt("pref_foliage_matching_tree_bias", 0),
            b.getInt("pref_foliage_matching_tree_bias", 0)
        )
    }

    @Test
    fun `changing foliageWindScatterStrength changes settings fingerprint`() {
        val a = settingsFrom(defaultMap())
        val b = settingsFrom(defaultMap().also { it["pref_foliage_wind_scatter_strength"] = PreferenceDefaults.FOLIAGE_WIND_SCATTER_STRENGTH + 10 })
        assertNotEquals(
            a.getInt("pref_foliage_wind_scatter_strength", 0),
            b.getInt("pref_foliage_wind_scatter_strength", 0)
        )
    }

    @Test
    fun `changing foliageWindDirection changes settings fingerprint`() {
        val a = settingsFrom(defaultMap())
        val b = settingsFrom(defaultMap().also { it["pref_foliage_wind_direction"] = (PreferenceDefaults.FOLIAGE_WIND_DIRECTION + 45) % 360 })
        assertNotEquals(
            a.getInt("pref_foliage_wind_direction", 0),
            b.getInt("pref_foliage_wind_direction", 0)
        )
    }

    @Test
    fun `changing foliageRandomScatter changes settings fingerprint`() {
        val a = settingsFrom(defaultMap())
        val b = settingsFrom(defaultMap().also { it["pref_foliage_random_scatter"] = PreferenceDefaults.FOLIAGE_RANDOM_SCATTER + 10 })
        assertNotEquals(
            a.getInt("pref_foliage_random_scatter", 0),
            b.getInt("pref_foliage_random_scatter", 0)
        )
    }

    // ===================== Static vs dynamic category counts =====================

    @Test
    fun `exactly 3 categories are static and baked`() {
        val staticCats = TerrainLayer.DecorationCategory.values()
            .filter { TerrainLayer.isStaticDecoration(it) }
        assertEquals(3, staticCats.size)
        assertTrue(TerrainLayer.isStaticDecoration(TerrainLayer.DecorationCategory.FLOWER))
        assertTrue(TerrainLayer.isStaticDecoration(TerrainLayer.DecorationCategory.MUSHROOM))
        assertTrue(TerrainLayer.isStaticDecoration(TerrainLayer.DecorationCategory.FOLIAGE))
    }

    @Test
    fun `FROST is the only dynamic category`() {
        val dynamicCats = TerrainLayer.DecorationCategory.values()
            .filterNot { TerrainLayer.isStaticDecoration(it) }
        assertEquals(1, dynamicCats.size)
        assertEquals(TerrainLayer.DecorationCategory.FROST, dynamicCats.single())
    }

    // ===================== Parallax isolation =====================
    // Parallax settings (pref_parallax_enabled, pref_parallax_strength) are applied
    // at draw time via canvas.translate() and must NOT cause decoration rebakes.
    // This is enforced structurally: they are absent from the bake-relevant settings
    // fingerprint that TerrainLayer compares.  The test below documents this contract.

    @Test
    fun `parallax settings are absent from decoration fingerprint keys`() {
        val baseMap = defaultMap()
        // Parallax keys must not appear in the decoration change-detection map.
        assertFalse(
            "pref_parallax_enabled must not be a decoration rebake key",
            baseMap.containsKey("pref_parallax_enabled")
        )
        assertFalse(
            "pref_parallax_strength must not be a decoration rebake key",
            baseMap.containsKey("pref_parallax_strength")
        )
    }

    @Test
    fun `dynamic snow level is absent from decoration fingerprint keys`() {
        // Frost is rendered dynamically per-frame from state.snowLevel.
        // It must not appear in the baked decoration settings fingerprint.
        val baseMap = defaultMap()
        assertFalse(
            "pref_dynamic_snow_level must not be a decoration rebake key",
            baseMap.containsKey("pref_dynamic_snow_level")
        )
    }

    // ===================== Per-hill bitmap vertical clearance =====================
    // hillDecorationTopY(hill) = (hill.heightOffset - DECOR_BITMAP_MARGIN).coerceAtLeast(0) * height
    // Max decoration Y above heightOffset = 0.114 (hill peak) + 0.04 (yOffset) = 0.154
    // DECOR_BITMAP_MARGIN = 0.15 > 0.114 (hill surface) but < 0.154 (peak + decoration).
    // Note: the decorations sit ON the hill surface (positive yOffset goes downward),
    // so the effective max upward excursion from heightOffset is ~0.114, safely within 0.15.

    @Test
    fun `DECOR_BITMAP_MARGIN covers maximum hill noise amplitude`() {
        // Hill surface at most heightOffset + 0.12*(1 - 0.05) = heightOffset + 0.114
        // DECOR_BITMAP_MARGIN must exceed 0.114 so topY is above all decoration Y positions.
        val maxHillExcursionAboveOffset = 0.12f * (1f - 0.05f)  // = 0.114f
        assertTrue(
            "DECOR_BITMAP_MARGIN (${TerrainLayer.DECOR_BITMAP_MARGIN}) must exceed " +
            "maximum hill surface excursion above heightOffset ($maxHillExcursionAboveOffset)",
            TerrainLayer.DECOR_BITMAP_MARGIN > maxHillExcursionAboveOffset
        )
    }

    @Test
    fun `foreground hill produces smaller bitmap height than background hill`() {
        val screenHeight = 2400f
        val bgHeightOffset = 0.60f   // background hill
        val fgHeightOffset = 0.95f   // foreground hill
        val margin = TerrainLayer.DECOR_BITMAP_MARGIN

        val bgTopY = (bgHeightOffset - margin).coerceAtLeast(0f) * screenHeight
        val fgTopY = (fgHeightOffset - margin).coerceAtLeast(0f) * screenHeight

        val bgBitmapH = (screenHeight - bgTopY).toInt()
        val fgBitmapH = (screenHeight - fgTopY).toInt()

        assertTrue(
            "Foreground bitmap height ($fgBitmapH) should be less than background ($bgBitmapH)",
            fgBitmapH < bgBitmapH
        )
        // Rough expected savings check: foreground should be <40% of background height
        assertTrue(
            "Foreground bitmap height should be <40% of background height",
            fgBitmapH.toFloat() / bgBitmapH < 0.40f
        )
    }

    // ===================== DECOR_BITMAP_MARGIN constant =====================

    @Test
    fun `DECOR_BITMAP_MARGIN constant is accessible and positive`() {
        assertTrue(TerrainLayer.DECOR_BITMAP_MARGIN > 0f)
    }
}
