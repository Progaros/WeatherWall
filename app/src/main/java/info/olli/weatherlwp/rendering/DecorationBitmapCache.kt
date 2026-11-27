package info.olli.weatherlwp.rendering

import android.graphics.Bitmap

/**
 * Per-hill baked decoration bitmaps plus the keys that decide when they must be rebaked.
 * Baking itself stays in [TerrainLayer]; this class only owns state and the rebake decision.
 */
internal class DecorationBitmapCache {
    @Volatile private var bitmaps: Array<Bitmap?> = emptyArray()
    // Bitmap for hill i starts at screen y = topYs[i].
    private var topYs: FloatArray = floatArrayOf()
    private var bitmapW = 0
    private var screenH = 0f
    @Volatile private var dirty = true
    private var suppressed = false
    private var seasonBucket = -1
    private var ambientBucket = -1
    private var shadowBucket = Int.MIN_VALUE

    /** After an OOM, rebakes driven only by season/ambient/shadow buckets are skipped. */
    var oomBlocked = false

    fun needsRebake(
        bitmapW: Int,
        screenH: Float,
        hillCount: Int,
        seasonBkt: Int,
        ambientBkt: Int,
        shadowBkt: Int,
    ): Boolean {
        if (suppressed) return false
        val sizeChanged = this.bitmapW != bitmapW ||
            this.screenH != screenH ||
            bitmaps.size != hillCount
        val rebakeNeeded = dirty || sizeChanged ||
            seasonBkt != seasonBucket ||
            ambientBkt != ambientBucket ||
            shadowBkt != shadowBucket
        return rebakeNeeded && (!oomBlocked || dirty || sizeChanged)
    }

    fun markBaked(seasonBkt: Int, ambientBkt: Int, shadowBkt: Int) {
        dirty = false
        seasonBucket = seasonBkt
        ambientBucket = ambientBkt
        shadowBucket = shadowBkt
    }

    /** Recycles the previous bitmaps and installs the freshly baked ones. */
    fun publish(newBitmaps: Array<Bitmap?>, newTopYs: FloatArray, w: Int, h: Float) {
        recycle()
        bitmaps = newBitmaps
        topYs = newTopYs
        bitmapW = w
        screenH = h
    }

    fun bitmapAt(index: Int): Bitmap? = bitmaps.getOrNull(index)

    fun topYAt(index: Int): Float = topYs.getOrElse(index) { 0f }

    fun hasAnyBitmap(): Boolean = bitmaps.any { it != null }

    fun markDirty() {
        dirty = true
    }

    /** Memory trim: drop references without recycling and stop rebaking until [resume]. */
    fun release() {
        // Null only (no recycle): a posted hardware canvas may still reference them; GC reclaims.
        bitmaps = emptyArray()
        dirty = true
        suppressed = true
    }

    fun resume() {
        suppressed = false
    }

    fun recycle() {
        val old = bitmaps
        bitmaps = emptyArray()
        old.forEach { it?.recycle() }
    }
}
