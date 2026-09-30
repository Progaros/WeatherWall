package info.olli.weatherlwp.simulation

import android.content.Context
import androidx.preference.PreferenceManager
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.ExecutorService
import kotlin.random.Random

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SimulationControllerTest {
    private val context = RuntimeEnvironment.getApplication() as Context
    private val executor = mock(ExecutorService::class.java)
    private lateinit var controller: SimulationController

    @Before
    fun setUp() {
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit()
        context.getSharedPreferences("weather_history", Context.MODE_PRIVATE).edit().clear().commit()
        controller = SimulationController(context, WindSimulator(Random(42)), executor)
        setTime(1439)
    }

    private fun setTime(minutes: Int) {
        controller.applySettings(SimulationSettings.fromSnapshot(mapOf(
            "pref_debug_enabled" to true,
            "pref_manual_time" to minutes
        )))
    }

    @Test
    fun `snapshot reads do not advance or schedule history`() {
        val initial = controller.getState()
        repeat(10) { assertSame(initial, controller.getState()) }
        verifyNoInteractions(executor)
        controller.advance(0L)
        val advanced = controller.getState()
        repeat(10) { assertSame(advanced, controller.getState()) }
        controller.advance(0L)
        assertSame(advanced, controller.getState())
        verify(executor, times(1)).execute(any(Runnable::class.java))
        controller.destroy()
    }

    @Test
    fun `history persistence runs only in the scheduled worker`() {
        val prefs = context.getSharedPreferences("weather_history", Context.MODE_PRIVATE)
        controller.advance(0L)
        assertFalse(prefs.contains("daily_history_json"))
        val task = ArgumentCaptor.forClass(Runnable::class.java)
        verify(executor).execute(task.capture())
        task.value.run()
        assertTrue(prefs.contains("daily_history_json"))
        controller.advance(59_999L)
        verify(executor, times(1)).execute(any(Runnable::class.java))
        controller.advance(60_000L)
        verify(executor, times(2)).execute(any(Runnable::class.java))
        controller.destroy()
    }

    @Test
    fun `pending history work is coalesced and shutdown accepts no new work`() {
        controller.advance(0L)
        controller.advance(60_000L)
        verify(executor, times(1)).execute(any(Runnable::class.java))
        controller.destroy()
        val stopped = controller.getState()
        controller.advance(120_000L)
        assertSame(stopped, controller.getState())
        verify(executor).shutdown()
        verify(executor, times(1)).execute(any(Runnable::class.java))
    }

    @Test
    fun `midnight smoothing takes the short path on explicit advancement`() {
        controller.advance(0L)
        setTime(1)
        controller.advance(16L)
        val state = controller.getState()
        assertEquals(1f / 60f, state.timeOfDayHours, 0.001f)
        assertTrue(state.smoothedTimeOfDayHours > 23.9f || state.smoothedTimeOfDayHours < 0.1f)
        controller.destroy()
    }
}