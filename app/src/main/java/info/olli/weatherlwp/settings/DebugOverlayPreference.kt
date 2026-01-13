package info.olli.weatherlwp.settings

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.widget.Switch
import androidx.appcompat.app.AlertDialog
import androidx.preference.Preference
import androidx.preference.PreferenceManager
import androidx.preference.PreferenceViewHolder
import info.olli.weatherlwp.R
import info.olli.weatherlwp.rendering.DebugOverlayLayer

/**
 * Custom preference for debug overlay settings.
 * Shows a switch on the right to enable/disable, and clicking the main area
 * opens a multi-select dialog to choose which debug items to display.
 * 
 * Touch behavior:
 * - Tapping LEFT of the divider line: Opens the configure items dialog
 * - Tapping RIGHT of the divider line (switch area): Toggles the switch
 */
class DebugOverlayPreference @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = androidx.preference.R.attr.preferenceStyle,
    defStyleRes: Int = 0
) : Preference(context, attrs, defStyleAttr, defStyleRes) {
    
    private val prefs = PreferenceManager.getDefaultSharedPreferences(context)
    private val mainHandler = Handler(Looper.getMainLooper())
    
    // All available debug items
    private val allItems = DebugOverlayLayer.DebugItem.entries.toTypedArray()
    private val itemLabels = allItems.map { it.label }.toTypedArray()
    private val itemKeys = allItems.map { it.key }
    
    init {
        widgetLayoutResource = R.layout.pref_widget_debug_overlay
        // Set initial summary without triggering RecyclerView issues
        updateSummaryText()
    }
    
    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        
        val switch = holder.findViewById(R.id.debug_overlay_switch) as? Switch
        val divider = holder.findViewById(R.id.debug_overlay_divider) as? View
        switch?.isChecked = prefs.getBoolean(key, false)
        
        // Remove default listener on switch - we handle via click
        switch?.setOnCheckedChangeListener(null)
        
        // Switch direct click toggles the setting
        switch?.setOnClickListener {
            val newState = switch.isChecked
            prefs.edit().putBoolean(key, newState).apply()
            mainHandler.post { 
                updateSummaryText()
                notifyChanged()
            }
            callChangeListener(newState)
        }
        
        // Use touch listener on item view to determine which zone was tapped
        holder.itemView.setOnTouchListener { view, event ->
            if (event.action == MotionEvent.ACTION_UP) {
                val dividerLeft = divider?.let { getDividerLeftPosition(holder.itemView, it) } 
                    ?: (view.width - 100)  // Fallback: rightmost 100px is switch zone
                
                if (event.x >= dividerLeft) {
                    // Tapped on switch area (right of divider) - let the switch handle it
                    // Return false so the event propagates to the switch
                    false
                } else {
                    // Tapped on main area (left of divider) - open configure dialog
                    showItemSelectionDialog()
                    true
                }
            } else {
                false
            }
        }
    }
    
    /**
     * Get the left position of the divider relative to the parent itemView.
     */
    private fun getDividerLeftPosition(itemView: View, divider: View): Int {
        val dividerLocation = IntArray(2)
        val itemLocation = IntArray(2)
        divider.getLocationOnScreen(dividerLocation)
        itemView.getLocationOnScreen(itemLocation)
        return dividerLocation[0] - itemLocation[0]
    }
    
    private fun toggleSwitch(switch: Switch?) {
        switch?.let { sw ->
            val newState = !sw.isChecked
            sw.isChecked = newState
            prefs.edit().putBoolean(key, newState).apply()
            mainHandler.post { 
                updateSummaryText()
                notifyChanged()
            }
            callChangeListener(newState)
        }
    }
    
    private fun showItemSelectionDialog() {
        val currentItems = prefs.getStringSet("pref_debug_overlay_items", null) 
            ?: setOf("fps", "frame_time")  // Default items
        
        val checkedItems = BooleanArray(allItems.size) { index ->
            currentItems.contains(itemKeys[index])
        }
        
        AlertDialog.Builder(context)
            .setTitle("Select Debug Info")
            .setMultiChoiceItems(itemLabels, checkedItems) { _, which, isChecked ->
                checkedItems[which] = isChecked
            }
            .setPositiveButton("OK") { _, _ ->
                val selectedItems = mutableSetOf<String>()
                for (i in checkedItems.indices) {
                    if (checkedItems[i]) {
                        selectedItems.add(itemKeys[i])
                    }
                }
                prefs.edit()
                    .putStringSet("pref_debug_overlay_items", selectedItems)
                    .apply()
                // Post to avoid RecyclerView layout issues
                mainHandler.post {
                    updateSummaryText()
                    notifyChanged()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
    
    private fun updateSummaryText() {
        val enabled = prefs.getBoolean(key, false)
        val items = prefs.getStringSet("pref_debug_overlay_items", null)
            ?: setOf("fps", "frame_time")
        
        summary = if (enabled) {
            val count = items.size
            "Showing $count item${if (count != 1) "s" else ""} - tap to configure"
        } else {
            "Tap switch to enable, tap here to configure items"
        }
    }
}
