package info.olli.weatherlwp.settings

import android.content.Context
import android.util.AttributeSet
import android.widget.ImageButton
import androidx.appcompat.widget.SwitchCompat
import androidx.preference.PreferenceViewHolder
import androidx.preference.SwitchPreferenceCompat
import info.olli.weatherlwp.R

/**
 * Custom preference that adds an inline "Spawn" button and animated preview next to the switch toggle.
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
        // Use our custom widget layout with preview + spawn button + switch
        widgetLayoutResource = R.layout.pref_widget_spawn_switch
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)

        // Configure animated preview based on preference key
        val previewView = holder.findViewById(R.id.event_preview) as? EventPreviewView
        previewView?.eventType = getEventTypeFromKey(key)
        
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
    
    /**
     * Map preference key to event preview type.
     */
    private fun getEventTypeFromKey(key: String?): EventPreviewView.EventType {
        return when (key) {
            // Sky events
            "pref_event_airplane" -> EventPreviewView.EventType.AIRPLANE
            "pref_event_birds" -> EventPreviewView.EventType.BIRDS
            "pref_event_eagle" -> EventPreviewView.EventType.EAGLE
            "pref_event_owl" -> EventPreviewView.EventType.OWL
            "pref_event_balloon" -> EventPreviewView.EventType.BALLOON
            "pref_event_shootingstar" -> EventPreviewView.EventType.SHOOTING_STAR
            "pref_event_alien" -> EventPreviewView.EventType.UFO
            // Ground events
            "pref_event_butterfly" -> EventPreviewView.EventType.BUTTERFLY
            "pref_event_dragonfly" -> EventPreviewView.EventType.DRAGONFLY
            "pref_event_rabbit" -> EventPreviewView.EventType.RABBIT
            "pref_event_fox" -> EventPreviewView.EventType.FOX
            "pref_event_squirrel" -> EventPreviewView.EventType.SQUIRREL
            "pref_event_fireflies" -> EventPreviewView.EventType.FIREFLIES
            "pref_event_snowflake" -> EventPreviewView.EventType.SNOWFLAKE
            "pref_event_bat" -> EventPreviewView.EventType.BAT
            "pref_event_deer" -> EventPreviewView.EventType.DEER
            else -> EventPreviewView.EventType.BUTTERFLY  // Default fallback
        }
    }
}
