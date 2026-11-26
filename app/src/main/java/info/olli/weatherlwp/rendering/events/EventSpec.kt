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
    private val factory: (random: Random, context: EventSpawnContext) -> RandomEvent,
    val canSpawn: (conditions: EventSpawnConditions, random: Random) -> Boolean,
    val preview: EventPreviewSpec,
    val spawnSource: EventSpawnSource = EventSpawnSource.DEFAULT
) {
    AIRPLANE("airplane", "Airplane", 0,
        { r, ctx -> AirplaneEvent(r, ctx.spawnTimeMs) },
        { c, _ -> !c.isNight },
        EventPreviewSpec(1.15f, SKY_PREVIEW_Y, 0.4f)),
    BIRDS("birds", "Bird Flock", 1,
        { r, ctx -> BirdFlockEvent(r, ctx.spawnTimeMs) },
        { c, _ -> !c.isNight },
        EventPreviewSpec(1.20f, SKY_PREVIEW_Y, 0.4f)),
    EAGLE("eagle", "Eagle", 2,
        { r, ctx -> EagleEvent(r, ctx.spawnTimeMs) },
        { c, _ -> !c.isNight },
        EventPreviewSpec(1.15f, SKY_PREVIEW_Y, 0.4f)),
    // Spring & summer only (warm weather insects)
    BUTTERFLY("butterfly", "Butterfly", 3,
        { r, ctx -> ButterflyEvent(r, ctx.spawnTimeMs, ctx.viewportRatio) },
        { c, _ -> !c.isNight && c.isWarmSeason },
        EventPreviewSpec(1.25f, 0f, 0.4f)),
    // Summer only (peak insect season)
    DRAGONFLY("dragonfly", "Dragonfly", 11,
        { r, ctx -> DragonflyEvent(r, ctx.spawnTimeMs, ctx.viewportRatio) },
        { c, _ -> !c.isNight && c.isSummer },
        EventPreviewSpec(1.20f, 0f, 0.4f)),
    BALLOON("balloon", "Hot Air Balloon", 4,
        { r, ctx -> HotAirBalloonEvent(r, ctx.spawnTimeMs) },
        { c, _ -> !c.isNight && !c.isPrecipitating },
        EventPreviewSpec(1.15f, SKY_PREVIEW_Y, 0.4f)),
    RABBIT("rabbit", "Rabbit", 5,
        { r, ctx -> RabbitEvent(r, ctx.spawnTimeMs, ctx.viewportRatio) },
        { _, _ -> true },
        EventPreviewSpec(1.20f, GROUND_PREVIEW_Y, 0.5f)),
    FOX("fox", "Fox", 6,
        { r, ctx -> FoxEvent(r, ctx.spawnTimeMs, ctx.viewportRatio) },
        { _, _ -> true },
        EventPreviewSpec(1.15f, GROUND_PREVIEW_Y, 0.5f)),
    // Autumn primarily (gathering nuts), but can appear year-round
    SQUIRREL("squirrel", "Squirrel", 12,
        { r, ctx -> SquirrelEvent(r, ctx.spawnTimeMs, ctx.viewportRatio) },
        { c, random -> c.isAutumn || random.nextFloat() < 0.3f },
        EventPreviewSpec(1.20f, GROUND_PREVIEW_Y, 0.5f)),
    SHOOTING_STAR("shootingstar", "Shooting Star", 7,
        { r, ctx -> ShootingStarEvent(r, ctx.spawnTimeMs) },
        { c, _ -> c.isNight && c.isClearEnough },
        EventPreviewSpec(1.25f, SKY_PREVIEW_Y, 0.75f)),
    // Summer nights only
    FIREFLIES("fireflies", "Fireflies", 8,
        { r, ctx -> FireflyEvent(r, ctx.spawnTimeMs) },
        { c, _ -> c.isNight && !c.isPrecipitating && c.isSummer },
        EventPreviewSpec(1.30f, 0f, 0.35f)),
    OWL("owl", "Owl", 9,
        { r, ctx -> OwlEvent(r, ctx.spawnTimeMs) },
        { c, _ -> c.isNight },
        EventPreviewSpec(1.15f, SKY_PREVIEW_Y, 0.4f)),
    // Decorative; winter days only, when not already snowing
    SNOWFLAKE("snowflake", "Snowflake", 13,
        { r, ctx -> SnowflakeEvent(r, ctx.spawnTimeMs) },
        { c, _ -> c.isWinter && c.weather != WeatherType.SNOW && !c.isNight },
        EventPreviewSpec(1.35f, 0f, 0.4f)),
    BAT("bat", "Bat Colony", 14,
        { r, ctx -> BatEvent(r, ctx.spawnTimeMs) },
        { c, _ -> c.isNight },
        EventPreviewSpec(1.20f, SKY_PREVIEW_Y, 0.4f)),
    // Autumn/winter primarily (more visible in sparse foliage)
    DEER("deer", "Deer", 15,
        { r, ctx -> DeerEvent(r, ctx.spawnTimeMs, ctx.viewportRatio) },
        { c, random -> !c.isPrecipitating && (c.isAutumn || c.isWinter || random.nextFloat() < 0.2f) },
        EventPreviewSpec(1.10f, GROUND_PREVIEW_Y, 0.5f)),
    // Rare: night only, very low chance
    ALIEN("alien", "UFO", 10,
        { r, ctx -> AlienEvent(r, ctx.spawnTimeMs) },
        { c, random -> c.isNight && random.nextFloat() < 0.05f },
        EventPreviewSpec(1.15f, SKY_PREVIEW_Y, 0.4f)),
    FISH("fish", "Fish", 16,
        { r, ctx -> FishJumpEvent(r, ctx.spawnTimeMs) },
        { c, _ -> !c.isNight && !c.isWinter && !c.isPrecipitating },
        EventPreviewSpec(1.30f, 0.08f, 0.4f)),
    DUCK("duck", "Duck", 17,
        { r, ctx -> DuckEvent(r, ctx.spawnTimeMs, ctx.viewportRatio) },
        { c, _ -> !c.isNight && !c.isPrecipitating },
        EventPreviewSpec(1.25f, GROUND_PREVIEW_Y, 0.5f)),
    // Evening/dusk/warm nights in spring and summer
    FROG("frog", "Frog", 18,
        { r, ctx -> FrogEvent(r, ctx.spawnTimeMs) },
        { c, _ -> !c.isWinter && !c.isAutumn && (c.isDusk || c.isNight) },
        EventPreviewSpec(1.25f, GROUND_PREVIEW_Y, 0.5f)),
    // Burst of the current shower when triggered manually; the annual showers (Perseids,
    // Geminids, Quadrantids) spawn single meteors by solar longitude in RandomEventLayer.
    PERSEIDS("perseids", "Meteor Showers", 19,
        { r, ctx -> MeteorShowerEvent(r, ctx.spawnTimeMs) },
        { _, _ -> false },
        EventPreviewSpec(1.25f, SKY_PREVIEW_Y, 0.02f)),
    // Real background stars drop and burn out; new ones slowly appear elsewhere
    STAR_FALL("starfall", "Falling Stars", 20,
        { r, ctx -> StarFallEvent(r, ctx.spawnTimeMs) },
        { c, random -> c.isNight && c.isClearEnough && random.nextFloat() < 0.25f },
        EventPreviewSpec(0.4f, 0f, 0.02f)),
    // Autumn leaves detaching from deciduous tree crowns and fluttering to the hill
    FALLING_LEAF("fallingleaf", "Falling Leaf", 21,
        { r, ctx -> FallingLeafEvent(r, ctx.spawnTimeMs, ctx.spawnPosition) },
        { c, random -> !c.isWinter && (c.isAutumn || random.nextFloat() < 0.15f) },
        EventPreviewSpec(1.35f, 0f, 0.2f),
        EventSpawnSource.TREE_CROWN);

    val prefKey: String get() = "pref_event_$id"

    fun create(
        random: Random,
        spawnTimeMs: Long,
        viewportRatio: Float = 1.0f,
        spawnPosition: EventSpawnPosition? = null
    ): RandomEvent = factory(random, EventSpawnContext(spawnTimeMs, viewportRatio, spawnPosition))

    /** Deterministic automatic spawn: seeded from the spawn time plus [spawnSeedOffset]. */
    fun createForSpawn(
        spawnTimeMs: Long,
        viewportRatio: Float,
        spawnPosition: EventSpawnPosition? = null
    ): RandomEvent = create(Random(spawnTimeMs + spawnSeedOffset), spawnTimeMs, viewportRatio, spawnPosition)

    companion object {
        fun fromId(id: String): EventSpec? = entries.firstOrNull { it.id == id }

        fun fromPrefKey(key: String?): EventSpec? = entries.firstOrNull { it.prefKey == key }

        /** Candidate order of the "spawn random event" button; new types are appended. */
        val DEBUG_SPAWN_ORDER: List<EventSpec> = listOf(
            AIRPLANE, BIRDS, EAGLE, BUTTERFLY, RABBIT, FOX, SHOOTING_STAR, FIREFLIES, OWL,
            BALLOON, ALIEN, SQUIRREL, DRAGONFLY, SNOWFLAKE, BAT, DEER, FISH, DUCK, FROG,
            PERSEIDS, STAR_FALL, FALLING_LEAF
        )
    }
}
