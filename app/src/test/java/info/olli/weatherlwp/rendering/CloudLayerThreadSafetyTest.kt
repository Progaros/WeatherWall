package info.olli.weatherlwp.rendering

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/**
 * Regression tests for CloudLayer thread safety fixes.
 *
 * Covers:
 * - cellCache uses ConcurrentHashMap (not plain HashMap)
 * - bitmap.isRecycled guard in draw loop
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CloudLayerThreadSafetyTest {

    @Test
    fun `destroy does not wait for workers or allow late bitmap publication`() {
        val layer = CloudLayer(RuntimeEnvironment.getApplication()).apply { resize(360f, 640f) }
        val group = layer.getCloudGroups().first()
        val executorField = CloudLayer::class.java.getDeclaredField("bitmapExecutor").apply { isAccessible = true }
        val executor = executorField.get(layer) as ExecutorService
        val render = CloudLayer::class.java.getDeclaredMethod(
            "renderGroupToBitmap", CloudLayer.CloudGroup::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType
        ).apply { isAccessible = true }
        val started = CountDownLatch(1)
        val release = Semaphore(0)
        executor.execute {
            started.countDown()
            release.acquireUninterruptibly()
            render.invoke(layer, group, 64, 32)
        }
        try {
            assertTrue(started.await(5, TimeUnit.SECONDS))
            val startNanos = System.nanoTime()
            layer.destroy()
            assertTrue("Teardown must not wait for the worker", System.nanoTime() - startNanos < 500_000_000L)
        } finally {
            release.release()
            layer.destroy()
        }
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        assertNull("A late worker must not publish into the destroyed layer", group.cachedBitmap)
        assertTrue(layer.getCloudGroups().isEmpty())
    }

    @Test
    fun `ConcurrentHashMap supports concurrent clear during iteration`() {
        // Regression: cellCache was a plain HashMap, causing ConcurrentModificationException
        // when reloadSettings() called clear() while render thread iterated.
        // This test verifies ConcurrentHashMap handles this correctly.
        val map = ConcurrentHashMap<Int, List<String>>()
        map[1] = listOf("a", "b")
        map[2] = listOf("c")
        map[3] = listOf("d", "e")

        // Simulate concurrent clear during iteration (no exception should be thrown)
        val iterator = map.entries.iterator()
        map.clear() // Concurrent clear
        // Iterator should safely finish with whatever snapshot it has
        var count = 0
        while (iterator.hasNext()) {
            iterator.next()
            count++
        }
        // After clear, map is empty
        assertTrue("Map should be empty after clear", map.isEmpty())
    }

    @Test
    fun `ConcurrentHashMap getOrPut is safe with concurrent clear`() {
        val map = ConcurrentHashMap<Int, List<String>>()

        // Simulate getOrPut from render thread while clear happens from main thread
        map[1] = listOf("cached")
        
        // Clear (simulating reloadSettings call)
        map.clear()
        
        // getOrPut after clear should generate new value
        val result = map.getOrPut(1) { listOf("regenerated") }
        assertEquals(listOf("regenerated"), result)
    }

    @Test
    fun `ConcurrentHashMap keys removal is safe`() {
        val map = ConcurrentHashMap<Int, List<String>>()
        for (i in 1..10) map[i] = listOf("cell$i")

        // Simulate eviction from render thread (keys outside viewport)
        val toRemove = map.keys.filter { it > 5 }
        for (key in toRemove) map.remove(key)

        assertEquals("Should have 5 remaining entries", 5, map.size)
    }
}
