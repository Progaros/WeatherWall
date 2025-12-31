package info.olli.weatherlwp.prefs

import android.content.Context
import android.util.AttributeSet
import android.widget.SeekBar
import android.widget.TextView
import androidx.preference.PreferenceViewHolder
import androidx.preference.SeekBarPreference

/**
 * Custom SeekBarPreference that displays time in HH:MM format.
 * Used for the "Simulate Time of Day" setting where the underlying value
 * is minutes since midnight (0-1439) but should display as a clock time.
 */
class TimeSeekBarPreference @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = androidx.preference.R.attr.seekBarPreferenceStyle,
    defStyleRes: Int = 0
) : SeekBarPreference(context, attrs, defStyleAttr, defStyleRes) {

    private var valueTextView: TextView? = null

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        
        // Find the SeekBar value TextView using the androidx.preference resource ID
        valueTextView = holder.findViewById(androidx.preference.R.id.seekbar_value) as? TextView
        
        // Also attach a listener to the SeekBar to update text on drag
        val seekBar = holder.findViewById(androidx.preference.R.id.seekbar) as? SeekBar
        seekBar?.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                updateValueText(progress)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        
        updateValueText(value)
    }

    override fun setValue(value: Int) {
        super.setValue(value)
        updateValueText(value)
    }

    private fun updateValueText(minutes: Int) {
        valueTextView?.text = formatMinutesToTime(minutes)
    }

    companion object {
        /**
         * Format minutes since midnight to HH:MM format.
         */
        fun formatMinutesToTime(minutes: Int): String {
            val h = (minutes / 60).coerceIn(0, 23)
            val m = (minutes % 60).coerceIn(0, 59)
            return String.format("%02d:%02d", h, m)
        }
    }
}
