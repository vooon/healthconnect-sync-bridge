package ru.vehq.hcsync.gpx

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import ru.vehq.hcsync.model.RoutePoint
import ru.vehq.hcsync.model.SensorSample
import ru.vehq.hcsync.model.SensorSamples
import java.time.Duration
import java.time.Instant

class SensorMergeTest {
    private val t0 = Instant.parse("2026-05-01T08:00:00Z")
    private fun pt(sec: Long) = RoutePoint(t0.plusSeconds(sec), 0.0, 0.0, null, null)
    private fun s(sec: Long, v: Double) = SensorSample(t0.plusSeconds(sec), v)

    private val walking = 79
    private val biking = 8

    @Test
    fun nearestSampleWithinToleranceIsUsedAndRounded() {
        val points = listOf(pt(0), pt(10), pt(20))
        val samples = SensorSamples(heartRateBpm = listOf(s(12, 110.4), s(3, 100.6), s(19, 130.0)))
        val merged = SensorMerge.merge(points, samples, walking)
        assertEquals(listOf(101, 110, 130), merged.map { it.heartRateBpm })
        assertEquals(listOf<Int?>(null, null, null), merged.map { it.cadenceRpm })
    }

    @Test
    fun samplesTooFarAwayAreIgnored() {
        val points = listOf(pt(0), pt(600))
        val samples = SensorSamples(heartRateBpm = listOf(s(5, 90.0)))
        val merged = SensorMerge.merge(points, samples, walking, tolerance = Duration.ofSeconds(30))
        assertEquals(90, merged[0].heartRateBpm)
        assertNull(merged[1].heartRateBpm)
    }

    @Test
    fun stepsPerMinuteBecomeHalfForGarminCadenceOnFoot() {
        val merged = SensorMerge.merge(listOf(pt(0)), SensorSamples(stepsPerMinute = listOf(s(0, 170.0))), walking)
        assertEquals(85, merged[0].cadenceRpm)
    }

    @Test
    fun pedalRpmIsUsedAsIsForCycling() {
        val samples = SensorSamples(stepsPerMinute = listOf(s(0, 170.0)), pedalRpm = listOf(s(1, 88.0)))
        val merged = SensorMerge.merge(listOf(pt(0)), samples, biking)
        assertEquals(88, merged[0].cadenceRpm)
    }

    @Test
    fun noSamplesReturnsSameList() {
        val points = listOf(pt(0))
        assertSame(points, SensorMerge.merge(points, SensorSamples(), walking))
    }
}
