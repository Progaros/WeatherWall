package info.olli.weatherlwp

import org.junit.Assert.*
import org.junit.Test

/**
 * Issue #1: Package Conflict - App won't install over existing version.
 * Guards the generated BuildConfig values that decide whether an update installs.
 */
class BuildConfigTest {

    @Test
    fun `versionCode is positive`() {
        assertTrue("VERSION_CODE=${BuildConfig.VERSION_CODE}", BuildConfig.VERSION_CODE > 0)
    }

    @Test
    fun `applicationId is unchanged so updates install over existing versions`() {
        assertEquals("info.olli.weatherlwp", BuildConfig.APPLICATION_ID)
    }

    @Test
    fun `versionName is a single dotted numeric token`() {
        // CI names the release and APK after this value.
        assertTrue(
            "VERSION_NAME='${BuildConfig.VERSION_NAME}'",
            Regex("""\d+(\.\d+)+""").matches(BuildConfig.VERSION_NAME)
        )
    }
}
