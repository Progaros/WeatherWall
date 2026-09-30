package info.olli.weatherlwp.settings

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.util.AttributeSet
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.ImageButton
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SwitchCompat
import androidx.preference.PreferenceViewHolder
import androidx.preference.SwitchPreferenceCompat
import info.olli.weatherlwp.R
import info.olli.weatherlwp.rendering.events.EventSpec

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
        val mappedEventType = getEventTypeFromKey(key)
        val previewView = holder.findViewById(R.id.event_preview) as? EventPreviewView
        previewView?.apply {
            eventType = mappedEventType
            previewMode = EventPreviewView.PreviewMode.SMALL
            setOnClickListener {
                showLargePreviewDialog(mappedEventType)
            }
        }
        
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

    private fun showLargePreviewDialog(eventType: EventPreviewView.EventType) {
        val density = context.resources.displayMetrics.density
        val previewSizePx = (300f * density).toInt()
        val paddingPx = (16f * density).toInt()

        val container = FrameLayout(context).apply {
            setPadding(paddingPx, paddingPx, paddingPx, paddingPx)
        }

        val largePreview = EventPreviewView(context).apply {
            this.eventType = eventType
            previewMode = EventPreviewView.PreviewMode.LARGE
            layoutParams = FrameLayout.LayoutParams(previewSizePx, previewSizePx, Gravity.CENTER)
        }
        container.addView(largePreview)

        val dialog = AlertDialog.Builder(context)
            .setView(container)
            .setNegativeButton(android.R.string.cancel, null)
            .create()

        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.show()
    }
    
    /**
     * Map preference key to event preview type.
     */
    private fun getEventTypeFromKey(key: String?): EventPreviewView.EventType {
        val spec = EventSpec.fromPrefKey(key) ?: EventSpec.BUTTERFLY
        return EventPreviewView.EventType.fromSpec(spec)
    }
}
