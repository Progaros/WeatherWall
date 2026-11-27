package info.olli.weatherlwp.rendering

/**
 * Counters that make cached-geometry regressions observable.
 *
 * Hill silhouettes, frost contours and decoration bitmaps are static for a given
 * screen size and settings snapshot. During steady-state rendering every `*Builds`
 * counter must stay flat while [framesRendered] keeps growing; any growth means
 * per-frame work crept back in.
 */
class TerrainRenderStats {
    @Volatile
    var framesRendered = 0L
        private set

    /** Number of [TerrainLayer.buildHillGeometry] invocations. */
    @Volatile
    var hillGeometryBuilds = 0L
        private set

    /** Number of hill fill [android.graphics.Path] objects constructed. */
    @Volatile
    var hillPathBuilds = 0L
        private set

    /** Number of frost contour [android.graphics.Path] objects constructed. */
    @Volatile
    var frostPathBuilds = 0L
        private set

    /** Number of [TerrainLayer.bakeDecorationBitmaps] invocations. */
    @Volatile
    var decorationBakes = 0L
        private set

    /** Number of decoration [android.graphics.Bitmap] objects allocated. */
    @Volatile
    var decorationBitmapBuilds = 0L
        private set

    /** Total bytes held by the currently baked decoration bitmaps. */
    @Volatile
    var decorationBitmapBytes = 0L
        private set

    internal fun recordFrame() {
        framesRendered++
    }

    internal fun recordHillGeometryBuild(hillCount: Int) {
        hillGeometryBuilds++
        hillPathBuilds += hillCount.toLong()
        frostPathBuilds += hillCount.toLong()
    }

    internal fun recordDecorationBake(bitmapCount: Int) {
        decorationBakes++
        decorationBitmapBuilds += bitmapCount.toLong()
    }

    internal fun recordDecorationBytes(bytes: Long) {
        decorationBitmapBytes = bytes
    }

    fun reset() {
        framesRendered = 0L
        hillGeometryBuilds = 0L
        hillPathBuilds = 0L
        frostPathBuilds = 0L
        decorationBakes = 0L
        decorationBitmapBuilds = 0L
    }

    /** Total static-geometry rebuilds; must be 0 during steady-state rendering. */
    val totalRebuilds: Long
        get() = hillGeometryBuilds + decorationBakes

    override fun toString(): String =
        "frames=$framesRendered hillGeometryBuilds=$hillGeometryBuilds " +
            "hillPaths=$hillPathBuilds frostPaths=$frostPathBuilds " +
            "decorationBakes=$decorationBakes decorationBitmaps=$decorationBitmapBuilds " +
            "decorationBytes=$decorationBitmapBytes"
}
