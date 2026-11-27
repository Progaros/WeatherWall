package info.olli.weatherlwp.rendering.events

import android.content.SharedPreferences
import androidx.core.content.edit
import kotlin.random.Random

/**
 * Manually triggered events, shared by the settings preview and the live wallpaper.
 *
 * Every press appends `id@triggerMs` to one preference instead of overwriting a single
 * trigger, so presses made while the wallpaper is hidden behind the settings screen are not
 * lost. The event type is fixed at press time and every scene seeds the event from the trigger
 * time, so preview and wallpaper show the same event (path, colours, timing).
 */
object ManualEventSpawns {
    const val PREF_KEY = "manual_event_spawns"
    /** Presses older than this are dropped instead of replayed. */
    const val MAX_AGE_MS = 10 * 60_000L
    private const val MAX_ENTRIES = 32

    data class Entry(
        val eventId: String,
        val triggerMs: Long,
        val spawnPosition: EventSpawnPosition? = null
    )

    fun parse(raw: String?): List<Entry> {
        if (raw.isNullOrBlank()) return emptyList()
        return raw.split(',').mapNotNull { token ->
            val parts = token.split('@')
            if (parts.size < 2 || parts[0].isEmpty()) return@mapNotNull null
            val time = parts[1].toLongOrNull() ?: return@mapNotNull null
            val pos = if (parts.size >= 3 && parts[2].isNotEmpty()) parsePosition(parts[2]) else null
            Entry(parts[0], time, pos)
        }
    }

    fun format(entries: List<Entry>): String = entries.joinToString(",") { entry ->
        val pos = entry.spawnPosition
        if (pos == null) {
            "${entry.eventId}@${entry.triggerMs}"
        } else {
            val depthPart = pos.depthFactor?.let { ":$it" } ?: ""
            "${entry.eventId}@${entry.triggerMs}@${pos.x}:${pos.y}$depthPart"
        }
    }

    private fun parsePosition(raw: String): EventSpawnPosition? {
        val coords = raw.split(':')
        if (coords.size < 2) return null
        val x = coords[0].toFloatOrNull() ?: return null
        val y = coords[1].toFloatOrNull() ?: return null
        val depth = coords.getOrNull(2)?.toFloatOrNull()
        return EventSpawnPosition(x, y, depth)
    }

    /** Append a press; returns the stored entry (trigger times stay unique and increasing). */
    fun enqueue(
        prefs: SharedPreferences,
        eventId: String,
        nowMs: Long = System.currentTimeMillis(),
        spawnPosition: EventSpawnPosition? = null
    ): Entry {
        synchronized(this) {
            val kept = parse(prefs.getString(PREF_KEY, null)).filter { nowMs - it.triggerMs < MAX_AGE_MS }
            val triggerMs = maxOf(nowMs, (kept.maxOfOrNull { it.triggerMs } ?: Long.MIN_VALUE) + 1)
            val entry = Entry(eventId, triggerMs, spawnPosition)
            prefs.edit { putString(PREF_KEY, format((kept + entry).takeLast(MAX_ENTRIES))) }
            return entry
        }
    }

    /** The "spawn random event" button resolves its type once, at press time. */
    fun randomEventId(triggerMs: Long): String {
        val specs = EventSpec.DEBUG_SPAWN_ORDER
        return specs[Random(triggerMs).nextInt(specs.size)].id
    }

    /**
     * Per-scene replay state. Entries newer than the last handled one are started together,
     * shifted so the oldest begins now while the gaps between presses are kept.
     */
    class Replayer(private var lastHandledTriggerMs: Long) {

        fun takeNew(entries: List<Entry>, nowMs: Long): List<Pair<Entry, Long>> {
            val fresh = entries.filter { it.triggerMs > lastHandledTriggerMs && nowMs - it.triggerMs < MAX_AGE_MS }
            if (fresh.isEmpty()) return emptyList()
            lastHandledTriggerMs = fresh.maxOf { it.triggerMs }
            val shift = (nowMs - fresh.minOf { it.triggerMs }).coerceAtLeast(0L)
            return fresh.map { it to it.triggerMs + shift }
        }
    }
}
