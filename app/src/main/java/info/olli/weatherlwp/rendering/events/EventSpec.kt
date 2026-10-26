package info.olli.weatherlwp.rendering.events

import info.olli.weatherlwp.simulation.CloudDensity
import info.olli.weatherlwp.weather.WeatherType
import kotlin.random.Random

/** Inputs the automatic spawn conditions read, captured once per spawn attempt. */
class EventSpawnConditions(
    val isNight: Boolean,
    val isDusk: Boolean,
    season: String,
    val weather: WeatherType,
    val cloudDensity: CloudDensity
) {
    val isSpring = season == "spring"
    val isSummer = season == "summer"
    val isAutumn = season == "autumn"
    val isWinter = season == "winter"
    val isWarmSeason = isSpring || isSummer
    val isPrecipitating = weather == WeatherType.RAIN || weather == WeatherType.THUNDERSTORM ||
        weather == WeatherType.SNOW || weather == WeatherType.HAIL
    val isClearEnough = cloudDensity == CloudDensity.LIGHT || cloudDensity == CloudDensity.MEDIUM
}

/**
 * Settings-preview framing.
 * @param smallScale Scale in the inline (small) preview; the large popup derives from it.
 * @param yOffsetFactor Vertical offset of the anchor as a fraction of the preview size.
 * @param startFraction How far into the event's duration the preview starts.
 */
class EventPreviewSpec(
    val smallScale: Float,
    val yOffsetFactor: Float,
    val startFraction: Float
)

private const val SKY_PREVIEW_Y = -0.03f
private const val GROUND_PREVIEW_Y = 0.16f

/**
 * Single registry of all random event types.
 *
 * Entry order is the order in which automatic spawning collects candidates, and
 * [spawnSeedOffset] is added to the spawn time to seed each event; both must stay
 * unchanged or the same time bucket would spawn a different event.
 *
 * [canSpawn] may consume values from the shared spawn [Random]; conditions are evaluated
 * in entry order and only for enabled events, so the sequence of draws is part of the contract.
 */
