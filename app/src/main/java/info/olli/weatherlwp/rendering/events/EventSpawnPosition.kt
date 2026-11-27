package info.olli.weatherlwp.rendering.events

import kotlin.random.Random

/**
 * Optional explicit spawn coordinates for an event.
 *
 * Allows events such as [FallingLeafEvent] to originate from world-anchored features
 * (for example, a specific tree crown) or from a caller-specified screen position,
 * while still supporting default self-positioned spawning when omitted.
 *
 * @param x Normalized X in extended event field space (0..1, matching [EventTerrainProvider]).
 * @param y Normalized Y in screen-height ratio space (0 = top, 1 = bottom).
 * @param depthFactor Optional hill depth factor (0..1) for ground/tree-interleaved events.
 */
data class EventSpawnPosition(
    val x: Float,
    val y: Float,
    val depthFactor: Float? = null
)

/**
 * Declares how an [EventSpec] prefers to resolve its spawn position when none is
 * explicitly supplied by the caller.
 */
enum class EventSpawnSource {
    /** Event picks its own screen/path coordinates from its seeded Random. */
    DEFAULT,
    /** Event prefers to spawn from a visible deciduous tree crown when available. */
    TREE_CROWN
}

/**
 * Context passed to event factories during automatic and manual spawning.
 */
data class EventSpawnContext(
    val spawnTimeMs: Long,
    val viewportRatio: Float = 1.0f,
    val spawnPosition: EventSpawnPosition? = null
)

/**
 * Provider that deterministically selects a tree-crown spawn position in event field coordinates.
 */
typealias EventTreeSpawnProvider = (random: Random) -> EventSpawnPosition?
