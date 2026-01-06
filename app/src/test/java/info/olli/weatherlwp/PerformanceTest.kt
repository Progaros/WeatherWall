package info.olli.weatherlwp

import info.olli.weatherlwp.parallax.ParallaxCalculator
import info.olli.weatherlwp.rendering.RenderConstants
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import kotlin.math.sin
import kotlin.math.cos
import kotlin.math.sqrt

/**
 * Performance benchmark tests for profiling with async-profiler.
 * 
 * These tests run expensive calculations in loops for 10-20 seconds,
 * allowing profilers to capture meaningful data.
 * 
 * Usage with async-profiler:
 * 1. Start test in background:
 *    ./gradlew :app:testDebugUnitTest --tests "PerformanceTest.*" &
 *    PID=$!
 * 2. Wait for Gradle to start worker:
 *    sleep 5
 *    TEST_PID=$(jps | grep GradleWorker | cut -d ' ' -f 1)
 * 3. Profile:
 *    /opt/async-profiler/profiler.sh -d 10 -f /tmp/profile.html $TEST_PID
 * 
 * @see ai.md for full profiling workflow
 */
class PerformanceTest {

    /**
     * Isolated frame calculation logic that mimics heavy wallpaper computations.
     * Pure Kotlin, no Android dependencies.
     */
    class WallpaperEngine {
        private var timeSeconds = 0f
        private var windStrength = 0.5f
        private var gustPhase = 0f
        private var gustIntensity = 0f
        private var targetGust = 0f
        private var smoothedGust = 0f
        
        /**
         * Simulates the heavy per-frame physics calculations.
         */
        fun calculateFrame(deltaMs: Long): FrameResult {
            val deltaSec = deltaMs / 1000f
            timeSeconds += deltaSec
            
            // Wind gust simulation (from SimulationController)
            val windGust = calculateWindGust(deltaSec)
            
            // Parallax calculations (multiple depth layers)
            val parallaxOffsets = calculateParallaxOffsets()
            
            // Tree sway calculations (sin-based)
            val treeSwayAngles = calculateTreeSway()
            
            // Hill noise generation (procedural)
            val hillHeights = calculateHillNoise()
            
            // Color interpolation (time-of-day)
            val skyColors = calculateSkyColors()
            
            return FrameResult(
                windGust = windGust,
                parallaxOffsets = parallaxOffsets,
                treeSwayAngles = treeSwayAngles,
                hillHeights = hillHeights,
                skyColors = skyColors
            )
        }
        
        /**
         * Wind gust calculation - smooth ramping with decay.
         */
        private fun calculateWindGust(deltaSec: Float): Float {
            // No gusts in calm conditions
            if (windStrength < 0.15f) {
                targetGust = 0f
                smoothedGust = (smoothedGust - deltaSec * 0.5f).coerceAtLeast(0f)
                gustIntensity = smoothedGust
                return smoothedGust
            }
            
            // Decay target gust intensity
            val gustDecay = 0.3f + windStrength * 0.5f
            targetGust = (targetGust - gustDecay * deltaSec).coerceIn(0f, 1f)
            
            // Random gust triggers (simplified - use timeSeconds as pseudo-random seed)
            if ((timeSeconds * 1000).toInt() % 2000 < 16) {
                val minGust = windStrength * 0.5f
                val maxGust = windStrength * 0.3f + windStrength * 0.7f
                targetGust = minGust + (sin(timeSeconds * 7.3f) * 0.5f + 0.5f) * (maxGust - minGust)
            }
            
            // Smooth transition
            val rampSpeed = 2.5f + windStrength * 2.0f
            if (smoothedGust < targetGust) {
                smoothedGust = (smoothedGust + deltaSec * rampSpeed).coerceAtMost(targetGust)
            } else {
                smoothedGust = (smoothedGust - deltaSec * gustDecay).coerceAtLeast(targetGust)
            }
            
            gustIntensity = smoothedGust
            return gustIntensity
        }
        
