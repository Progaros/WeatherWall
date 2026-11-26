package info.olli.weatherlwp.rendering.events

import info.olli.weatherlwp.simulation.Astronomy
import info.olli.weatherlwp.simulation.CloudDensity
import info.olli.weatherlwp.simulation.SimulationState
import info.olli.weatherlwp.weather.WeatherType
import kotlin.math.abs
import kotlin.random.Random

/**
 * Deterministic time-bucket event spawner and cross-scene synchronizer.
 *
 * Evaluates fixed [BUCKET_MS] time buckets using seeds derived purely from the bucket index.
 * Unlike single-bucket polling, [EventSpawner] supports:
 * - **Startup/resume lookback**: when a scene starts (e.g. opening the settings preview while
 *   the live wallpaper is already running), it evaluates the last [LOOKBACK_BUCKETS] buckets so
 *   any deterministic event still in flight appears immediately at the exact same position.
 * - **Idle catch-up**: when frames are spaced further apart than [BUCKET_MS] (such as idle mode
 *   or background pauses), every missed bucket up to [MAX_CATCHUP_BUCKETS] is evaluated.
 * - **Deduplication**: each spawned instance is keyed by `(id, spawnTimeMs, manual)` so neither
 *   catch-up nor repeated manual snapshots can spawn duplicate events.
 */
class EventSpawner(private val baseSeed: Long = 4000L) {

    companion object {
        const val BUCKET_MS = 500L
        /** 30 seconds of lookback covers nearly all active in-flight events when syncing a new scene. */
        const val LOOKBACK_BUCKETS = 60L
        const val MAX_CATCHUP_BUCKETS = 60L
        const val MAX_ACTIVE_EVENTS = 8
        private const val SHOWER_SEED_OFFSET = 7777L
        private const val UNINITIALIZED_BUCKET = Long.MIN_VALUE
    }

    data class SpawnKey(
        val id: String,
        val spawnTimeMs: Long,
        val manual: Boolean = false
    )

    sealed class SpawnRequest {
        abstract val key: SpawnKey
        abstract val spawnTimeMs: Long

        data class SpecSpawn(
            val spec: EventSpec,
            val seed: Long,
            override val spawnTimeMs: Long,
            val explicitPosition: EventSpawnPosition? = null,
            val manual: Boolean = false
        ) : SpawnRequest() {
            override val key: SpawnKey = SpawnKey(spec.id, spawnTimeMs, manual)
        }

        data class ShowerMeteorSpawn(
            val shower: MeteorShowers.Shower,
            val seed: Long,
            override val spawnTimeMs: Long
        ) : SpawnRequest() {
            override val key: SpawnKey = SpawnKey("shower_${shower.name}", spawnTimeMs, manual = false)
        }
    }

    private var lastEvaluatedBucket = UNINITIALIZED_BUCKET
    private val spawnedKeys = LinkedHashMap<SpawnKey, Long>()  // key -> spawnTimeMs

    private val showerActivity = FloatArray(MeteorShowers.Shower.entries.size)
    private var showerActivityMs = Long.MIN_VALUE / 2

    fun reset() {
        lastEvaluatedBucket = UNINITIALIZED_BUCKET
        spawnedKeys.clear()
        showerActivityMs = Long.MIN_VALUE / 2
    }

    /**
     * Record an already-spawned event (for example, a manual trigger) so automatic
     * catch-up does not collide with it.
     */
    fun recordSpawned(key: SpawnKey): Boolean {
        if (spawnedKeys.containsKey(key)) return false
        spawnedKeys[key] = key.spawnTimeMs
        return true
    }

    /** Remove old dedup keys once they are well past any possible lookback window. */
    fun pruneOldKeys(nowMs: Long, retentionMs: Long = 120_000L) {
        if (spawnedKeys.isEmpty()) return
        val cutoff = nowMs - retentionMs
        val it = spawnedKeys.entries.iterator()
        while (it.hasNext()) {
            if (it.next().value < cutoff) it.remove()
        }
    }

