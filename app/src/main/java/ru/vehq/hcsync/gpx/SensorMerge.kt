package ru.vehq.hcsync.gpx

import ru.vehq.hcsync.model.RoutePoint
import ru.vehq.hcsync.model.SensorSample
import ru.vehq.hcsync.model.SensorSamples
import ru.vehq.hcsync.model.isCyclingType
import java.time.Duration
import java.time.Instant
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Attaches heart rate and cadence to track points. Health Connect stores sensors as separate
 * series with their own timestamps, so each point takes the sample nearest in time, if one lies
 * within [tolerance]. Pure Kotlin.
 */
object SensorMerge {
    val DEFAULT_TOLERANCE: Duration = Duration.ofSeconds(30)

    fun merge(
        points: List<RoutePoint>,
        samples: SensorSamples,
        exerciseType: Int,
        tolerance: Duration = DEFAULT_TOLERANCE,
    ): List<RoutePoint> {
        if (samples.isEmpty) return points
        val hr = samples.heartRateBpm.sortedBy { it.time }
        // Garmin's <cad> is rpm: pedal revolutions when cycling, otherwise steps/min ÷ 2 (one foot).
        val cadence = if (isCyclingType(exerciseType)) samples.pedalRpm.sortedBy { it.time }
        else samples.stepsPerMinute.sortedBy { it.time }.map { SensorSample(it.time, it.value / 2.0) }
        val tol = tolerance.toMillis()
        return points.map { p ->
            p.copy(
                heartRateBpm = nearest(hr, p.time, tol)?.roundToInt(),
                cadenceRpm = nearest(cadence, p.time, tol)?.roundToInt(),
            )
        }
    }

    /** Value of the sample closest to [t] within [tolMillis], by binary search over sorted samples. */
    private fun nearest(sorted: List<SensorSample>, t: Instant, tolMillis: Long): Double? {
        if (sorted.isEmpty()) return null
        val target = t.toEpochMilli()
        var lo = 0
        var hi = sorted.size - 1
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (sorted[mid].time.toEpochMilli() < target) lo = mid + 1 else hi = mid
        }
        val candidates = listOfNotNull(sorted.getOrNull(lo - 1), sorted[lo])
        val best = candidates.minByOrNull { abs(it.time.toEpochMilli() - target) } ?: return null
        return if (abs(best.time.toEpochMilli() - target) <= tolMillis) best.value else null
    }
}
