package ru.vehq.hcsync.gpx

import ru.vehq.hcsync.model.RoutePoint
import ru.vehq.hcsync.model.RouteResult
import ru.vehq.hcsync.model.Workout
import ru.vehq.hcsync.model.exerciseTypeLabel
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Workout → GPX 1.1 text. Pure Kotlin (no android.* types) so it runs in plain JUnit.
 *
 * Layout: one `<trk>` with one `<trkseg>`. The Health Connect record id goes into
 * `<metadata><desc>` because Wanderer maps that element to the trail description.
 */
object GpxWriter {
    const val CREATOR = "Wanderer Sync"

    private val nameTimeFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

    /** `"<Type label> <start time, yyyy-MM-dd HH:mm>"` in the record's own zone, else [fallbackZone]. */
    fun trailName(workout: Workout, fallbackZone: ZoneId = ZoneId.systemDefault()): String {
        val zone: ZoneId = workout.startZoneOffset ?: fallbackZone
        return exerciseTypeLabel(workout.exerciseType) + " " + nameTimeFormat.format(workout.startTime.atZone(zone))
    }

    fun write(workout: Workout, fallbackZone: ZoneId = ZoneId.systemDefault()): String {
        val points: List<RoutePoint> = when (val r = workout.route) {
            is RouteResult.Data -> r.points.sortedBy { it.time }
            else -> emptyList()
        }
        val name = trailName(workout, fallbackZone)
        val label = exerciseTypeLabel(workout.exerciseType)
        val desc = "Health Connect record ${workout.id} from ${workout.sourcePackage}"

        val sb = StringBuilder(256 + points.size * 96)
        sb.append("""<?xml version="1.0" encoding="UTF-8"?>""").append('\n')
        sb.append("""<gpx version="1.1" creator="""").append(esc(CREATOR)).append('"')
            .append(""" xmlns="http://www.topografix.com/GPX/1/1"""")
            .append(""" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"""")
            .append(""" xsi:schemaLocation="http://www.topografix.com/GPX/1/1 http://www.topografix.com/GPX/1/1/gpx.xsd">""")
            .append('\n')
        // Element order inside <metadata> and <trk> is fixed by the XSD; keep it.
        sb.append("  <metadata>\n")
        sb.append("    <name>").append(esc(name)).append("</name>\n")
        sb.append("    <desc>").append(esc(desc)).append("</desc>\n")
        sb.append("    <time>").append(time(workout.startTime)).append("</time>\n")
        sb.append("  </metadata>\n")
        sb.append("  <trk>\n")
        sb.append("    <name>").append(esc(name)).append("</name>\n")
        sb.append("    <desc>").append(esc(desc)).append("</desc>\n")
        sb.append("    <type>").append(esc(label)).append("</type>\n")
        sb.append("    <trkseg>\n")
        for (p in points) {
            sb.append("""      <trkpt lat="""").append(coord(p.latitude)).append("""" lon="""").append(coord(p.longitude)).append("\">\n")
            p.altitudeMeters?.let { sb.append("        <ele>").append(ele(it)).append("</ele>\n") }
            sb.append("        <time>").append(time(p.time)).append("</time>\n")
            sb.append("      </trkpt>\n")
        }
        sb.append("    </trkseg>\n")
        sb.append("  </trk>\n")
        sb.append("</gpx>\n")
        return sb.toString()
    }

    private fun coord(v: Double): String = String.format(Locale.ROOT, "%.7f", v)
    private fun ele(v: Double): String = String.format(Locale.ROOT, "%.1f", v)
    private fun time(t: Instant): String = DateTimeFormatter.ISO_INSTANT.format(t)

    private fun esc(s: String): String {
        val sb = StringBuilder(s.length)
        for (c in s) {
            when (c) {
                '&' -> sb.append("&amp;")
                '<' -> sb.append("&lt;")
                '>' -> sb.append("&gt;")
                '"' -> sb.append("&quot;")
                else -> sb.append(c)
            }
        }
        return sb.toString()
    }
}
