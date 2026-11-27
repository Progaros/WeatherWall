package info.olli.weatherlwp

import info.olli.weatherlwp.parallax.ParallaxCalculator
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

/**
 * Performance benchmark for profiling with async-profiler.
 *
 * Runs ParallaxCalculator in a loop for 10 seconds so a profiler can capture meaningful data.
 *
 * Usage with async-profiler:
 * 1. Start test in background:
 *    ./gradlew :app:testDebugUnitTest --tests "PerformanceTest.*" &
 * 2. Wait for Gradle to start worker, then find it:
 *    TEST_PID=$(jps | grep GradleWorker | cut -d ' ' -f 1)
 * 3. Profile:
 *    /opt/async-profiler/profiler.sh -d 10 -f /tmp/profile.html $TEST_PID
 */
class PerformanceTest {

    private lateinit var parallaxCalculator: ParallaxCalculator

    @Before
    fun setUp() {
        parallaxCalculator = ParallaxCalculator()
        parallaxCalculator.setCanvasSize(1080f, 1920f)
    }

    @Test(timeout = 25000)
    fun benchmarkParallaxCalculations() {
        var iterations = 0L
        val startTime = System.currentTimeMillis()
        val endTime = startTime + 10_000

        println("Starting parallax benchmark for 10 seconds...")

        while (System.currentTimeMillis() < endTime) {
            val tiltX = sin(iterations * 0.001f) * 0.5f
            val tiltY = cos(iterations * 0.001f) * 0.3f
            parallaxCalculator.updateFromSensor(tiltX, tiltY)

            parallaxCalculator.update(16)

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
}