        /**
         * Parallax offset calculation for multiple depth layers.
         * Simulates what ParallaxCalculator.update() does internally.
         */
        private fun calculateParallaxOffsets(): FloatArray {
            val depths = floatArrayOf(0.1f, 0.2f, 0.35f, 0.5f, 0.65f, 0.8f, 0.95f)
            val offsets = FloatArray(depths.size * 2)
            
            // Simulate sensor tilt input
            val tiltX = sin(timeSeconds * 0.7f) * 0.3f
            val tiltY = cos(timeSeconds * 0.5f) * 0.2f
            
            val strength = 2.5f
            val maxOffset = 130f
            
            for (i in depths.indices) {
                val depth = depths[i]
                
                // Calculate raw target
                val targetX = tiltX * maxOffset * strength
                val targetY = tiltY * maxOffset * strength
                
                // Circular clamping
                val magnitude = sqrt(targetX * targetX + targetY * targetY)
                val clampedX: Float
                val clampedY: Float
                if (magnitude > maxOffset) {
                    clampedX = targetX * maxOffset / magnitude
                    clampedY = targetY * maxOffset / magnitude
                } else {
                    clampedX = targetX
                    clampedY = targetY
                }
                
                // Apply depth multiplier (deeper layers move less)
                val depthMult = 0.3f + depth * 0.7f
                offsets[i * 2] = clampedX * depthMult
                offsets[i * 2 + 1] = clampedY * depthMult
            }
            
            return offsets
        }
        
        /**
         * Tree sway angle calculation - sin-based wind animation.
         * Simulates multiple trees at different positions.
         */
        private fun calculateTreeSway(): FloatArray {
            val treeCount = 100
            val swayAngles = FloatArray(treeCount)
            
            val baseAmplitude = 0.15f * windStrength
            val gustBoost = gustIntensity * 0.3f
            val totalAmplitude = baseAmplitude + gustBoost
            
            for (i in 0 until treeCount) {
                // Each tree has slightly different phase based on position
                val treePhase = i * 0.31f + timeSeconds * (1.5f + windStrength * 0.5f)
                
                // Multi-frequency oscillation for natural movement
                val primaryWave = sin(treePhase) * totalAmplitude
                val secondaryWave = sin(treePhase * 2.3f) * totalAmplitude * 0.3f
                val tertiaryWave = sin(treePhase * 4.7f) * totalAmplitude * 0.1f
                
                swayAngles[i] = primaryWave + secondaryWave + tertiaryWave
            }
            
            return swayAngles
        }
        
        /**
         * Procedural hill noise generation.
         * Simulates TerrainLayer's noise-based hill shapes.
         */
        private fun calculateHillNoise(): FloatArray {
            val hillCount = 5
            val pointsPerHill = 50
            val heights = FloatArray(hillCount * pointsPerHill)
            
            for (h in 0 until hillCount) {
                val seed = 42 + h * 1000
                val baseHeight = 0.5f + h * 0.07f
                
                for (p in 0 until pointsPerHill) {
                    val x = p.toFloat() / pointsPerHill
                    
                    // Multi-octave noise (simplified value noise)
                    var noiseSum = 0f
                    var amplitude = 0.1f
                    var frequency = 2f
                    
                    for (octave in 0 until 4) {
                        val noiseX = x * frequency + seed
                        val noiseVal = hash((noiseX * 1000).toInt()) * 2f - 1f
                        noiseSum += noiseVal * amplitude
                        
                        amplitude *= 0.5f
                        frequency *= 2f
                    }
                    
                    // Add wind-based animation
                    val windOffset = sin(x * 3.14159f + timeSeconds * windStrength) * 0.02f
                    
                    heights[h * pointsPerHill + p] = baseHeight + noiseSum + windOffset
                }
            }
            
            return heights
        }
        
