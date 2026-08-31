package info.olli.weatherlwp

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import info.olli.weatherlwp.parallax.ParallaxCalculator
import info.olli.weatherlwp.rendering.events.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/**
 * Renders each event type to cropped PNG files for visual review.
 * Uses Robolectric native graphics for real Canvas rendering.
 * Produces both full-scene and auto-cropped+zoomed views.
 * 
 * Output: /workspace/event_renders/<EventName>.png (cropped)
 *         /workspace/event_renders/<EventName>_full.png (full scene)
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class EventRenderTest {

    private val width = 1080f
    private val height = 2400f
    private val cropSize = 400  // Output size for cropped view
    private val outputDir = File("/workspace/event_renders").also { it.mkdirs() }

    /**
     * Find bounding box of non-background pixels in bitmap.
     */
    private fun findContentBounds(bitmap: Bitmap, bgColor: Int): Rect? {
        val w = bitmap.width
        val h = bitmap.height
        var minX = w; var minY = h; var maxX = 0; var maxY = 0
        val pixels = IntArray(w)
        
        for (y in 0 until h) {
            bitmap.getPixels(pixels, 0, w, 0, y, w, 1)
            for (x in 0 until w) {
                if (pixels[x] != bgColor && Color.alpha(pixels[x]) > 10) {
                    minX = min(minX, x)
                    minY = min(minY, y)
                    maxX = max(maxX, x)
                    maxY = max(maxY, y)
                }
            }
        }
        
        return if (maxX > minX && maxY > minY) Rect(minX, minY, maxX, maxY) else null
    }

    /**
     * Crop bitmap around content bounds with padding, then scale up to cropSize.
     */
    private fun cropAndScale(bitmap: Bitmap, bounds: Rect, @Suppress("UNUSED_PARAMETER") bgColor: Int): Bitmap {
        // Add generous padding (80% of content size on each side, min 30px)
        val contentW = bounds.width()
        val contentH = bounds.height()
        val padX = max(contentW * 4 / 5, 30)
        val padY = max(contentH * 4 / 5, 30)
        
        val cropLeft = max(0, bounds.left - padX)
        val cropTop = max(0, bounds.top - padY)
        val cropRight = min(bitmap.width, bounds.right + padX)
        val cropBottom = min(bitmap.height, bounds.bottom + padY)
        val cropW = cropRight - cropLeft
        val cropH = cropBottom - cropTop
        
        // Make it square-ish by expanding the shorter dimension
        val squareSize = max(cropW, cropH)
        val finalLeft = max(0, cropLeft - (squareSize - cropW) / 2)
        val finalTop = max(0, cropTop - (squareSize - cropH) / 2)
        val finalW = min(squareSize, bitmap.width - finalLeft)
        val finalH = min(squareSize, bitmap.height - finalTop)
        
        // Crop
        val cropped = Bitmap.createBitmap(bitmap, finalLeft, finalTop, finalW, finalH)
        
        // Always scale up to cropSize for visibility
        return Bitmap.createScaledBitmap(cropped, cropSize, cropSize, true)
    }

    /**
     * Render a single event at a given progress, return the bitmap and bg color used.
     * Ground is drawn ONLY on the full version, not here, so content detection works.
     */
    private fun renderToBitmap(event: RandomEvent, progress: Float, bgColor: Int, withGround: Boolean = false): Bitmap {
        val absoluteTimeMs = event.spawnTimeMs + (event.durationMs * progress).toLong()
        return renderAtAbsoluteTime(event, absoluteTimeMs, bgColor, withGround)
    }

    private fun renderAtAbsoluteTime(
        event: RandomEvent,
        absoluteTimeMs: Long,
        bgColor: Int,
        withGround: Boolean = false
    ): Bitmap {
        val bitmap = Bitmap.createBitmap(width.toInt(), height.toInt(), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(bgColor)
        
        // Draw ground for GROUND layer events (only in full renders)
        if (withGround && event.layer == EventLayer.GROUND) {
            val groundPaint = Paint().apply {
                color = Color.rgb(100, 160, 80)
                style = Paint.Style.FILL
            }
            canvas.drawRect(0f, height * 0.78f, width, height, groundPaint)
        }
        
        val paint = Paint().apply { isAntiAlias = true; style = Paint.Style.FILL }
        val strokePaint = Paint().apply { isAntiAlias = true; style = Paint.Style.STROKE; strokeWidth = 2f }
        val parallax = ParallaxCalculator()
        parallax.setCanvasSize(width, height)
        
        val hillProvider: EventTerrainProvider = { _, _ -> 0.80f }
        
        event.draw(canvas, paint, strokePaint, parallax, width, height,
            timeSeconds = absoluteTimeMs / 1000f,
            ambientColor = Color.rgb(255, 250, 240),
            extendedOffset = 0f,
            absoluteTimeMs = absoluteTimeMs,
            hillHeightProvider = hillProvider)
        
        return bitmap
    }

    /**
     * Render event at multiple progress points, pick the one with the most content,
     * then save both full and cropped versions.
     */
    private fun renderEvent(name: String, event: RandomEvent, bgColor: Int = Color.rgb(135, 190, 230)) {
        // Try several progress points to find the best one (most visible content)
        val progresses = listOf(0.2f, 0.35f, 0.5f, 0.65f, 0.8f)
        
        var bestBitmap: Bitmap? = null
        var bestBounds: Rect? = null
        var bestArea = 0
        var bestProgress = 0.5f
        
        for (progress in progresses) {
            val bitmap = renderToBitmap(event, progress, bgColor, withGround = false)
            val bounds = findContentBounds(bitmap, bgColor)
            if (bounds != null) {
                val area = bounds.width() * bounds.height()
                // Also check if content is actually on-screen (not clipped at edges)
                val onScreen = bounds.left > 5 && bounds.right < width.toInt() - 5
                val effectiveArea = if (onScreen) area else area / 2
                if (effectiveArea > bestArea) {
                    bestArea = effectiveArea
                    bestBitmap = bitmap
                    bestBounds = bounds
                    bestProgress = progress
                }
            }
        }
        
        if (bestBitmap == null || bestBounds == null) {
            println("WARNING: No content found for $name")
            // Save empty render anyway
            val bitmap = renderToBitmap(event, 0.5f, bgColor, withGround = true)
            File(outputDir, "$name.png").outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            return
        }
        
        // Save full scene (with ground)
        val fullBitmap = renderToBitmap(event, bestProgress, bgColor, withGround = true)
        File(outputDir, "${name}_full.png").outputStream().use {
            fullBitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        
        // Save cropped + zoomed version
        val cropped = cropAndScale(bestBitmap, bestBounds, bgColor)
        File(outputDir, "$name.png").outputStream().use {
            cropped.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        
        println("Rendered $name: content at [${bestBounds.left},${bestBounds.top}]-[${bestBounds.right},${bestBounds.bottom}] (${bestBounds.width()}x${bestBounds.height()}px)")
    }

    /**
     * Render 3 animation frames, all cropped to the same region for comparison.
     */
    private fun renderFrames(name: String, event: RandomEvent, bgColor: Int = Color.rgb(135, 190, 230)) {
        val progresses = listOf(0.2f, 0.5f, 0.8f)
        
        // First pass: find the union bounding box across all frames (without ground)
        var unionLeft = width.toInt(); var unionTop = height.toInt()
        var unionRight = 0; var unionBottom = 0
        val bitmaps = mutableListOf<Bitmap>()
        
        for (progress in progresses) {
            val bitmap = renderToBitmap(event, progress, bgColor, withGround = false)
            bitmaps.add(bitmap)
            val bounds = findContentBounds(bitmap, bgColor)
            if (bounds != null) {
                unionLeft = min(unionLeft, bounds.left)
                unionTop = min(unionTop, bounds.top)
                unionRight = max(unionRight, bounds.right)
                unionBottom = max(unionBottom, bounds.bottom)
            }
        }
        
        if (unionRight <= unionLeft) return
        
        val unionBounds = Rect(unionLeft, unionTop, unionRight, unionBottom)
        
        // Save each frame cropped to the union bounding box
        bitmaps.forEachIndexed { idx, bitmap ->
            val cropped = cropAndScale(bitmap, unionBounds, bgColor)
            File(outputDir, "${name}_f${idx + 1}.png").outputStream().use {
                cropped.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        }
    }

    /** Fixed-size crop centred on the content, so limbs animate without the body drifting out of frame. */
    private fun cropCentered(bitmap: Bitmap, bounds: Rect, side: Int): Bitmap {
        val clamped = side.coerceIn(16, min(bitmap.width, bitmap.height))
        val cx = (bounds.left + bounds.right) / 2
        val cy = (bounds.top + bounds.bottom) / 2
        val left = (cx - clamped / 2).coerceIn(0, bitmap.width - clamped)
        val top = (cy - clamped / 2).coerceIn(0, bitmap.height - clamped)
        val cropped = Bitmap.createBitmap(bitmap, left, top, clamped, clamped)
        return Bitmap.createScaledBitmap(cropped, cropSize, cropSize, true)
    }

    private fun buildSheet(tiles: List<Pair<String, Bitmap>>, cols: Int): Bitmap {
        val tileSize = cropSize
        val rows = (tiles.size + cols - 1) / cols
        val sheet = Bitmap.createBitmap(cols * tileSize, rows * tileSize, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(sheet)
        canvas.drawColor(Color.DKGRAY)
        val labelPaint = Paint().apply {
            color = Color.WHITE; textSize = 20f; isAntiAlias = true
            setShadowLayer(2f, 1f, 1f, Color.BLACK)
        }
        for ((idx, pair) in tiles.withIndex()) {
            val x = (idx % cols) * tileSize
            val y = (idx / cols) * tileSize
            canvas.drawBitmap(pair.second, x.toFloat(), y.toFloat(), null)
            canvas.drawText(pair.first, x + 6f, y + 24f, labelPaint)
        }
        return sheet
    }

    /**
     * Tile a short slice of the animation so limb phases can be compared side by side.
     * Frames are spaced in real time rather than by progress: a gait cycle lasts under a second
     * while an event lives for many seconds, so progress-based sampling only shows the animal
     * at different screen positions, never at different points of its stride.
     */
    private fun renderGaitSheet(
        name: String,
        event: RandomEvent,
        frames: Int = 8,
        stepMs: Long = 100L,
        startProgress: Float = 0.35f,
        bgColor: Int = Color.rgb(135, 190, 230)
    ) {
        val startMs = event.spawnTimeMs + (event.durationMs * startProgress).toLong()
        val rendered = (0 until frames).map { i ->
            val bmp = renderAtAbsoluteTime(event, startMs + i * stepMs, bgColor, withGround = false)
            bmp to findContentBounds(bmp, bgColor)
        }

        var side = 0
        for ((_, bounds) in rendered) {
            if (bounds != null) side = max(side, max(bounds.width(), bounds.height()))
        }
        if (side <= 0) {
            println("WARNING: No content found for gait sheet $name")
            return
        }
        // Head-room so limbs at their extremes never touch the tile edge.
        side = (side * 9) / 5

        val tiles = rendered.mapIndexedNotNull { i, (bmp, bounds) ->
            bounds?.let { "+${i * stepMs}ms" to cropCentered(bmp, it, side) }
        }
        if (tiles.isEmpty()) return

        val dir = File(outputDir, "gait").also { it.mkdirs() }
        File(dir, "$name.png").outputStream().use {
            buildSheet(tiles, cols = min(4, tiles.size)).compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        println("Gait sheet $name: ${tiles.size} frames, ${stepMs}ms apart, tile source ${side}px")
    }

    // ==================== Ground Events ====================

    @Test fun renderFox() {
        val event = FoxEvent(Random(42), spawnTimeMs = 1000000L)
        renderEvent("FoxEvent", event)
        renderFrames("FoxEvent", event)
    }

    @Test fun renderDeer() {
        // Deer moves very slowly and only peeks at screen edge during its 15s lifetime.
        // Try multiple seeds to find one where content is on screen.
        val seeds = listOf(7L, 42L, 100L, 200L, 300L)
        for (seed in seeds) {
            val event = DeerEvent(Random(seed), spawnTimeMs = 1000000L)
            val bitmap = renderToBitmap(event, 0.95f, Color.rgb(135, 190, 230), withGround = false)
            val bounds = findContentBounds(bitmap, Color.rgb(135, 190, 230))
            if (bounds != null && bounds.width() > 30) {
                renderEvent("DeerEvent", event)
                renderFrames("DeerEvent", event)
                return
            }
        }
        // Fallback: use seed 42 at 95% progress (deer at edge is the natural look)
        val event = DeerEvent(Random(42), spawnTimeMs = 1000000L)
        renderEvent("DeerEvent", event)
    }

    @Test fun renderRabbit() {
        val event = RabbitEvent(Random(42), spawnTimeMs = 1000000L)
        renderEvent("RabbitEvent", event)
        renderFrames("RabbitEvent", event)
    }

    @Test fun renderSquirrel() {
        val event = SquirrelEvent(Random(42), spawnTimeMs = 1000000L)
        renderEvent("SquirrelEvent", event)
        renderFrames("SquirrelEvent", event)
    }

    /**
     * Gait sheets for the walking animals. These are the renders to look at when judging
     * leg motion, body attachment and silhouette, which the progress-based frames cannot show.
     */
    @Test fun renderGroundAnimalGaits() {
        renderGaitSheet("FoxEvent", FoxEvent(Random(42), spawnTimeMs = 1000000L))
        renderGaitSheet("DeerEvent", DeerEvent(Random(42), spawnTimeMs = 1000000L), startProgress = 0.9f)
        renderGaitSheet("RabbitEvent", RabbitEvent(Random(42), spawnTimeMs = 1000000L))
        renderGaitSheet("SquirrelEvent", SquirrelEvent(Random(42), spawnTimeMs = 1000000L))
    }

    // ==================== Sky Events ====================

    @Test fun renderEagle() {
        val event = EagleEvent(Random(42), spawnTimeMs = 1000000L)
        renderEvent("EagleEvent", event)
        renderFrames("EagleEvent", event)
    }

    @Test fun renderBirdFlock() {
        val event = BirdFlockEvent(Random(42), spawnTimeMs = 1000000L)
        renderEvent("BirdFlockEvent", event)
        renderFrames("BirdFlockEvent", event)
    }

    @Test fun renderButterfly() {
        val event = ButterflyEvent(Random(42), spawnTimeMs = 1000000L)
        renderEvent("ButterflyEvent", event)
        renderFrames("ButterflyEvent", event)
    }

    @Test fun renderDragonfly() {
        val event = DragonflyEvent(Random(42), spawnTimeMs = 1000000L)
        renderEvent("DragonflyEvent", event)
        renderFrames("DragonflyEvent", event)
    }

    @Test fun renderAirplane() {
        val event = AirplaneEvent(Random(42), spawnTimeMs = 1000000L)
        renderEvent("AirplaneEvent", event)
        renderFrames("AirplaneEvent", event)
    }

    @Test fun renderHotAirBalloon() {
        val event = HotAirBalloonEvent(Random(42), spawnTimeMs = 1000000L)
        renderEvent("HotAirBalloonEvent", event)
        renderFrames("HotAirBalloonEvent", event)
    }

    // ==================== Night Events ====================

    @Test fun renderBat() {
        val bg = Color.rgb(25, 25, 50)
        val event = BatEvent(Random(42), spawnTimeMs = 1000000L)
        renderEvent("BatEvent", event, bgColor = bg)
        renderFrames("BatEvent", event, bgColor = bg)
    }

    @Test fun renderOwl() {
        val bg = Color.rgb(25, 25, 50)
        val event = OwlEvent(Random(42), spawnTimeMs = 1000000L)
        renderEvent("OwlEvent", event, bgColor = bg)
        renderFrames("OwlEvent", event, bgColor = bg)
    }

    @Test fun renderFirefly() {
        val bg = Color.rgb(15, 20, 40)
        val event = FireflyEvent(Random(42), spawnTimeMs = 1000000L)
        renderEvent("FireflyEvent", event, bgColor = bg)
    }

    // ==================== Far Sky Events ====================

    @Test fun renderShootingStar() {
        val bg = Color.rgb(15, 15, 35)
        val event = ShootingStarEvent(Random(42), spawnTimeMs = 1000000L)
        renderEvent("ShootingStarEvent", event, bgColor = bg)
        renderFrames("ShootingStarEvent", event, bgColor = bg)
    }

    @Test fun renderSnowflake() {
        val bg = Color.rgb(180, 200, 220)
        val event = SnowflakeEvent(Random(42), spawnTimeMs = 1000000L)
        renderEvent("SnowflakeEvent", event, bgColor = bg)
    }

    @Test fun renderAlien() {
        val bg = Color.rgb(15, 15, 35)
        val event = AlienEvent(Random(42), spawnTimeMs = 1000000L)
        renderEvent("AlienEvent", event, bgColor = bg)
        renderFrames("AlienEvent", event, bgColor = bg)
    }
}
