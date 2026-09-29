package info.olli.weatherlwp.settings

import androidx.preference.PreferenceManager
import android.content.Context
import info.olli.weatherlwp.PreferenceDefaults
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsDefaultsTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit()
    }

    @Test
    fun `defaults from every preference screen are seeded`() {
        SettingsResetManager.ensureDefaults(context)
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)

        assertEquals(PreferenceDefaults.RANDOM_SEED, prefs.getInt("pref_random_seed", -1))
        assertEquals(PreferenceDefaults.TREE_SIZE, prefs.getInt("pref_tree_size", -1))
        assertEquals(PreferenceDefaults.WINTER_SNOW_COVERAGE, prefs.getInt("pref_winter_snow_coverage", -1))
        assertEquals(PreferenceDefaults.CELESTIAL_ARC_HEIGHT, prefs.getInt("pref_celestial_arc_height", -1))
        assertEquals(PreferenceDefaults.SHAKE_SENSITIVITY, prefs.getInt("pref_shake_sensitivity", -1))
        assertTrue("custom SpawnSwitchPreference default", prefs.getBoolean("pref_event_airplane", false))
    }

    @Test
    fun `existing values are never overwritten`() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        prefs.edit().putInt("pref_random_seed", 7).putInt("pref_tree_size", 150).commit()

        SettingsResetManager.ensureDefaults(context)

        assertEquals(7, prefs.getInt("pref_random_seed", -1))
        assertEquals(150, prefs.getInt("pref_tree_size", -1))
    }

    @Test
    fun `reset restores defaults`() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        prefs.edit().putInt("pref_random_seed", 7).commit()

        SettingsResetManager.resetAllToDefaults(context)

        assertEquals(PreferenceDefaults.RANDOM_SEED, prefs.getInt("pref_random_seed", -1))
    }
}
