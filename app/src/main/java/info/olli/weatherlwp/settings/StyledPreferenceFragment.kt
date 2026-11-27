package info.olli.weatherlwp.settings

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceGroup
import androidx.preference.PreferenceGroupAdapter
import androidx.preference.PreferenceScreen
import androidx.preference.PreferenceViewHolder
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.color.MaterialColors
import info.olli.weatherlwp.R

/**
 * Base preference fragment applying Material 3 Expressive grouped-card styling:
 * - Removes empty left icon gutters when preferences have no icon
 * - Groups sibling preferences inside each category into rounded surface cards
 * - Adds navigation chevrons on sub-screen links and live summaries on ListPreferences
 * - Adds bottom scroll padding so the FAB never overlaps the final card
 */
abstract class StyledPreferenceFragment : PreferenceFragmentCompat() {

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setDivider(null)
        setDividerHeight(0)

        val density = resources.displayMetrics.density
        val sidePad = (12 * density).toInt()
        val topPad = (4 * density).toInt()
        val bottomPad = (96 * density).toInt()

        listView.apply {
            clipToPadding = false
            setPadding(sidePad, topPad, sidePad, bottomPad)
            overScrollMode = View.OVER_SCROLL_ALWAYS
            addItemDecoration(GroupedSpacingDecoration(density))
        }
    }

    override fun setPreferenceScreen(preferenceScreen: PreferenceScreen?) {
        preferenceScreen?.let { styleGroup(it) }
        super.setPreferenceScreen(preferenceScreen)
    }

    @SuppressLint("RestrictedApi")
    override fun onCreateAdapter(preferenceScreen: PreferenceScreen): RecyclerView.Adapter<*> {
        return GroupedCardAdapter(preferenceScreen)
    }

    private fun styleGroup(group: PreferenceGroup) {
        group.isIconSpaceReserved = false
        for (i in 0 until group.preferenceCount) {
            val pref = group.getPreference(i)
            if (pref.icon == null) {
                pref.isIconSpaceReserved = false
            }
            if (pref is PreferenceGroup) {
                styleGroup(pref)
            } else {
                // Show chevron on navigation links without a custom widget
                if (pref.fragment != null && pref.widgetLayoutResource == 0) {
                    pref.widgetLayoutResource = R.layout.pref_widget_chevron
                }
                // Show selected entry in summary for ListPreferences
                if (pref is ListPreference && pref.summaryProvider == null) {
                    pref.summaryProvider = ListPreference.SimpleSummaryProvider.getInstance()
                }
            }
        }
    }

    @SuppressLint("RestrictedApi")
    private class GroupedCardAdapter(
        preferenceGroup: PreferenceGroup
    ) : PreferenceGroupAdapter(preferenceGroup) {

        override fun onBindViewHolder(holder: PreferenceViewHolder, position: Int) {
            super.onBindViewHolder(holder, position)
            val pref = getItem(position) ?: return
            val itemView = holder.itemView
            val context = itemView.context
            val density = context.resources.displayMetrics.density

            if (pref is PreferenceCategory) {
                itemView.background = null
                val hPad = (8 * density).toInt()
                val topPad = (14 * density).toInt()
                val botPad = (6 * density).toInt()
                itemView.setPaddingRelative(hPad, topPad, hPad, botPad)
                return
            }

            val prevIsItem = position > 0 && getItem(position - 1) !is PreferenceCategory
            val nextIsItem = position < itemCount - 1 && getItem(position + 1) !is PreferenceCategory

            val outer = 16f * density
            val inner = 4f * density
            val topR = if (prevIsItem) inner else outer
            val botR = if (nextIsItem) inner else outer

            itemView.background = createCardRipple(context, topR, botR)
            val hPad = (16 * density).toInt()
            val vPad = (4 * density).toInt()
            itemView.setPaddingRelative(hPad, vPad, hPad, vPad)

            // Ensure minimum comfortable height on card items
            val lp = itemView.layoutParams
            if (lp is ViewGroup.MarginLayoutParams) {
                lp.setMargins(0, 0, 0, 0)
                itemView.layoutParams = lp
            }
        }

        private fun createCardRipple(context: Context, topRadius: Float, bottomRadius: Float): RippleDrawable {
            val radii = floatArrayOf(
                topRadius, topRadius,
                topRadius, topRadius,
                bottomRadius, bottomRadius,
                bottomRadius, bottomRadius
            )
            val cardColor = MaterialColors.getColor(
                context,
                com.google.android.material.R.attr.colorSurfaceContainerLow,
                0xFFF0F4F8.toInt()
            )
            val rippleColor = MaterialColors.getColor(
                context,
                com.google.android.material.R.attr.colorControlHighlight,
                0x1F000000
            )
            val content = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadii = radii
                setColor(cardColor)
            }
            val mask = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadii = radii
                setColor(0xFFFFFFFF.toInt())
            }
            return RippleDrawable(ColorStateList.valueOf(rippleColor), content, mask)
        }
    }

    @SuppressLint("RestrictedApi")
    private class GroupedSpacingDecoration(private val density: Float) : RecyclerView.ItemDecoration() {
        private val siblingGapPx = (2 * density).toInt().coerceAtLeast(1)
        private val groupEndGapPx = (6 * density).toInt()

        override fun getItemOffsets(
            outRect: Rect,
            view: View,
            parent: RecyclerView,
            state: RecyclerView.State
        ) {
            val adapter = parent.adapter as? PreferenceGroupAdapter ?: return
            val pos = parent.getChildAdapterPosition(view)
            if (pos == RecyclerView.NO_POSITION) return
            val pref = adapter.getItem(pos) ?: return
            if (pref is PreferenceCategory) return

            val nextIsItem = pos < adapter.itemCount - 1 && adapter.getItem(pos + 1) !is PreferenceCategory
            outRect.bottom = if (nextIsItem) siblingGapPx else groupEndGapPx
        }
    }
}
