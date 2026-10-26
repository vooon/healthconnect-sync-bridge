package ru.vehq.hcsync.ui

import ru.vehq.hcsync.model.RouteResult
import ru.vehq.hcsync.model.SensorSamples
import ru.vehq.hcsync.model.Workout
import ru.vehq.hcsync.model.exerciseTypeLabel
import java.time.Duration
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val probeTimeFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

/**
 * One line per session, plain text so the owner can copy it out of the phone.
 * @param sensors per record id, the sensor samples found for that session (omitted = not read).
 */
fun formatProbeReport(
    workouts: List<Workout>,
    zone: ZoneId = ZoneId.systemDefault(),
    sensors: Map<String, SensorSamples> = emptyMap(),
): String {
    if (workouts.isEmpty()) return "No exercise sessions in the last 30 days."
    val sb = StringBuilder()
    sb.append(workouts.size).append(" session(s), newest first:\n")
    for (w in workouts.sortedByDescending { it.startTime }) {
        val start = probeTimeFormat.format(w.startTime.atZone(w.startZoneOffset ?: zone))
        val minutes = Duration.between(w.startTime, w.endTime).toMinutes()
        val route = when (val r = w.route) {
            is RouteResult.Data -> "Data(${r.points.size} pts)"
            RouteResult.ConsentRequired -> "ConsentRequired"
            RouteResult.NoData -> "NoData"
        }
        sb.append(start).append("  ")
            .append(exerciseTypeLabel(w.exerciseType)).append(" (type ").append(w.exerciseType).append(")  ")
            .append(minutes).append(" min  route=").append(route)
        sensors[w.id]?.let { s ->
            sb.append("  hr=").append(s.heartRateBpm.size)
                .append(" steps/min=").append(s.stepsPerMinute.size)
                .append(" rpm=").append(s.pedalRpm.size)
        }
        sb.append("  from=").append(w.sourcePackage)
            .append("  id=").append(w.id)
            .append('\n')
    }
    return sb.toString()
}