        /**
         * Sky color interpolation based on time of day.
         * Simulates SkyLayer's color gradient calculations.
         */
        private fun calculateSkyColors(): IntArray {
            val colorCount = 10 // Gradient stops
            val colors = IntArray(colorCount)
            
            // Simulate hour of day cycling
            val hour = (timeSeconds / 60f) % 24f
            
            for (i in 0 until colorCount) {
                val position = i.toFloat() / colorCount
                
                // Day colors (simplified)
                val dayR = (135 + position * 70).toInt().coerceIn(0, 255)
                val dayG = (206 + position * 30).toInt().coerceIn(0, 255)
                val dayB = 235
                
                // Night colors
                val nightR = (10 + position * 20).toInt().coerceIn(0, 255)
                val nightG = (15 + position * 25).toInt().coerceIn(0, 255)
                val nightB = (30 + position * 40).toInt().coerceIn(0, 255)
                
                // Blend based on hour
                val dayBlend = when {
                    hour < 6 -> 0f
                    hour < 8 -> (hour - 6) / 2f
                    hour < 18 -> 1f
                    hour < 20 -> 1f - (hour - 18) / 2f
                    else -> 0f
                }
                
                val r = (nightR + (dayR - nightR) * dayBlend).toInt()
                val g = (nightG + (dayG - nightG) * dayBlend).toInt()
                val b = (nightB + (dayB - nightB) * dayBlend).toInt()
                
                colors[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
            
            return colors
        }
        
        /**
         * Hash function for procedural noise (from TerrainLayer).
         */
        private fun hash(n: Int): Float {
            var x = n
            x = (x shr 13) xor x
            x = (x * (x * x * 15731 + 789221) + 1376312589)
            return (x and 0x7fffffff) / 2147483647.0f
        }
    }
    
    /**
     * Result of a single frame calculation.
     */
    data class FrameResult(
        val windGust: Float,
        val parallaxOffsets: FloatArray,
        val treeSwayAngles: FloatArray,
        val hillHeights: FloatArray,
        val skyColors: IntArray
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as FrameResult
            return windGust == other.windGust &&
                    parallaxOffsets.contentEquals(other.parallaxOffsets) &&
                    treeSwayAngles.contentEquals(other.treeSwayAngles) &&
                    hillHeights.contentEquals(other.hillHeights) &&
                    skyColors.contentEquals(other.skyColors)
        }

        override fun hashCode(): Int {
            var result = windGust.hashCode()
            result = 31 * result + parallaxOffsets.contentHashCode()
            result = 31 * result + treeSwayAngles.contentHashCode()
            result = 31 * result + hillHeights.contentHashCode()
            result = 31 * result + skyColors.contentHashCode()
            return result
        }
    }

    // Test instances
    private lateinit var engine: WallpaperEngine
    private lateinit var parallaxCalculator: ParallaxCalculator

    @Before
    fun setUp() {
        engine = WallpaperEngine()
        parallaxCalculator = ParallaxCalculator()
        parallaxCalculator.setCanvasSize(1080f, 1920f)
    }

    // ============================================================
    // PROFILING BENCHMARKS
    // Run these with async-profiler attached for flamegraph analysis
    // ============================================================

    /**
     * Main benchmark: exercises all heavy calculations for 20 seconds.
     * This is the primary test to profile with async-profiler.
     */
    @Test(timeout = 30000)
    fun benchmarkFullFrameCalculation() {
        var frameCount = 0L
        val startTime = System.currentTimeMillis()
        val endTime = startTime + 20_000  // 20 seconds
        
        println("Starting full frame benchmark for 20 seconds...")
        
        while (System.currentTimeMillis() < endTime) {
            // Simulate 60fps frame timing
            engine.calculateFrame(16)
            frameCount++
        }
        
        val elapsed = System.currentTimeMillis() - startTime
        val fps = frameCount * 1000.0 / elapsed
        
        println("Benchmark complete:")
        println("  Frames: $frameCount")
        println("  Time: ${elapsed}ms")
        println("  FPS: %.1f".format(fps))
        
        // Verify we maintained reasonable performance
        assertTrue("Should achieve at least 1000 FPS in pure calculation", fps >= 1000)
    }

    /**
     * Focused benchmark: parallax calculations only.
     * Tests ParallaxCalculator's update loop.
     */
    @Test(timeout = 25000)
    fun benchmarkParallaxCalculations() {
        var iterations = 0L
        val startTime = System.currentTimeMillis()
        val endTime = startTime + 10_000  // 10 seconds
        
        println("Starting parallax benchmark for 10 seconds...")
        
        while (System.currentTimeMillis() < endTime) {
            // Simulate sensor updates
            val tiltX = sin(iterations * 0.001f) * 0.5f
            val tiltY = cos(iterations * 0.001f) * 0.3f
            parallaxCalculator.updateFromSensor(tiltX, tiltY)
            
            // Run frame update
            parallaxCalculator.update(16)
            
            // Get offsets for multiple depth layers
            for (depth in listOf(0.15f, 0.35f, 0.5f, 0.65f, 0.8f)) {
                parallaxCalculator.getOffsetForDepth(depth)
            }
            
            iterations++
        }
        
        val elapsed = System.currentTimeMillis() - startTime
        val ips = iterations * 1000.0 / elapsed
        
        println("Parallax benchmark complete:")
        println("  Iterations: $iterations")
        println("  Time: ${elapsed}ms")
        println("  Iterations/sec: %.0f".format(ips))
        
        assertTrue("Should achieve high iteration rate", ips >= 10000)
    }

