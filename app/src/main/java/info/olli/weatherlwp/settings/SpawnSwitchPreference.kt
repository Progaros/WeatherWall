package info.olli.weatherlwp.settings

import android.content.Context
import android.util.AttributeSet
import android.widget.ImageButton
import androidx.appcompat.widget.SwitchCompat
import androidx.preference.PreferenceViewHolder
import androidx.preference.SwitchPreferenceCompat
import info.olli.weatherlwp.R

/**
 * Custom preference that adds an inline "Spawn" button next to the switch toggle.
 * Used for event toggles to allow manually spawning specific events.
 */
class SpawnSwitchPreference @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = androidx.preference.R.attr.switchPreferenceCompatStyle,
    defStyleRes: Int = 0
) : SwitchPreferenceCompat(context, attrs, defStyleAttr, defStyleRes) {

    /** Callback for when the spawn button is clicked */
    var onSpawnClickListener: (() -> Unit)? = null

    init {
        // Use our custom widget layout with spawn button + switch
        widgetLayoutResource = R.layout.pref_widget_spawn_switch
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)

        val spawnButton = holder.findViewById(R.id.spawn_button) as? ImageButton
        spawnButton?.setOnClickListener {
            // Trigger the callback when the dice button is clicked
            // Manual spawn works regardless of toggle state
            onSpawnClickListener?.invoke()
        }

        // Spawn button is always enabled - manual spawn works regardless of auto-spawn toggle
        spawnButton?.isEnabled = true
        spawnButton?.alpha = 1.0f
        
        // Ensure switch is properly bound and clickable
        val switchWidget = holder.findViewById(android.R.id.switch_widget) as? SwitchCompat
        switchWidget?.apply {
            // Remove listener BEFORE setting checked state to avoid triggering
            // notifyItemChanged during RecyclerView layout (causes IllegalStateException)
            setOnCheckedChangeListener(null)
            
            // Sync with preference state
            isChecked = this@SpawnSwitchPreference.isChecked
            
            // Now add the listener back
            setOnCheckedChangeListener { _, isChecked ->
                if (callChangeListener(isChecked)) {
                    this@SpawnSwitchPreference.isChecked = isChecked
                }
            }
        }
    }
}
