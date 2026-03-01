package info.olli.weatherlwp.settings

import android.content.Context
import android.content.res.TypedArray
import android.util.AttributeSet
import androidx.appcompat.widget.SwitchCompat
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder
import info.olli.weatherlwp.R

/**
 * A preference that combines navigation (text click opens sub-screen fragment)
 * with a quick toggle switch on the right side.
 *
 * - Clicking the title/summary area navigates to the detail fragment (via app:fragment attribute)
 * - Clicking the switch toggles the feature on/off (persisted as a boolean preference)
 *
 * Usage in XML:
 * ```xml
 * <info.olli.weatherlwp.settings.ToggleNavPreference
 *     android:key="pref_clouds_enabled"
 *     android:title="Clouds"
 *     android:summary="Fluffiness, shape, opacity, speed, render mode"
 *     android:defaultValue="true"
 *     app:fragment="info.olli.weatherlwp.settings.EffectsCloudsFragment" />
 * ```
 */
class ToggleNavPreference @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = androidx.preference.R.attr.preferenceStyle,
    defStyleRes: Int = 0
) : Preference(context, attrs, defStyleAttr, defStyleRes) {

    private var checked = true

    init {
        widgetLayoutResource = R.layout.pref_widget_toggle
    }

    override fun onGetDefaultValue(a: TypedArray, index: Int): Any? {
        return a.getBoolean(index, true)
    }

    override fun onSetInitialValue(defaultValue: Any?) {
        checked = getPersistedBoolean((defaultValue as? Boolean) ?: true)
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)

        val switchView = holder.findViewById(R.id.toggle_switch) as? SwitchCompat
        switchView?.apply {
            // Remove any existing listener to avoid double-fire during recycling
            setOnCheckedChangeListener(null)
            isChecked = this@ToggleNavPreference.checked

            // Make switch independently clickable (consumes touch events, no bubble to parent)
            isClickable = true
            isFocusable = false

            setOnCheckedChangeListener { _, isChecked ->
                if (callChangeListener(isChecked)) {
                    this@ToggleNavPreference.checked = isChecked
                    persistBoolean(isChecked)
                }
            }
        }
    }

    override fun onClick() {
        // Text area click: do NOT toggle the switch.
        // Navigation is handled by framework's onPreferenceTreeClick via app:fragment attribute.
    }

    /** Programmatically set checked state. */
    fun setChecked(value: Boolean) {
        if (checked != value) {
            checked = value
            persistBoolean(value)
            notifyChanged()
        }
    }

    /** Get current checked state. */
    fun isChecked(): Boolean = checked
}
