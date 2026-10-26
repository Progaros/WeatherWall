package info.olli.weatherlwp.rendering.events

import info.olli.weatherlwp.rendering.StarField
import info.olli.weatherlwp.rendering.StarFieldOverlay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class ManualEventSpawnsTest {

    private fun entry(id: String, t: Long) = ManualEventSpawns.Entry(id, t)

    @Test
    fun `format and parse round trip`() {
        val entries = listOf(entry("fox", 1_000L), entry("starfall", 2_000L))
        assertEquals(entries, ManualEventSpawns.parse(ManualEventSpawns.format(entries)))
        assertEquals(emptyList<ManualEventSpawns.Entry>(), ManualEventSpawns.parse(null))
        assertEquals(listOf(entry("owl", 5L)), ManualEventSpawns.parse("garbage,owl@5,@7,x@y"))
    }

    @Test
    fun `every press is replayed once, keeping the gaps between presses`() {
        val replayer = ManualEventSpawns.Replayer(lastHandledTriggerMs = 0L)
        val presses = listOf(entry("fox", 10_000L), entry("owl", 11_000L), entry("bat", 13_500L))

        // Wallpaper hidden while all three were pressed; it becomes visible at 20 s.
        val started = replayer.takeNew(presses, nowMs = 20_000L)
        assertEquals(listOf("fox", "owl", "bat"), started.map { it.first.eventId })
        assertEquals(listOf(20_000L, 21_000L, 23_500L), started.map { it.second })

        assertTrue(replayer.takeNew(presses, nowMs = 30_000L).isEmpty())
        val next = replayer.takeNew(presses + entry("fox", 31_000L), nowMs = 31_050L)
        assertEquals(listOf(31_050L), next.map { it.second })
    }

    @Test
    fun `presses from before the scene existed or too old are ignored`() {
        val replayer = ManualEventSpawns.Replayer(lastHandledTriggerMs = 50_000L)
        assertTrue(replayer.takeNew(listOf(entry("fox", 40_000L)), nowMs = 60_000L).isEmpty())
        val stale = ManualEventSpawns.Replayer(0L)
        assertTrue(stale.takeNew(listOf(entry("fox", 0L)), nowMs = ManualEventSpawns.MAX_AGE_MS + 1).isEmpty())
    }

    @Test
    fun `random button resolves a deterministic type`() {
        assertEquals(ManualEventSpawns.randomEventId(123L), ManualEventSpawns.randomEventId(123L))
        assertTrue(EventSpec.fromId(ManualEventSpawns.randomEventId(456L)) != null)
    }

    @Test
    fun `same seed gives the same star fall in preview and wallpaper`() {
        fun snapshot(): String {
            val event = StarFallEvent(Random(99L), spawnTimeMs = 0L)
            val overlay = StarFieldOverlay().apply { reset() }
            event.applyTo(overlay, 2_500L)
            return overlay.starAlpha.joinToString() + "|" + (0 until overlay.meteorCount).joinToString {
                "${overlay.meteorHeadX[it]},${overlay.meteorHeadY[it]}"
            }
        }
        assertEquals(snapshot(), snapshot())
    }

    @Test
    fun `star fall hides real stars, adds new ones and finally restores the field`() {
        val event = StarFallEvent(Random(7L), spawnTimeMs = 0L)
        val overlay = StarFieldOverlay()
        var sawMeteor = false
        var sawHidden = false
        var sawNewStar = false
        var t = 0L
        while (t < event.durationMs) {
            overlay.reset()
            event.applyTo(overlay, t)
            if (overlay.meteorCount > 0) sawMeteor = true
            if (overlay.starAlpha.any { it == 0f }) sawHidden = true
            if (overlay.extraCount > 0 && overlay.extraAlpha.take(overlay.extraCount).any { it > 0.9f }) sawNewStar = true
            t += 250L
        }
        assertTrue(sawMeteor && sawHidden && sawNewStar)

        overlay.reset()
        event.applyTo(overlay, event.durationMs)
        assertTrue("field restored", overlay.starAlpha.all { it == 1f })
        assertEquals(0, overlay.extraCount)
        assertEquals(StarField.stars.size, overlay.starAlpha.size)
    }

    @Test
    fun `perseid activity peaks at solar longitude 140 and is zero outside the shower`() {
        assertEquals(1f, MeteorShowers.perseidActivityAt(140.0), 1e-6f)
        assertEquals(0f, MeteorShowers.perseidActivityAt(100.0), 0f)
        assertEquals(0f, MeteorShowers.perseidActivityAt(160.0), 0f)
        assertTrue(MeteorShowers.perseidActivityAt(135.0) > MeteorShowers.perseidActivityAt(130.0))
        // Faster decline after the maximum.
        assertTrue(MeteorShowers.perseidActivityAt(142.0) < MeteorShowers.perseidActivityAt(138.0))
    }
}