    /**
     * Focused benchmark: wind/tree sway calculations.
     * Tests sin-based animation math.
     */
    @Test(timeout = 25000)
    fun benchmarkWindAnimations() {
        var iterations = 0L
        val startTime = System.currentTimeMillis()
        val endTime = startTime + 10_000  // 10 seconds
        
        println("Starting wind animation benchmark for 10 seconds...")
        
        var timeSeconds = 0f
        val windStrength = 0.6f
        val treeCount = 200
        
        while (System.currentTimeMillis() < endTime) {
            timeSeconds += 0.016f
            
            // Calculate tree sway for many trees (like TerrainLayer does)
            for (i in 0 until treeCount) {
                val treePhase = i * 0.31f + timeSeconds * (1.5f + windStrength * 0.5f)
                
                // Multi-frequency sway calculation
                val primaryWave = sin(treePhase) * 0.2f
                val secondaryWave = sin(treePhase * 2.3f) * 0.06f
                val tertiaryWave = sin(treePhase * 4.7f) * 0.02f
                
                @Suppress("UNUSED_VARIABLE")
                val totalSway = primaryWave + secondaryWave + tertiaryWave
            }
            
            iterations++
        }
        
        val elapsed = System.currentTimeMillis() - startTime
        val ips = iterations * 1000.0 / elapsed
        
        println("Wind animation benchmark complete:")
        println("  Iterations: $iterations (${treeCount} trees each)")
        println("  Time: ${elapsed}ms")
        println("  Iterations/sec: %.0f".format(ips))
        
        assertTrue("Should achieve reasonable iteration rate", ips >= 1000)
    }

    /**
     * Focused benchmark: procedural noise generation.
     * Tests hash-based terrain generation.
     */
    @Test(timeout = 25000)
    fun benchmarkNoiseGeneration() {
        var iterations = 0L
        val startTime = System.currentTimeMillis()
        val endTime = startTime + 10_000  // 10 seconds
        
        println("Starting noise generation benchmark for 10 seconds...")
        
        val pointCount = 500
        
        while (System.currentTimeMillis() < endTime) {
            // Generate noise for multiple hills
            for (hill in 0 until 5) {
                val seed = 42 + hill * 1000
                
                for (p in 0 until pointCount) {
                    val x = p.toFloat() / pointCount
                    
                    // Multi-octave value noise
                    var noiseSum = 0f
                    var amplitude = 0.1f
                    var frequency = 2f
                    
                    for (octave in 0 until 4) {
                        val noiseX = x * frequency + seed
                        val noiseVal = hash((noiseX * 1000).toInt()) * 2f - 1f
                        noiseSum += noiseVal * amplitude
                        
                        amplitude *= 0.5f
                        frequency *= 2f
                    }
                }
            }
            
            iterations++
        }
        
        val elapsed = System.currentTimeMillis() - startTime
        val ips = iterations * 1000.0 / elapsed
        
        println("Noise generation benchmark complete:")
        println("  Iterations: $iterations (${5 * pointCount * 4} noise samples each)")
        println("  Time: ${elapsed}ms")
        println("  Iterations/sec: %.0f".format(ips))
        
        assertTrue("Should achieve reasonable iteration rate", ips >= 500)
    }