enum class EventSpec(
    /** Stable id used in enabledEvents, spawn triggers and `pref_event_<id>`. */
    val id: String,
    val displayName: String,
    val spawnSeedOffset: Long,
    private val factory: (random: Random, spawnTimeMs: Long, viewportRatio: Float) -> RandomEvent,
    val canSpawn: (conditions: EventSpawnConditions, random: Random) -> Boolean,
    val preview: EventPreviewSpec
) {
    AIRPLANE("airplane", "Airplane", 0,
        { r, t, _ -> AirplaneEvent(r, t) },
        { c, _ -> !c.isNight },
        EventPreviewSpec(1.15f, SKY_PREVIEW_Y, 0.4f)),
    BIRDS("birds", "Bird Flock", 1,
        { r, t, _ -> BirdFlockEvent(r, t) },
        { c, _ -> !c.isNight },
        EventPreviewSpec(1.20f, SKY_PREVIEW_Y, 0.4f)),
    EAGLE("eagle", "Eagle", 2,
        { r, t, _ -> EagleEvent(r, t) },
        { c, _ -> !c.isNight },
        EventPreviewSpec(1.15f, SKY_PREVIEW_Y, 0.4f)),
    // Spring & summer only (warm weather insects)
    BUTTERFLY("butterfly", "Butterfly", 3,
        { r, t, vr -> ButterflyEvent(r, t, vr) },
        { c, _ -> !c.isNight && c.isWarmSeason },
        EventPreviewSpec(1.25f, 0f, 0.4f)),
    // Summer only (peak insect season)
    DRAGONFLY("dragonfly", "Dragonfly", 11,
        { r, t, vr -> DragonflyEvent(r, t, vr) },
        { c, _ -> !c.isNight && c.isSummer },
        EventPreviewSpec(1.20f, 0f, 0.4f)),
    BALLOON("balloon", "Hot Air Balloon", 4,
        { r, t, _ -> HotAirBalloonEvent(r, t) },
        { c, _ -> !c.isNight && !c.isPrecipitating },
        EventPreviewSpec(1.15f, SKY_PREVIEW_Y, 0.4f)),
    RABBIT("rabbit", "Rabbit", 5,
        { r, t, vr -> RabbitEvent(r, t, vr) },
        { _, _ -> true },
        EventPreviewSpec(1.20f, GROUND_PREVIEW_Y, 0.5f)),
    FOX("fox", "Fox", 6,
        { r, t, vr -> FoxEvent(r, t, vr) },
        { _, _ -> true },
        EventPreviewSpec(1.15f, GROUND_PREVIEW_Y, 0.5f)),
    // Autumn primarily (gathering nuts), but can appear year-round
    SQUIRREL("squirrel", "Squirrel", 12,
        { r, t, vr -> SquirrelEvent(r, t, vr) },
        { c, random -> c.isAutumn || random.nextFloat() < 0.3f },
        EventPreviewSpec(1.20f, GROUND_PREVIEW_Y, 0.5f)),
    SHOOTING_STAR("shootingstar", "Shooting Star", 7,
        { r, t, _ -> ShootingStarEvent(r, t) },
        { c, _ -> c.isNight && c.isClearEnough },
        EventPreviewSpec(1.25f, SKY_PREVIEW_Y, 0.75f)),
    // Summer nights only
    FIREFLIES("fireflies", "Fireflies", 8,
        { r, t, _ -> FireflyEvent(r, t) },
        { c, _ -> c.isNight && !c.isPrecipitating && c.isSummer },
        EventPreviewSpec(1.30f, 0f, 0.35f)),
    OWL("owl", "Owl", 9,
        { r, t, _ -> OwlEvent(r, t) },
        { c, _ -> c.isNight },
        EventPreviewSpec(1.15f, SKY_PREVIEW_Y, 0.4f)),
    // Decorative; winter days only, when not already snowing
    SNOWFLAKE("snowflake", "Snowflake", 13,
        { r, t, _ -> SnowflakeEvent(r, t) },
        { c, _ -> c.isWinter && c.weather != WeatherType.SNOW && !c.isNight },
        EventPreviewSpec(1.35f, 0f, 0.4f)),
    BAT("bat", "Bat Colony", 14,
        { r, t, _ -> BatEvent(r, t) },
        { c, _ -> c.isNight },
        EventPreviewSpec(1.20f, SKY_PREVIEW_Y, 0.4f)),
    // Autumn/winter primarily (more visible in sparse foliage)
    DEER("deer", "Deer", 15,
        { r, t, vr -> DeerEvent(r, t, vr) },
        { c, random -> !c.isPrecipitating && (c.isAutumn || c.isWinter || random.nextFloat() < 0.2f) },
        EventPreviewSpec(1.10f, GROUND_PREVIEW_Y, 0.5f)),
    // Rare: night only, very low chance
    ALIEN("alien", "UFO", 10,
        { r, t, _ -> AlienEvent(r, t) },
        { c, random -> c.isNight && random.nextFloat() < 0.05f },
        EventPreviewSpec(1.15f, SKY_PREVIEW_Y, 0.4f)),
    FISH("fish", "Fish", 16,
        { r, t, _ -> FishJumpEvent(r, t) },
        { c, _ -> !c.isNight && !c.isWinter && !c.isPrecipitating },
        EventPreviewSpec(1.30f, 0.08f, 0.4f)),
    DUCK("duck", "Duck", 17,
        { r, t, vr -> DuckEvent(r, t, vr) },
        { c, _ -> !c.isNight && !c.isPrecipitating },
        EventPreviewSpec(1.25f, GROUND_PREVIEW_Y, 0.5f)),
    // Evening/dusk/warm nights in spring and summer
    FROG("frog", "Frog", 18,
        { r, t, _ -> FrogEvent(r, t) },
        { c, _ -> !c.isWinter && !c.isAutumn && (c.isDusk || c.isNight) },
        EventPreviewSpec(1.25f, GROUND_PREVIEW_Y, 0.5f)),
    // Burst when triggered manually; the annual shower (late July - August) spawns single
    // Perseids by solar longitude in RandomEventLayer instead of through random selection.
    PERSEIDS("perseids", "Perseids", 19,
        { r, t, _ -> MeteorShowerEvent(r, t) },
        { _, _ -> false },
        EventPreviewSpec(1.25f, SKY_PREVIEW_Y, 0.02f)),
    // Real background stars drop and burn out; new ones slowly appear elsewhere
    STAR_FALL("starfall", "Falling Stars", 20,
        { r, t, _ -> StarFallEvent(r, t) },
        { c, random -> c.isNight && c.isClearEnough && random.nextFloat() < 0.25f },
        EventPreviewSpec(0.4f, 0f, 0.02f));

    val prefKey: String get() = "pref_event_$id"

    fun create(random: Random, spawnTimeMs: Long, viewportRatio: Float = 1.0f): RandomEvent =
        factory(random, spawnTimeMs, viewportRatio)

    /** Deterministic automatic spawn: seeded from the spawn time plus [spawnSeedOffset]. */
    fun createForSpawn(spawnTimeMs: Long, viewportRatio: Float): RandomEvent =
        create(Random(spawnTimeMs + spawnSeedOffset), spawnTimeMs, viewportRatio)

    companion object {
        fun fromId(id: String): EventSpec? = entries.firstOrNull { it.id == id }

        fun fromPrefKey(key: String?): EventSpec? = entries.firstOrNull { it.prefKey == key }

        /** Candidate order of the "spawn random event" button; new types are appended. */
        val DEBUG_SPAWN_ORDER: List<EventSpec> = listOf(
            AIRPLANE, BIRDS, EAGLE, BUTTERFLY, RABBIT, FOX, SHOOTING_STAR, FIREFLIES, OWL,
            BALLOON, ALIEN, SQUIRREL, DRAGONFLY, SNOWFLAKE, BAT, DEER, FISH, DUCK, FROG,
            PERSEIDS, STAR_FALL
        )
    }
}
