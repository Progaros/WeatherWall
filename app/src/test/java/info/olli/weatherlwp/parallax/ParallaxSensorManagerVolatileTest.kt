package info.olli.weatherlwp.parallax

import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Modifier

/**
 * Regression test for ParallaxSensorManager listener field visibility.
 *
 * Covers:
 * - listener and shakeListener fields are @Volatile (cross-thread visibility)
 * - Bug: sensor thread could invoke callbacks on destroyed scene if not volatile
 */
class ParallaxSensorManagerVolatileTest {

    @Test
    fun `listener field is volatile`() {
        val field = ParallaxSensorManager::class.java.getDeclaredField("listener")
        assertTrue(
            "listener field must be volatile for cross-thread visibility",
            Modifier.isVolatile(field.modifiers)
        )
    }

    @Test
    fun `shakeListener field is volatile`() {
        val field = ParallaxSensorManager::class.java.getDeclaredField("shakeListener")
        assertTrue(
            "shakeListener field must be volatile for cross-thread visibility",
            Modifier.isVolatile(field.modifiers)
        )
    }
}
