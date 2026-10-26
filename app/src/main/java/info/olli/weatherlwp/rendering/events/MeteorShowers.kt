package info.olli.weatherlwp.rendering.events

import info.olli.weatherlwp.simulation.Astronomy
import kotlin.math.abs
import kotlin.math.pow

/**
 * Annual meteor showers, keyed to the Sun's longitude rather than calendar dates so they follow
 * the Earth's orbit (and thus the real showers) indefinitely.
 */
object MeteorShowers {

    /**
     * Activity window, maximum (solar longitude, degrees), profile slopes (log10 rate per degree
     * before/after the maximum) and peak rate relative to the Perseids, from the IMO working list.
     * Radiants are in event field coordinates; speed scales the Perseids' ~59 km/s.
     */
    enum class Shower(
        val start: Double,
        val end: Double,
        val maximum: Double,
        val rise: Double,
        val fall: Double,
        val relativeRate: Float,
        val radiantX: Float,
        val radiantY: Float,
        val speedScale: Float
    ) {
        // Jul 17 – Aug 24, ZHR 100, 59 km/s, Perseus low in the north-east
        PERSEIDS(115.0, 152.0, 140.0, 0.20, 0.40, 1.0f, 0.82f, -0.08f, 1.0f),
        // Dec 4 – Dec 20, ZHR 150, 35 km/s, Gemini high in the east
        GEMINIDS(251.8, 268.1, 262.2, 0.39, 0.81, 1.5f, 0.22f, -0.12f, 0.59f),
        // Dec 28 – Jan 12, ZHR 80, 41 km/s, Boötes low in the north; very sharp maximum
        QUADRANTIDS(276.2, 291.5, 283.15, 0.80, 0.80, 0.8f, 0.68f, 0.02f, 0.69f);

        /** Rate relative to this shower's own peak (0..1) at the given solar longitude. */
        fun activityAt(solarLongitude: Double): Float {
            if (solarLongitude < start || solarLongitude > end) return 0f
            val delta = solarLongitude - maximum
            return 10.0.pow(-(if (delta < 0) rise else fall) * abs(delta)).toFloat()
        }
    }

    /** Perseid rate relative to the peak (0..1) at [epochMs]. */
    fun perseidActivity(epochMs: Long): Float = Shower.PERSEIDS.activityAt(Astronomy.solarLongitude(epochMs))

    internal fun perseidActivityAt(solarLongitude: Double): Float = Shower.PERSEIDS.activityAt(solarLongitude)

    /** Shower with the highest current rate, or null outside every activity window. */
    fun mostActive(epochMs: Long): Shower? {
        val longitude = Astronomy.solarLongitude(epochMs)
        return Shower.entries
            .filter { it.activityAt(longitude) > 0f }
            .maxByOrNull { it.activityAt(longitude) * it.relativeRate }
    }
}