    /**
     * Benchmark: color interpolation and gradient calculations.
     * Tests color blending for time-of-day transitions.
     */
    @Test(timeout = 25000)
    fun benchmarkColorCalculations() {
        var iterations = 0L
        val startTime = System.currentTimeMillis()
        val endTime = startTime + 10_000  // 10 seconds
        
        println("Starting color calculation benchmark for 10 seconds...")
        
        val gradientStops = 20
        
        while (System.currentTimeMillis() < endTime) {
            val hour = (iterations * 0.01f) % 24f
            
            for (i in 0 until gradientStops) {
                val position = i.toFloat() / gradientStops
                
                // Day colors
                val dayR = (135 + position * 70).toInt().coerceIn(0, 255)
                val dayG = (206 + position * 30).toInt().coerceIn(0, 255)
                val dayB = 235
                
                // Night colors
                val nightR = (10 + position * 20).toInt().coerceIn(0, 255)
                val nightG = (15 + position * 25).toInt().coerceIn(0, 255)
                val nightB = (30 + position * 40).toInt().coerceIn(0, 255)
                
                // Time blend
                val dayBlend = when {
                    hour < 6 -> 0f
                    hour < 8 -> (hour - 6) / 2f
                    hour < 18 -> 1f
                    hour < 20 -> 1f - (hour - 18) / 2f
                    else -> 0f
                }
                
                @Suppress("UNUSED_VARIABLE")
                val color = blendColors(
                    nightR, nightG, nightB,
                    dayR, dayG, dayB,
                    dayBlend
                )
            }
            
            iterations++
        }
        
        val elapsed = System.currentTimeMillis() - startTime
        val ips = iterations * 1000.0 / elapsed
        
        println("Color calculation benchmark complete:")
        println("  Iterations: $iterations (${gradientStops} gradient stops each)")
        println("  Time: ${elapsed}ms")
        println("  Iterations/sec: %.0f".format(ips))
        
        assertTrue("Should achieve high iteration rate", ips >= 5000)
    }

    // ============================================================
    // HELPER FUNCTIONS
    // ============================================================

    /**
     * Hash function matching TerrainLayer's noise implementation.
     */
    private fun hash(n: Int): Float {
        var x = n
        x = (x shr 13) xor x
        x = (x * (x * x * 15731 + 789221) + 1376312589)
        return (x and 0x7fffffff) / 2147483647.0f
    }

    /**
     * Color blending for gradient interpolation.
     */
    private fun blendColors(
        r1: Int, g1: Int, b1: Int,
        r2: Int, g2: Int, b2: Int,
        blend: Float
    ): Int {
        val r = (r1 + (r2 - r1) * blend).toInt()
        val g = (g1 + (g2 - g1) * blend).toInt()
        val b = (b1 + (b2 - b1) * blend).toInt()
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    // ============================================================
    // ALLOCATION STRESS TESTS
    // These help identify GC pressure points
    // ============================================================

    /**
     * Benchmark with object allocation to find GC hotspots.
     */
    @Test(timeout = 25000)
    fun benchmarkWithAllocations() {
        var iterations = 0L
        val startTime = System.currentTimeMillis()
        val endTime = startTime + 10_000  // 10 seconds
        
        println("Starting allocation stress benchmark for 10 seconds...")
        
        while (System.currentTimeMillis() < endTime) {
            // Each frame calculation creates result objects
            val result = engine.calculateFrame(16)
            
            // Access results to prevent dead code elimination
            @Suppress("UNUSED_VARIABLE")
            val checksum = result.windGust + result.parallaxOffsets.sum()
            
            iterations++
        }
        
        val elapsed = System.currentTimeMillis() - startTime
        val ips = iterations * 1000.0 / elapsed
        
        println("Allocation stress benchmark complete:")
        println("  Iterations: $iterations")
        println("  Time: ${elapsed}ms")
        println("  Iterations/sec: %.0f".format(ips))
        
        assertTrue("Should achieve reasonable rate even with allocations", ips >= 500)
    }

    /**
     * Quick sanity test (not for profiling, just verification).
     */
    @Test
    fun testEngineProducesValidOutput() {
        val result = engine.calculateFrame(16)
        
        // Verify outputs are reasonable
        assertTrue("Wind gust should be in range", result.windGust >= 0f && result.windGust <= 1f)
        assertEquals("Should have parallax offsets for 7 layers", 14, result.parallaxOffsets.size)
        assertEquals("Should have 100 tree sway angles", 100, result.treeSwayAngles.size)
        assertEquals("Should have 250 hill heights", 250, result.hillHeights.size)
        assertEquals("Should have 10 sky colors", 10, result.skyColors.size)
    }
}