    /**
     * Evaluate any unvisited deterministic time buckets up to [state.absoluteTimeMs] and
     * return the resulting [SpawnRequest]s in chronological order.
     *
     * Callers are responsible for discarding lookback requests whose event would already
     * have expired at [state.absoluteTimeMs] and enforcing [MAX_ACTIVE_EVENTS] on live events.
     */
    fun collectAutomaticSpawns(
        state: SimulationState,
        season: String,
        spawnChance: Float,
        enabledEvents: Set<String>,
        meteorShowersEnabled: Boolean
    ): List<SpawnRequest> {
        val nowMs = state.absoluteTimeMs
        val currentBucket = nowMs / BUCKET_MS
        if (currentBucket == lastEvaluatedBucket) return emptyList()

        val startBucket = when {
            lastEvaluatedBucket == UNINITIALIZED_BUCKET -> (currentBucket - LOOKBACK_BUCKETS).coerceAtLeast(0L)
            currentBucket < lastEvaluatedBucket -> (currentBucket - LOOKBACK_BUCKETS).coerceAtLeast(0L)
            else -> maxOf(lastEvaluatedBucket + 1L, currentBucket - MAX_CATCHUP_BUCKETS + 1L)
        }
        lastEvaluatedBucket = currentBucket

        pruneOldKeys(nowMs)

        val requests = ArrayList<SpawnRequest>()
        val conditions = EventSpawnConditions(
            isNight = state.isNight,
            isDusk = state.isDusk,
            season = season,
            weather = state.weather,
            cloudDensity = state.cloudDensity
        )
        val allowShowers = meteorShowersEnabled && state.isNight && !isPrecipitating(state) &&
            (state.cloudDensity == CloudDensity.LIGHT || state.cloudDensity == CloudDensity.MEDIUM)
        if (allowShowers) {
            refreshShowerActivity(nowMs)
        }

        var bucket = startBucket
        while (bucket <= currentBucket) {
            val spawnSeed = bucket + baseSeed
            val spawnTime = bucket * BUCKET_MS
            val spawnRandom = Random(spawnSeed)

            if (spawnRandom.nextFloat() < spawnChance) {
                val eventRandom = Random(spawnSeed + 12345L)
                val spec = selectEventSpec(conditions, enabledEvents, eventRandom, spawnTime)
                if (spec != null) {
                    val req = SpawnRequest.SpecSpawn(
                        spec = spec,
                        seed = spawnTime + spec.spawnSeedOffset,
                        spawnTimeMs = spawnTime,
                        explicitPosition = null,
                        manual = false
                    )
                    if (recordSpawned(req.key)) {
                        requests.add(req)
                    }
                }
            }

            if (allowShowers) {
                val baseChance = (spawnChance * 1.5f).coerceAtMost(0.05f)
                for (shower in MeteorShowers.Shower.entries) {
                    val activity = showerActivity[shower.ordinal]
                    if (activity <= 0f) continue
                    val showerSeed = spawnSeed + SHOWER_SEED_OFFSET + shower.ordinal * 101L
                    val showerRandom = Random(showerSeed)
                    if (showerRandom.nextFloat() < activity * shower.relativeRate * baseChance) {
                        val req = SpawnRequest.ShowerMeteorSpawn(
                            shower = shower,
                            seed = showerSeed,
                            spawnTimeMs = spawnTime
                        )
                        if (recordSpawned(req.key)) {
                            requests.add(req)
                        }
                    }
                }
            }
            bucket++
        }
        return requests
    }

    private fun selectEventSpec(
        conditions: EventSpawnConditions,
        enabledEvents: Set<String>,
        random: Random,
        spawnTime: Long
    ): EventSpec? {
        val availableEvents = ArrayList<EventSpec>(EventSpec.entries.size)
        for (spec in EventSpec.entries) {
            if (spec.id in enabledEvents && spec.canSpawn(conditions, random)) {
                availableEvents.add(spec)
            }
        }
        if (availableEvents.isEmpty()) return null
        val selectionRandom = Random(spawnTime + 99999L)
        return availableEvents[selectionRandom.nextInt(availableEvents.size)]
    }

    private fun refreshShowerActivity(nowMs: Long) {
        if (abs(nowMs - showerActivityMs) < 60_000L) return
        val longitude = Astronomy.solarLongitude(nowMs)
        for (shower in MeteorShowers.Shower.entries) {
            showerActivity[shower.ordinal] = shower.activityAt(longitude)
        }
        showerActivityMs = nowMs
    }

    private fun isPrecipitating(state: SimulationState): Boolean =
        state.weather == WeatherType.RAIN || state.weather == WeatherType.THUNDERSTORM ||
            state.weather == WeatherType.SNOW || state.weather == WeatherType.HAIL
}
