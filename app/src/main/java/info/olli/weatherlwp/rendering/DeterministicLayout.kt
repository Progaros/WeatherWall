package info.olli.weatherlwp.rendering

import kotlin.random.Random

/**
 * Deterministic seed derivation for layout generation.
 * Keeps object placement stable when counts or viewport sizes change.
 */
object DeterministicLayout {
    fun mix(baseSeed: Int, section: Int, index: Int): Long {
        var x = baseSeed.toLong()
        x = x * 6364136223846793005L + 1442695040888963407L
        x = x xor (section.toLong() shl 32)
        x = x xor index.toLong()
        x = x xor (x shl 13)
        x = x xor (x ushr 7)
        x = x xor (x shl 17)
        return x
    }

    fun random(baseSeed: Int, section: Int, index: Int): Random {
        return Random(mix(baseSeed, section, index))
    }
}
