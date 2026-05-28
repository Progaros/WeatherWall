package info.olli.weatherlwp

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import info.olli.weatherlwp.parallax.ParallaxCalculator
import info.olli.weatherlwp.rendering.CloudLayer
import info.olli.weatherlwp.rendering.MountainLayer
import info.olli.weatherlwp.rendering.SceneLayer
import info.olli.weatherlwp.rendering.SkyLayer
import info.olli.weatherlwp.rendering.TerrainLayer
import info.olli.weatherlwp.rendering.WeatherLayer
import info.olli.weatherlwp.rendering.events.*
import info.olli.weatherlwp.simulation.CloudDensity
import info.olli.weatherlwp.simulation.SimulationSettings
import info.olli.weatherlwp.simulation.SimulationState
import info.olli.weatherlwp.weather.WeatherType
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/**
 * Full-scene screenshot test for visual inspection.
 * Renders events integrated into actual scene layers (sky, mountains, terrain, clouds, weather).
 * Outputs full-scene, medium-crop, and detail-crop variants for review.
 *
 * Run in Docker: ./gradlew testDebugUnitTest --tests "info.olli.weatherlwp.SceneScreenshotTest"
 * Output: /workspace/event_renders/screenshots/
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class SceneScreenshotTest {

    private val width = 1080f
    private val height = 1920f
    private val widthInt = width.toInt()
    private val heightInt = height.toInt()
    private val outputDir = File("/workspace/event_renders/screenshots").also { it.mkdirs() }

    private lateinit var skyLayer: SkyLayer
    private lateinit var mountainLayer: MountainLayer
    private lateinit var terrainLayer: TerrainLayer
    private lateinit var cloudLayer: CloudLayer
    private lateinit var weatherLayer: WeatherLayer
    private lateinit var parallax: ParallaxCalculator

    // Simulation states for different times/weather
    private lateinit var stateDay: SimulationState
    private lateinit var stateDawn: SimulationState
    private lateinit var stateDusk: SimulationState
    private lateinit var stateNight: SimulationState
    private lateinit var stateOvercast: SimulationState
    private lateinit var stateRain: SimulationState
    private lateinit var stateFog: SimulationState
    private lateinit var stateSnow: SimulationState

    private val baseTime = 1700000000000L // Fixed epoch for determinism

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()

        val settings = SimulationSettings(snapshot = mapOf(
            "pref_random_seed" to 42,
            "pref_hill_count" to 5,
            "pref_tree_density" to 60,
            "pref_tree_sparsity" to 20,
            "pref_mountain_density" to 50,
            "pref_clouds_enabled" to true,
            "pref_cloud_density" to 50,
            "pref_sun_enabled" to true,
            "pref_moon_enabled" to true,
            "pref_spring_flowers" to true,
            "pref_spring_flower_density" to 60,
            "pref_events_enabled" to true
        ))

        skyLayer = SkyLayer(context).apply {
            resize(width, height)
            reloadSettings(settings)
        }
        mountainLayer = MountainLayer(context).apply {
            resize(width, height)
            reloadSettings(settings)
        }
        terrainLayer = TerrainLayer(context).apply {
            resize(width, height)
            reloadSettings(settings)
        }
        cloudLayer = CloudLayer(context).apply {
            resize(width, height)
            reloadSettings(settings)
        }
        weatherLayer = WeatherLayer().apply {
            resize(width, height)
            setIntensity(0.8f)
        }

        parallax = ParallaxCalculator().apply { setCanvasSize(width, height) }

        stateDay = SimulationState.default().copy(
            timeOfDayHours = 14f, isNight = false, isDawn = false, isDusk = false,
            dayProgress = 0.58f, weather = WeatherType.CLEAR,
            cloudDensity = CloudDensity.LIGHT, windStrength = 0.3f,
            screenWidth = width, screenHeight = height,
            timeSeconds = 50000f, absoluteTimeMs = baseTime
        )
        stateDawn = SimulationState.default().copy(
            timeOfDayHours = 6.5f, isNight = false, isDawn = true, isDusk = false,
            dayProgress = 0.1f, weather = WeatherType.CLEAR,
            cloudDensity = CloudDensity.LIGHT, windStrength = 0.2f,
            screenWidth = width, screenHeight = height,
            timeSeconds = 23400f, absoluteTimeMs = baseTime
        )
        stateDusk = SimulationState.default().copy(
            timeOfDayHours = 19.5f, isNight = false, isDawn = false, isDusk = true,
            dayProgress = 0.9f, weather = WeatherType.CLEAR,
            cloudDensity = CloudDensity.MEDIUM, windStrength = 0.2f,
            screenWidth = width, screenHeight = height,
            timeSeconds = 70200f, absoluteTimeMs = baseTime
        )
        stateNight = SimulationState.default().copy(
            timeOfDayHours = 23f, isNight = true, isDawn = false, isDusk = false,
            dayProgress = 0.0f, weather = WeatherType.CLEAR,
            cloudDensity = CloudDensity.LIGHT, windStrength = 0.1f,
            nightShadeStrength = 0.7f, screenWidth = width, screenHeight = height,
            timeSeconds = 82800f, absoluteTimeMs = baseTime
        )
        stateOvercast = SimulationState.default().copy(
            timeOfDayHours = 12f, isNight = false, isDawn = false, isDusk = false,
            dayProgress = 0.5f, weather = WeatherType.OVERCAST,
            cloudDensity = CloudDensity.OVERCAST, windStrength = 0.4f,
            cloudCover = 0.9f, screenWidth = width, screenHeight = height,
            timeSeconds = 43200f, absoluteTimeMs = baseTime
        )
        stateRain = SimulationState.default().copy(
            timeOfDayHours = 15f, isNight = false, isDawn = false, isDusk = false,
            dayProgress = 0.6f, weather = WeatherType.RAIN,
            cloudDensity = CloudDensity.HEAVY, windStrength = 0.6f,
            cloudCover = 0.95f, precipIntensity = 0.7f,
            screenWidth = width, screenHeight = height,
            timeSeconds = 54000f, absoluteTimeMs = baseTime
        )
        stateFog = SimulationState.default().copy(
            timeOfDayHours = 7f, isNight = false, isDawn = true, isDusk = false,
            dayProgress = 0.12f, weather = WeatherType.FOG,
            cloudDensity = CloudDensity.MEDIUM, windStrength = 0.1f,
            visibility = 0.3f, screenWidth = width, screenHeight = height,
            timeSeconds = 25200f, absoluteTimeMs = baseTime
        )
        stateSnow = SimulationState.default().copy(
            timeOfDayHours = 11f, isNight = false, isDawn = false, isDusk = false,
            dayProgress = 0.45f, weather = WeatherType.SNOW,
            cloudDensity = CloudDensity.HEAVY, windStrength = 0.3f,
            snowLevel = 0.8f, currentSeason = "winter",
            screenWidth = width, screenHeight = height,
            timeSeconds = 39600f, absoluteTimeMs = baseTime
        )
    }

    // =========================================================================
    // Scene rendering helpers
    // =========================================================================

    /** Render full scene background (sky + mountains + terrain + clouds + weather). */
    private fun renderSceneBackground(state: SimulationState): Bitmap {
        val bitmap = Bitmap.createBitmap(widthInt, heightInt, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val layers: List<SceneLayer> = listOf(skyLayer, cloudLayer, mountainLayer, terrainLayer)
        for (layer in layers) {
            layer.update(state, 16L)
            layer.draw(canvas, state, parallax)
        }

        // Weather layer renders precipitation/fog on top of the scene.
        // Simulate several frames so particle buffers are populated and visible.
        for (i in 0 until 10) {
            weatherLayer.update(state, 16L)
        }
        weatherLayer.draw(canvas, state, parallax)

        return bitmap
    }

    /** Render event onto a scene background bitmap. */
    private fun renderEventOnScene(
        event: RandomEvent,
        progress: Float,
        state: SimulationState,
        sceneBg: Bitmap
    ): Bitmap {
        val bitmap = sceneBg.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(bitmap)
        drawEventOnCanvas(canvas, event, progress, state)
        return bitmap
    }

    /** Draw a single event onto an existing canvas. */
    private fun drawEventOnCanvas(
        canvas: Canvas,
        event: RandomEvent,
        progress: Float,
        state: SimulationState
    ) {
        val paint = Paint().apply { isAntiAlias = true; style = Paint.Style.FILL }
        val strokePaint = Paint().apply {
            isAntiAlias = true; style = Paint.Style.STROKE; strokeWidth = 2f
        }
        val absoluteTimeMs = event.spawnTimeMs + (event.durationMs * progress).toLong()
        val ambientColor = getAmbientColor(state)
        val hillProvider: HillHeightProvider = { _, _ -> 0.82f }

        event.draw(
            canvas, paint, strokePaint, parallax, width, height,
            timeSeconds = absoluteTimeMs / 1000f,
            ambientColor = ambientColor,
            extendedOffset = 0f,
            absoluteTimeMs = absoluteTimeMs,
            hillHeightProvider = if (event.layer == EventLayer.GROUND) hillProvider else null
        )
    }

    /** Get ambient color from simulation state. */
    private fun getAmbientColor(state: SimulationState): Int {
        return when {
            state.isNight -> Color.rgb(60, 70, 120)
            state.isDawn -> Color.rgb(255, 200, 150)
            state.isDusk -> Color.rgb(200, 140, 100)
            state.weather == WeatherType.OVERCAST -> Color.rgb(180, 185, 190)
            state.weather == WeatherType.RAIN -> Color.rgb(140, 145, 160)
            state.weather == WeatherType.FOG -> Color.rgb(200, 210, 215)
            state.weather == WeatherType.SNOW -> Color.rgb(220, 225, 235)
            else -> Color.rgb(255, 250, 240)
        }
    }

    // =========================================================================
    // Crop and quality helpers
    // =========================================================================

    /** Find bounding box of event pixels by diffing composite vs background. */
    private fun findEventBounds(composite: Bitmap, background: Bitmap): Rect? {
        val w = composite.width
        val h = composite.height
        var minX = w; var minY = h; var maxX = 0; var maxY = 0

        val compRow = IntArray(w)
        val bgRow = IntArray(w)
        for (y in 0 until h) {
            composite.getPixels(compRow, 0, w, 0, y, w, 1)
            background.getPixels(bgRow, 0, w, 0, y, w, 1)
            for (x in 0 until w) {
                if (compRow[x] != bgRow[x]) {
                    minX = min(minX, x)
                    minY = min(minY, y)
                    maxX = max(maxX, x)
                    maxY = max(maxY, y)
                }
            }
        }
        return if (maxX > minX && maxY > minY) Rect(minX, minY, maxX + 1, maxY + 1) else null
    }

    /** Crop bitmap to bounds with padding. */
    private fun cropWithPadding(bitmap: Bitmap, bounds: Rect, padFactor: Float = 0.5f): Bitmap {
        val padX = (bounds.width() * padFactor).toInt().coerceAtLeast(20)
        val padY = (bounds.height() * padFactor).toInt().coerceAtLeast(20)
        val left = (bounds.left - padX).coerceAtLeast(0)
        val top = (bounds.top - padY).coerceAtLeast(0)
        val right = (bounds.right + padX).coerceAtMost(bitmap.width)
        val bottom = (bounds.bottom + padY).coerceAtMost(bitmap.height)
        return Bitmap.createBitmap(bitmap, left, top, right - left, bottom - top)
    }

    data class QualityMetrics(
        val bounds: Rect?,
        val boundsWidthPx: Int,
        val boundsHeightPx: Int,
        val changedPixelRatio: Float,
        val eventAreaRatio: Float
    )

    private fun computeMetrics(composite: Bitmap, background: Bitmap): QualityMetrics {
        val w = composite.width
        val h = composite.height
        val bounds = findEventBounds(composite, background)
        var changedPixels = 0
        val compRow = IntArray(w)
        val bgRow = IntArray(w)
        for (y in 0 until h) {
            composite.getPixels(compRow, 0, w, 0, y, w, 1)
            background.getPixels(bgRow, 0, w, 0, y, w, 1)
            for (x in 0 until w) {
                if (compRow[x] != bgRow[x]) changedPixels++
            }
        }
        val totalPixels = w * h
        val boundsArea = bounds?.let { it.width() * it.height() } ?: 0
        return QualityMetrics(
            bounds = bounds,
            boundsWidthPx = bounds?.width() ?: 0,
            boundsHeightPx = bounds?.height() ?: 0,
            changedPixelRatio = changedPixels.toFloat() / totalPixels,
            eventAreaRatio = boundsArea.toFloat() / totalPixels
        )
    }

    /** Pixel difference ratio between two bitmaps. */
    private fun imageDifference(a: Bitmap, b: Bitmap): Float {
        if (a.width != b.width || a.height != b.height) return 1f
        val w = a.width
        val h = a.height
        var diffPixels = 0
        val rowA = IntArray(w)
        val rowB = IntArray(w)
        for (y in 0 until h) {
            a.getPixels(rowA, 0, w, 0, y, w, 1)
            b.getPixels(rowB, 0, w, 0, y, w, 1)
            for (x in 0 until w) {
                if (rowA[x] != rowB[x]) diffPixels++
            }
        }
        return diffPixels.toFloat() / (w * h)
    }

    private fun savePng(file: File, bitmap: Bitmap) {
        file.parentFile?.mkdirs()
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun scaleToWidth(bitmap: Bitmap, targetWidth: Int): Bitmap {
        val ratio = targetWidth.toFloat() / bitmap.width
        val targetHeight = (bitmap.height * ratio).toInt()
        return Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true)
    }

    // =========================================================================
    // Multi-crop output
    // =========================================================================

    data class RenderResult(
        val fullScene: Bitmap,
        val mediumCrop: Bitmap?,
        val detailCrop: Bitmap?,
        val metrics: QualityMetrics
    )

    private fun renderWithCrops(
        event: RandomEvent,
        progress: Float,
        state: SimulationState
    ): RenderResult {
        val sceneBg = renderSceneBackground(state)
        val composite = renderEventOnScene(event, progress, state, sceneBg)
        val metrics = computeMetrics(composite, sceneBg)

        val mediumCrop = metrics.bounds?.let { cropWithPadding(composite, it, padFactor = 1.0f) }
        val detailCrop = metrics.bounds?.let { cropWithPadding(composite, it, padFactor = 0.3f) }

        return RenderResult(composite, mediumCrop, detailCrop, metrics)
    }

    private fun saveAllCrops(dir: File, baseName: String, result: RenderResult): String {
        dir.mkdirs()
        savePng(File(dir, "${baseName}_scene.png"), scaleToWidth(result.fullScene, 540))
        result.mediumCrop?.let { savePng(File(dir, "${baseName}_medium.png"), it) }
        result.detailCrop?.let { savePng(File(dir, "${baseName}_detail.png"), it) }

        val m = result.metrics
        return "$baseName: bounds=${m.boundsWidthPx}x${m.boundsHeightPx} " +
            "pixRatio=%.4f areaRatio=%.4f".format(m.changedPixelRatio, m.eventAreaRatio)
    }

    // =========================================================================
    // Contact sheet builder
    // =========================================================================

    private fun buildContactSheet(
        tiles: List<Pair<String, Bitmap>>,
        cols: Int,
        tileWidth: Int = 400,
        tileHeight: Int = 400
    ): Bitmap {
        val rows = (tiles.size + cols - 1) / cols
        val sheet = Bitmap.createBitmap(
            cols * tileWidth, rows * tileHeight, Bitmap.Config.ARGB_8888
        )
        val canvas = Canvas(sheet)
        canvas.drawColor(Color.DKGRAY)

        val labelPaint = Paint().apply {
            color = Color.WHITE; textSize = 18f; isAntiAlias = true
            setShadowLayer(2f, 1f, 1f, Color.BLACK)
        }

        for ((idx, pair) in tiles.withIndex()) {
            val (label, bitmap) = pair
            val col = idx % cols
            val row = idx / cols
            val x = col * tileWidth
            val y = row * tileHeight

            val scaled = Bitmap.createScaledBitmap(bitmap, tileWidth, tileHeight, true)
            canvas.drawBitmap(scaled, x.toFloat(), y.toFloat(), null)
            canvas.drawText(label, x + 6f, y + 22f, labelPaint)
        }
        return sheet
    }

    // =========================================================================
    // TESTS: Full scene time/weather showcase
    // =========================================================================

    @Test
    fun `full scene - time of day variations`() {
        val dir = File(outputDir, "scenes")
        val states = listOf(
            "day" to stateDay, "dawn" to stateDawn,
            "dusk" to stateDusk, "night" to stateNight
        )
        val tiles = mutableListOf<Pair<String, Bitmap>>()
        for ((name, state) in states) {
            val scene = renderSceneBackground(state)
            savePng(File(dir, "scene_${name}.png"), scaleToWidth(scene, 540))
            tiles.add(name to scene)
        }
        val sheet = buildContactSheet(tiles, cols = 4, tileWidth = 270, tileHeight = 480)
        savePng(File(dir, "time_of_day_sheet.png"), sheet)
        println("Scene time-of-day variations saved to: $dir")
    }

    @Test
    fun `full scene - weather variations`() {
        val dir = File(outputDir, "scenes")
        val states = listOf(
            "clear" to stateDay, "overcast" to stateOvercast,
            "rain" to stateRain, "fog" to stateFog, "snow" to stateSnow
        )
        val tiles = mutableListOf<Pair<String, Bitmap>>()
        println("=== WEATHER VARIATION DIAGNOSTICS ===")
        for ((name, state) in states) {
            val scene = renderSceneBackground(state)
            savePng(File(dir, "scene_${name}.png"), scaleToWidth(scene, 540))
            tiles.add(name to scene)
            println("  $name: weather=${state.weather}, file=scene_${name}.png")
        }
        // Compute pixel differences between clear and each weather variant
        val clearBitmap = tiles[0].second
        println("--- Weather vs Clear differences ---")
        for (i in 1 until tiles.size) {
            val (name, bmp) = tiles[i]
            val diff = imageDifference(clearBitmap, bmp)
            val status = if (diff > 0.05f) "DISTINCT" else if (diff > 0.01f) "SUBTLE" else "FAIL_TOO_SIMILAR"
            println("  $name vs clear: %.4f diff → $status".format(diff))
            assert(diff > 0.01f) {
                "$name scene is nearly identical to clear (diff=${"%.4f".format(diff)}). " +
                "Weather effects are not rendering."
            }
        }
        val sheet = buildContactSheet(tiles, cols = 5, tileWidth = 216, tileHeight = 384)
        savePng(File(dir, "weather_sheet.png"), sheet)
        println("Scene weather variations saved to: $dir")
    }

    // =========================================================================
    // TESTS: Ground events in full scene
    // =========================================================================

    @Test
    fun `deer - full scene integration`() {
        val dir = File(outputDir, "deer")
        val diagnostics = mutableListOf<String>()

        for (seed in listOf(7L, 42L, 99L)) {
            val event = DeerEvent(Random(seed), spawnTimeMs = baseTime)
            val result = renderWithCrops(event, 0.25f, stateDay)
            diagnostics.add(saveAllCrops(dir, "walk_seed${seed}_day", result))
        }

        val grazeDeer = DeerEvent(Random(7), spawnTimeMs = baseTime)
        val grazeResult = renderWithCrops(grazeDeer, 0.40f, stateDay)
        diagnostics.add(saveAllCrops(dir, "graze_day", grazeResult))

        for ((name, state) in listOf("dawn" to stateDawn, "dusk" to stateDusk, "night" to stateNight)) {
            val event = DeerEvent(Random(7), spawnTimeMs = baseTime)
            val result = renderWithCrops(event, 0.25f, state)
            diagnostics.add(saveAllCrops(dir, "walk_${name}", result))
        }

        // Fawn
        for (seed in 1L..200L) {
            val fawn = DeerEvent(Random(seed), spawnTimeMs = baseTime)
            val sceneBg = renderSceneBackground(stateDay)
            val composite = renderEventOnScene(fawn, 0.3f, stateDay, sceneBg)
            val metrics = computeMetrics(composite, sceneBg)
            if (metrics.boundsHeightPx in 20..120) {
                val result = RenderResult(
                    composite,
                    metrics.bounds?.let { cropWithPadding(composite, it, 1.0f) },
                    metrics.bounds?.let { cropWithPadding(composite, it, 0.3f) },
                    metrics
                )
                diagnostics.add(saveAllCrops(dir, "fawn_day", result))
                break
            }
        }

        println("=== DEER DIAGNOSTICS ===")
        diagnostics.forEach { println(it) }
    }

    @Test
    fun `fox - full scene integration`() {
        val dir = File(outputDir, "fox")
        val diagnostics = mutableListOf<String>()

        for (seed in listOf(42L, 13L, 77L)) {
            val event = FoxEvent(Random(seed), spawnTimeMs = baseTime)
            val result = renderWithCrops(event, 0.35f, stateDay)
            diagnostics.add(saveAllCrops(dir, "trot_seed${seed}_day", result))
        }

        for ((name, state) in listOf("dawn" to stateDawn, "night" to stateNight)) {
            val event = FoxEvent(Random(42), spawnTimeMs = baseTime)
            val result = renderWithCrops(event, 0.35f, state)
            diagnostics.add(saveAllCrops(dir, "trot_${name}", result))
        }

        println("=== FOX DIAGNOSTICS ===")
        diagnostics.forEach { println(it) }
    }

    @Test
    fun `rabbit - full scene integration`() {
        val dir = File(outputDir, "rabbit")
        val diagnostics = mutableListOf<String>()

        for (seed in listOf(42L, 7L, 55L)) {
            val event = RabbitEvent(Random(seed), spawnTimeMs = baseTime)
            val result = renderWithCrops(event, 0.4f, stateDay)
            diagnostics.add(saveAllCrops(dir, "hop_seed${seed}_day", result))
        }
        val event = RabbitEvent(Random(42), spawnTimeMs = baseTime)
        val result = renderWithCrops(event, 0.4f, stateNight)
        diagnostics.add(saveAllCrops(dir, "hop_night", result))

        println("=== RABBIT DIAGNOSTICS ===")
        diagnostics.forEach { println(it) }
    }

    @Test
    fun `squirrel - full scene integration`() {
        val dir = File(outputDir, "squirrel")
        val diagnostics = mutableListOf<String>()

        for (seed in listOf(42L, 7L, 55L)) {
            val event = SquirrelEvent(Random(seed), spawnTimeMs = baseTime)
            val result = renderWithCrops(event, 0.4f, stateDay)
            diagnostics.add(saveAllCrops(dir, "run_seed${seed}_day", result))
        }
        val event = SquirrelEvent(Random(42), spawnTimeMs = baseTime)
        val result = renderWithCrops(event, 0.4f, stateDusk)
        diagnostics.add(saveAllCrops(dir, "run_dusk", result))

        println("=== SQUIRREL DIAGNOSTICS ===")
        diagnostics.forEach { println(it) }
    }

    // =========================================================================
    // TESTS: Sky events in full scene
    // =========================================================================

    @Test
    fun `eagle - full scene integration`() {
        val dir = File(outputDir, "eagle")
        val diagnostics = mutableListOf<String>()
        for ((name, state) in listOf("day" to stateDay, "dusk" to stateDusk)) {
            val event = EagleEvent(Random(42), spawnTimeMs = baseTime)
            val result = renderWithCrops(event, 0.5f, state)
            diagnostics.add(saveAllCrops(dir, "soar_${name}", result))
        }
        println("=== EAGLE DIAGNOSTICS ===")
        diagnostics.forEach { println(it) }
    }

    @Test
    fun `butterfly - full scene integration`() {
        val dir = File(outputDir, "butterfly")
        val diagnostics = mutableListOf<String>()

        // Multiple flutter phases to verify dot placement
        for ((idx, progress) in listOf(0.25f, 0.4f, 0.55f, 0.7f).withIndex()) {
            val event = ButterflyEvent(Random(42), spawnTimeMs = baseTime)
            val result = renderWithCrops(event, progress, stateDay)
            diagnostics.add(saveAllCrops(dir, "flutter_phase${idx}_day", result))
        }

        // Different seeds for color variation
        for (seed in listOf(7L, 42L, 77L, 123L)) {
            val event = ButterflyEvent(Random(seed), spawnTimeMs = baseTime)
            val result = renderWithCrops(event, 0.5f, stateDay)
            diagnostics.add(saveAllCrops(dir, "color_seed${seed}", result))
        }

        // Dawn (ambient tint)
        val dawnEvent = ButterflyEvent(Random(42), spawnTimeMs = baseTime)
        val dawnResult = renderWithCrops(dawnEvent, 0.5f, stateDawn)
        diagnostics.add(saveAllCrops(dir, "flutter_dawn", dawnResult))

        println("=== BUTTERFLY DIAGNOSTICS ===")
        diagnostics.forEach { println(it) }
    }

    @Test
    fun `bird flock - full scene integration`() {
        val dir = File(outputDir, "birdflock")
        val diagnostics = mutableListOf<String>()
        for ((name, state) in listOf("day" to stateDay, "dusk" to stateDusk)) {
            val event = BirdFlockEvent(Random(42), spawnTimeMs = baseTime)
            val result = renderWithCrops(event, 0.5f, state)
            diagnostics.add(saveAllCrops(dir, "flock_${name}", result))
        }
        println("=== BIRD FLOCK DIAGNOSTICS ===")
        diagnostics.forEach { println(it) }
    }

    @Test
    fun `balloon - full scene integration`() {
        val dir = File(outputDir, "balloon")
        val diagnostics = mutableListOf<String>()
        for ((name, state) in listOf("day" to stateDay, "dawn" to stateDawn)) {
            val event = HotAirBalloonEvent(Random(42), spawnTimeMs = baseTime)
            val result = renderWithCrops(event, 0.5f, state)
            diagnostics.add(saveAllCrops(dir, "float_${name}", result))
        }
        println("=== BALLOON DIAGNOSTICS ===")
        diagnostics.forEach { println(it) }
    }

    @Test
    fun `owl - full scene night`() {
        val dir = File(outputDir, "owl")
        val diagnostics = mutableListOf<String>()
        for ((idx, progress) in listOf(0.3f, 0.5f, 0.6f).withIndex()) {
            val event = OwlEvent(Random(42), spawnTimeMs = baseTime)
            val result = renderWithCrops(event, progress, stateNight)
            diagnostics.add(saveAllCrops(dir, "perch_pose${idx}", result))
        }
        println("=== OWL DIAGNOSTICS ===")
        diagnostics.forEach { println(it) }
    }

    @Test
    fun `owl - flap phase verification`() {
        val dir = File(outputDir, "owl")
        // Replicate Random(42) sequence to derive OwlEvent's animation params:
        val rng = Random(42)
        val distance = 0.2f + rng.nextFloat() * 0.4f
        @Suppress("UNUSED_VARIABLE") val sizeScale = 1.0f - distance * 0.5f
        @Suppress("UNUSED_VARIABLE") val y = 0.18f + rng.nextFloat() * 0.12f
        val speed = 0.000025f + rng.nextFloat() * 0.000015f
        val durationMs = (1.35f / speed).toLong()
        val flapInterval = 1.8f + rng.nextFloat() * 0.8f
        val flapDuration = 0.45f + rng.nextFloat() * 0.2f
        @Suppress("UNUSED_VARIABLE") val flapRate = 2.4f + rng.nextFloat() * 0.8f
        val wingPhase = rng.nextFloat() * 6.28f

        // Find a progress where envelope is maximized (cycleT near 0):
        // localTime = durationMs * progress / 1000
        // cycleT = (localTime + wingPhase) % flapInterval
        // envelope is max when cycleT ≈ 0 → localTime + wingPhase ≈ N * flapInterval
        // → localTime ≈ N * flapInterval - wingPhase
        var bestProgress = 0.4f
        var bestEnvelope = 0f
        for (step in 10..90) {
            val p = step / 100f
            val localTime = durationMs * p / 1000f
            val cycleT = (localTime + wingPhase) % flapInterval
            val burst01 = (1f - (cycleT / flapDuration)).coerceIn(0f, 1f)
            val t = burst01.coerceIn(0f, 1f)
            val envelope = t * t * (3f - 2f * t)
            if (envelope > bestEnvelope) {
                bestEnvelope = envelope
                bestProgress = p
            }
        }

        println("=== OWL FLAP VERIFICATION ===")
        println("Derived params: flapInterval=$flapInterval flapDuration=$flapDuration wingPhase=$wingPhase")
        println("Best flap progress=$bestProgress envelope=$bestEnvelope durationMs=$durationMs")

        val event = OwlEvent(Random(42), spawnTimeMs = baseTime)
        val result = renderWithCrops(event, bestProgress, stateNight)
        val diag = saveAllCrops(dir, "flap_burst", result)
        println("Flap burst: $diag")

        // Also render a known glide for comparison
        val glideResult = renderWithCrops(event, 0.5f, stateNight)
        val glideDiag = saveAllCrops(dir, "glide_compare", glideResult)
        println("Glide compare: $glideDiag")

        // Assert flap pose has non-zero bounds
        assert(result.metrics.boundsWidthPx > 0 && result.metrics.boundsHeightPx > 0) {
            "Flap burst has zero bounds!"
        }
        println("Flap bounds: ${result.metrics.boundsWidthPx}x${result.metrics.boundsHeightPx}")
        println("Glide bounds: ${glideResult.metrics.boundsWidthPx}x${glideResult.metrics.boundsHeightPx}")
    }

    @Test
    fun `bat - full scene night`() {
        val dir = File(outputDir, "bat")
        val diagnostics = mutableListOf<String>()
        for ((idx, progress) in listOf(0.3f, 0.5f, 0.7f).withIndex()) {
            val event = BatEvent(Random(42), spawnTimeMs = baseTime)
            val result = renderWithCrops(event, progress, stateNight)
            diagnostics.add(saveAllCrops(dir, "fly_pose${idx}", result))
        }
        println("=== BAT DIAGNOSTICS ===")
        diagnostics.forEach { println(it) }
    }

    // =========================================================================
    // TESTS: Contact sheets
    // =========================================================================

    @Test
    fun `contact sheet - all ground events x time`() {
        val dir = File(outputDir, "contact_sheets")
        val events = listOf(
            "Deer" to { DeerEvent(Random(7), spawnTimeMs = baseTime) },
            "Fox" to { FoxEvent(Random(42), spawnTimeMs = baseTime) },
            "Rabbit" to { RabbitEvent(Random(42), spawnTimeMs = baseTime) },
            "Squirrel" to { SquirrelEvent(Random(42), spawnTimeMs = baseTime) }
        )
        val states = listOf(
            "Day" to stateDay, "Dawn" to stateDawn,
            "Dusk" to stateDusk, "Night" to stateNight
        )

        val tiles = mutableListOf<Pair<String, Bitmap>>()
        for ((stateName, state) in states) {
            for ((eventName, factory) in events) {
                val event = factory()
                val result = renderWithCrops(event, 0.30f, state)
                val tile = result.mediumCrop ?: result.fullScene
                tiles.add("$eventName $stateName" to tile)
            }
        }
        val sheet = buildContactSheet(tiles, cols = 4)
        savePng(File(dir, "ground_events_x_time.png"), sheet)
        println("Ground events x time contact sheet saved")
    }

    @Test
    fun `contact sheet - all sky events x time`() {
        val dir = File(outputDir, "contact_sheets")
        val events = listOf(
            "Eagle" to { EagleEvent(Random(42), spawnTimeMs = baseTime) as RandomEvent },
            "BirdFlock" to { BirdFlockEvent(Random(42), spawnTimeMs = baseTime) as RandomEvent },
            "Butterfly" to { ButterflyEvent(Random(42), spawnTimeMs = baseTime) as RandomEvent },
            "Balloon" to { HotAirBalloonEvent(Random(42), spawnTimeMs = baseTime) as RandomEvent }
        )
        val states = listOf("Day" to stateDay, "Dusk" to stateDusk, "Night" to stateNight)

        val tiles = mutableListOf<Pair<String, Bitmap>>()
        for ((stateName, state) in states) {
            for ((eventName, factory) in events) {
                val event = factory()
                val result = renderWithCrops(event, 0.5f, state)
                val tile = result.mediumCrop ?: result.fullScene
                tiles.add("$eventName $stateName" to tile)
            }
        }
        val sheet = buildContactSheet(tiles, cols = 4)
        savePng(File(dir, "sky_events_x_time.png"), sheet)
        println("Sky events x time contact sheet saved")
    }

    @Test
    fun `contact sheet - deer seed variation`() {
        val dir = File(outputDir, "contact_sheets")
        val seeds = listOf(7L, 13L, 29L, 42L, 55L, 77L, 99L, 123L)

        val tiles = mutableListOf<Pair<String, Bitmap>>()
        val bitmaps = mutableListOf<Bitmap>()
        for (seed in seeds) {
            val event = DeerEvent(Random(seed), spawnTimeMs = baseTime)
            val result = renderWithCrops(event, 0.25f, stateDay)
            val tile = result.mediumCrop ?: result.fullScene
            tiles.add("Seed $seed" to tile)
            bitmaps.add(result.fullScene)
        }

        val diffs = mutableListOf<String>()
        for (i in 0 until bitmaps.size - 1) {
            val diff = imageDifference(bitmaps[i], bitmaps[i + 1])
            diffs.add("seed${seeds[i]} vs seed${seeds[i+1]}: %.4f diff".format(diff))
        }

        val sheet = buildContactSheet(tiles, cols = 4)
        savePng(File(dir, "deer_seed_variation.png"), sheet)
        println("=== DEER SEED VARIATION ===")
        diffs.forEach { println(it) }
    }

    @Test
    fun `contact sheet - butterfly detail (dot placement)`() {
        val dir = File(outputDir, "contact_sheets")
        val tiles = mutableListOf<Pair<String, Bitmap>>()

        for (i in 0..7) {
            val progress = 0.2f + i * 0.08f
            val event = ButterflyEvent(Random(42), spawnTimeMs = baseTime)
            val result = renderWithCrops(event, progress, stateDay)
            val tile = result.detailCrop ?: result.mediumCrop ?: result.fullScene
            tiles.add("p=${"%.2f".format(progress)}" to tile)
        }

        val sheet = buildContactSheet(tiles, cols = 4)
        savePng(File(dir, "butterfly_dot_phases.png"), sheet)
        println("Butterfly dot placement contact sheet saved")
    }

    @Test
    fun `contact sheet - weather impact on ground events`() {
        val dir = File(outputDir, "contact_sheets")
        val weatherStates = listOf(
            "Clear" to stateDay, "Overcast" to stateOvercast,
            "Rain" to stateRain, "Fog" to stateFog, "Snow" to stateSnow
        )

        val tiles = mutableListOf<Pair<String, Bitmap>>()
        for ((weatherName, state) in weatherStates) {
            val event = DeerEvent(Random(7), spawnTimeMs = baseTime)
            val result = renderWithCrops(event, 0.25f, state)
            val tile = result.mediumCrop ?: result.fullScene
            tiles.add("Deer $weatherName" to tile)
        }
        for ((weatherName, state) in weatherStates) {
            val event = FoxEvent(Random(42), spawnTimeMs = baseTime)
            val result = renderWithCrops(event, 0.35f, state)
            val tile = result.mediumCrop ?: result.fullScene
            tiles.add("Fox $weatherName" to tile)
        }

        val sheet = buildContactSheet(tiles, cols = 5)
        savePng(File(dir, "weather_impact_ground.png"), sheet)
        println("Weather impact on ground events contact sheet saved")
    }

    // =========================================================================
    // TESTS: Quality validation
    // =========================================================================

    @Test
    fun `quality validation - all events visible and sized`() {
        data class EventCheck(
            val name: String,
            val event: RandomEvent,
            val progress: Float,
            val state: SimulationState,
            val minBoundsWidth: Int = 15,
            val minBoundsHeight: Int = 15
        )

        val checks = listOf(
            EventCheck("Deer(walk)", DeerEvent(Random(7), baseTime), 0.25f, stateDay, 60, 60),
            EventCheck("Deer(graze)", DeerEvent(Random(7), baseTime), 0.40f, stateDay, 60, 40),
            EventCheck("Fox", FoxEvent(Random(42), baseTime), 0.35f, stateDay, 40, 20),
            EventCheck("Rabbit", RabbitEvent(Random(42), baseTime), 0.4f, stateDay, 20, 15),
            EventCheck("Squirrel", SquirrelEvent(Random(42), baseTime), 0.4f, stateDay, 20, 20),
            EventCheck("Eagle", EagleEvent(Random(42), baseTime), 0.5f, stateDay, 40, 20),
            EventCheck("BirdFlock", BirdFlockEvent(Random(42), baseTime), 0.5f, stateDay, 30, 15),
            EventCheck("Butterfly", ButterflyEvent(Random(42), baseTime), 0.5f, stateDay, 20, 20),
            EventCheck("Balloon", HotAirBalloonEvent(Random(42), baseTime), 0.5f, stateDay, 30, 40),
            EventCheck("Owl", OwlEvent(Random(42), baseTime), 0.5f, stateNight, 25, 25),
            EventCheck("Bat", BatEvent(Random(42), baseTime), 0.5f, stateNight, 15, 10)
        )

        val diagnosticDir = File(outputDir, "diagnostics")
        println("=== QUALITY VALIDATION ===")
        println("%-14s %-12s %-10s %-10s %s".format(
            "Event", "Bounds", "MinExpect", "PixRatio", "Status"))
        println("-".repeat(70))

        var allPass = true
        for (check in checks) {
            val sceneBg = renderSceneBackground(check.state)
            val composite = renderEventOnScene(check.event, check.progress, check.state, sceneBg)
            val metrics = computeMetrics(composite, sceneBg)

            val widthOk = metrics.boundsWidthPx >= check.minBoundsWidth
            val heightOk = metrics.boundsHeightPx >= check.minBoundsHeight
            val visible = metrics.changedPixelRatio > 0.0001f
            val pass = widthOk && heightOk && visible

            val status = if (pass) "PASS" else "FAIL"
            val notes = buildString {
                if (!visible) append("INVISIBLE ")
                if (!widthOk) append("W<${check.minBoundsWidth} ")
                if (!heightOk) append("H<${check.minBoundsHeight} ")
            }

            println("%-14s %4dx%-4d    %4dx%-4d    %.5f  %-6s %s".format(
                check.name,
                metrics.boundsWidthPx, metrics.boundsHeightPx,
                check.minBoundsWidth, check.minBoundsHeight,
                metrics.changedPixelRatio, status, notes
            ))

            if (!pass) {
                allPass = false
                savePng(File(diagnosticDir, "${check.name}_FAIL.png"),
                    scaleToWidth(composite, 540))
            }
        }
        println("-".repeat(70))
        assert(allPass) { "Some events failed visibility/size checks" }
    }

    @Test
    fun `quality validation - seed variation produces distinct images`() {
        val seeds = listOf(7L, 13L, 29L, 42L, 55L, 77L, 99L, 123L)

        println("=== SEED VARIATION DISTINCTNESS ===")
        println("%-10s %-8s %-12s %s".format("Event", "Seeds", "AvgDiff", "Status"))
        println("-".repeat(50))

        data class VarCheck(val name: String, val factory: (Long) -> RandomEvent, val progress: Float)
        val checks = listOf(
            VarCheck("Deer", { DeerEvent(Random(it), baseTime) }, 0.25f),
            VarCheck("Fox", { FoxEvent(Random(it), baseTime) }, 0.35f),
            VarCheck("Rabbit", { RabbitEvent(Random(it), baseTime) }, 0.4f),
            VarCheck("Squirrel", { SquirrelEvent(Random(it), baseTime) }, 0.4f)
        )

        for (check in checks) {
            val sceneBg = renderSceneBackground(stateDay)
            val renders = seeds.map { seed ->
                val event = check.factory(seed)
                renderEventOnScene(event, check.progress, stateDay, sceneBg)
            }

            val diffs = (0 until renders.size - 1).map { i ->
                imageDifference(renders[i], renders[i + 1])
            }
            val avgDiff = diffs.average()
            val status = if (avgDiff > 0.001f) "PASS" else "WARN_SIMILAR"
            println("%-10s %d        %.6f     %s".format(check.name, seeds.size, avgDiff, status))
        }
    }

    // =========================================================================
    // TESTS: Full wallpaper preview scenes
    // =========================================================================

    @Test
    fun `wallpaper preview - day with deer and butterfly`() {
        val dir = File(outputDir, "previews")
        val sceneBg = renderSceneBackground(stateDay)
        val bitmap = sceneBg.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(bitmap)

        val deer = DeerEvent(Random(7), spawnTimeMs = baseTime)
        drawEventOnCanvas(canvas, deer, 0.25f, stateDay)

        val butterfly = ButterflyEvent(Random(42), spawnTimeMs = baseTime)
        drawEventOnCanvas(canvas, butterfly, 0.5f, stateDay)

        savePng(File(dir, "preview_day_deer_butterfly.png"), scaleToWidth(bitmap, 540))
        println("Day preview with deer + butterfly saved")
    }

    @Test
    fun `wallpaper preview - dusk with fox and eagle`() {
        val dir = File(outputDir, "previews")
        val sceneBg = renderSceneBackground(stateDusk)
        val bitmap = sceneBg.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(bitmap)

        val fox = FoxEvent(Random(42), spawnTimeMs = baseTime)
        drawEventOnCanvas(canvas, fox, 0.35f, stateDusk)

        val eagle = EagleEvent(Random(7), spawnTimeMs = baseTime)
        drawEventOnCanvas(canvas, eagle, 0.5f, stateDusk)

        savePng(File(dir, "preview_dusk_fox_eagle.png"), scaleToWidth(bitmap, 540))
        println("Dusk preview with fox + eagle saved")
    }

    @Test
    fun `wallpaper preview - night with owl and bat`() {
        val dir = File(outputDir, "previews")
        val sceneBg = renderSceneBackground(stateNight)
        val bitmap = sceneBg.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(bitmap)

        val owl = OwlEvent(Random(42), spawnTimeMs = baseTime)
        drawEventOnCanvas(canvas, owl, 0.5f, stateNight)

        val bat = BatEvent(Random(7), spawnTimeMs = baseTime)
        drawEventOnCanvas(canvas, bat, 0.4f, stateNight)

        savePng(File(dir, "preview_night_owl_bat.png"), scaleToWidth(bitmap, 540))
        println("Night preview with owl + bat saved")
    }

    @Test
    fun `wallpaper preview - rain with rabbit`() {
        val dir = File(outputDir, "previews")
        val sceneBg = renderSceneBackground(stateRain)
        val bitmap = sceneBg.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(bitmap)

        val rabbit = RabbitEvent(Random(42), spawnTimeMs = baseTime)
        drawEventOnCanvas(canvas, rabbit, 0.4f, stateRain)

        savePng(File(dir, "preview_rain_rabbit.png"), scaleToWidth(bitmap, 540))
        println("Rain preview with rabbit saved")
    }

    @Test
    fun `wallpaper preview - snow with squirrel`() {
        val dir = File(outputDir, "previews")
        val sceneBg = renderSceneBackground(stateSnow)
        val bitmap = sceneBg.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(bitmap)

        val squirrel = SquirrelEvent(Random(42), spawnTimeMs = baseTime)
        drawEventOnCanvas(canvas, squirrel, 0.4f, stateSnow)

        savePng(File(dir, "preview_snow_squirrel.png"), scaleToWidth(bitmap, 540))
        println("Snow preview with squirrel saved")
    }
}
