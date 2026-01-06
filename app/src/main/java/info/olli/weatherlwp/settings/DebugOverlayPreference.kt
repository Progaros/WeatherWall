package info.olli.weatherlwp.settings

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
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
        switch?.isChecked = prefs.getBoolean(key, false)
        
        // Switch click toggles enabled state
        switch?.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(key, isChecked).apply()
            // Post to avoid RecyclerView layout issues
            mainHandler.post { 
                updateSummaryText()
                notifyChanged()
            }
            callChangeListener(isChecked)
        }
        
        // Clicking the preference opens the item selection dialog
        holder.itemView.setOnClickListener {
            showItemSelectionDialog()
        }
        
        // Prevent switch from intercepting all clicks
        switch?.setOnClickListener { view ->
            val sw = view as Switch
            prefs.edit().putBoolean(key, sw.isChecked).apply()
            mainHandler.post { 
                updateSummaryText()
                notifyChanged()
            }
            callChangeListener(sw.isChecked)
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
