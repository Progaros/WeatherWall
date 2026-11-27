package info.olli.weatherlwp.simulation

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager
import java.io.Closeable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.awaitClose

/**
 * Centralized settings repository.
 *
 * Wraps SharedPreferences and exposes immutable settings snapshots through StateFlow.
 */
class SimulationSettingsRepository(context: Context) : Closeable {

    private val prefs = PreferenceManager.getDefaultSharedPreferences(context.applicationContext)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _settingsFlow = MutableStateFlow(SimulationSettings.fromSnapshot(prefs.all))
    val settingsFlow: StateFlow<SimulationSettings> = _settingsFlow.asStateFlow()

    private val observerJob = scope.launch {
        callbackFlow {
            val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
                trySend(SimulationSettings.fromSnapshot(prefs.all))
            }

            prefs.registerOnSharedPreferenceChangeListener(listener)
            trySend(SimulationSettings.fromSnapshot(prefs.all))

            awaitClose {
                prefs.unregisterOnSharedPreferenceChangeListener(listener)
            }
        }
            .distinctUntilChanged()
            .collect { settings ->
                _settingsFlow.value = settings
            }
    }

    override fun close() {
        observerJob.cancel()
        scope.cancel()
    }
}
