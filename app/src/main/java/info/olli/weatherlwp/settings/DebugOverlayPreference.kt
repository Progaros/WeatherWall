package info.olli.weatherlwp.settings

import android.content.Context
import android.content.res.TypedArray
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SwitchCompat
import androidx.preference.Preference
import androidx.preference.PreferenceManager
import androidx.preference.PreferenceViewHolder
import info.olli.weatherlwp.R
import info.olli.weatherlwp.rendering.DebugOverlayLayer

/**
 * Custom preference for debug overlay settings.
 * Uses the standard "text left opens options, toggle right to toggle" pattern:
 * - Clicking the text/title area opens a multi-select dialog to choose debug items
 * - The SwitchCompat on the right independently toggles the overlay on/off
 */
class DebugOverlayPreference @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = androidx.preference.R.attr.preferenceStyle,
    defStyleRes: Int = 0
) : Preference(context, attrs, defStyleAttr, defStyleRes) {
    
    private val prefs = PreferenceManager.getDefaultSharedPreferences(context)
    private val mainHandler = Handler(Looper.getMainLooper())
    
    private val allItems = DebugOverlayLayer.DebugItem.entries.toTypedArray()
    private val itemLabels = allItems.map { it.label }.toTypedArray()
    private val itemKeys = allItems.map { it.key }
    
    private var checked = false
    
    init {
        widgetLayoutResource = R.layout.pref_widget_debug_overlay
        updateSummaryText()
    }

    override fun onGetDefaultValue(a: TypedArray, index: Int): Any? {
        return a.getBoolean(index, false)
    }

    override fun onSetInitialValue(defaultValue: Any?) {
        checked = getPersistedBoolean((defaultValue as? Boolean) ?: false)
    }
    
    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        
        val switchView = holder.findViewById(R.id.debug_overlay_switch) as? SwitchCompat
        switchView?.apply {
            setOnCheckedChangeListener(null)
            isChecked = this@DebugOverlayPreference.checked
            
            // Switch is independently clickable — does not trigger onClick()
            isClickable = true
            isFocusable = false
            
            setOnCheckedChangeListener { _, isChecked ->
                if (callChangeListener(isChecked)) {
                    this@DebugOverlayPreference.checked = isChecked
                    persistBoolean(isChecked)
                    mainHandler.post { updateSummaryText() }
                }
            }
        }
    }
    
    /**
     * Text area click opens the item selection dialog (does NOT toggle the switch).
     */
    override fun onClick() {
        showItemSelectionDialog()
    }
    
    private fun showItemSelectionDialog() {
        val currentItems = prefs.getStringSet("pref_debug_overlay_items", null) 
            ?: setOf("fps", "frame_time")
        
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
                mainHandler.post {
                    updateSummaryText()
                    notifyChanged()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
    
    private fun updateSummaryText() {
        val enabled = checked
        val items = prefs.getStringSet("pref_debug_overlay_items", null)
            ?: setOf("fps", "frame_time")
        
        summary = if (enabled) {
            val count = items.size
            "Showing $count item${if (count != 1) "s" else ""} — tap to configure"
        } else {
            "Disabled — tap to configure items"
        }
    }
}
