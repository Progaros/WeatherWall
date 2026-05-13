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

    /**
     * Deterministic hash producing a float in [0, 1).
     * Zero-allocation alternative to creating a Random object.
     * Uses integer avalanche mixing for uniform distribution.
     *
     * @param seed Base seed (e.g. user setting).
     * @param index Cell or item index (world identity).
     * @param salt Additional differentiator for multiple values from same cell.
     */
    fun hashToFloat(seed: Int, index: Int, salt: Int = 0): Float {
        var x = index * 374761393 + seed * 668265263 + salt * 1443059027
        x = (x xor (x ushr 13)) * 1274126177
        x = (x xor (x ushr 16))
        return (x and 0x7fffffff) / 2147483647f
    }
}
