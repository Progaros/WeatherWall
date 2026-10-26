package info.olli.weatherlwp.rendering.events

import info.olli.weatherlwp.simulation.Astronomy
import kotlin.math.abs
import kotlin.math.pow

/**
 * Annual meteor shower activity, keyed to the Sun's longitude rather than calendar dates so it
 * follows the Earth's orbit (and thus the real shower) indefinitely.
 */
object MeteorShowers {
    // IMO working list: Perseids active λ☉ 115°–152° (Jul 17 – Aug 24), maximum λ☉ 140.0°, ZHR 100.
    private const val PERSEID_START = 115.0
    private const val PERSEID_END = 152.0
    private const val PERSEID_MAX = 140.0
    // Profile slopes (log10 ZHR per degree): slow rise, faster decline after the maximum.
    private const val PERSEID_RISE = 0.20
    private const val PERSEID_FALL = 0.40

    /** Perseid rate relative to the peak (0..1) at [epochMs]. */
    fun perseidActivity(epochMs: Long): Float = perseidActivityAt(Astronomy.solarLongitude(epochMs))

    internal fun perseidActivityAt(solarLongitude: Double): Float {
        if (solarLongitude < PERSEID_START || solarLongitude > PERSEID_END) return 0f
        val delta = solarLongitude - PERSEID_MAX
        val slope = if (delta < 0) PERSEID_RISE else PERSEID_FALL
        return 10.0.pow(-slope * abs(delta)).toFloat()
    }
}
