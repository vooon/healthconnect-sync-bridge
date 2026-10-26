package ru.vehq.hcsync.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.vehq.hcsync.model.RoutePoint
import ru.vehq.hcsync.model.RouteResult
import ru.vehq.hcsync.model.Workout
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

class ProbeReportTest {
    private fun workout(id: String, start: String, route: RouteResult) = Workout(
        id = id,
        exerciseType = 79, // walking
        title = null,
        startTime = Instant.parse(start),
        endTime = Instant.parse(start).plusSeconds(45 * 60),
        startZoneOffset = ZoneOffset.ofHours(2),
        sourcePackage = "com.example.watch",
        route = route,
    )

    @Test
    fun emptyListSaysSo() {
        assertEquals("No exercise sessions in the last 30 days.", formatProbeReport(emptyList()))
    }

    @Test
    fun oneLinePerSessionNewestFirstWithRouteStatus() {
        val pts = listOf(RoutePoint(Instant.parse("2026-01-02T10:00:00Z"), 0.0, 0.0, null, null))
        val report = formatProbeReport(
            listOf(
                workout("a", "2026-01-01T10:00:00Z", RouteResult.NoData),
                workout("b", "2026-01-02T10:00:00Z", RouteResult.Data(pts)),
                workout("c", "2026-01-03T10:00:00Z", RouteResult.ConsentRequired),
            ),
            zone = ZoneId.of("UTC"),
        )
        val lines = report.trim().lines()
        assertEquals(4, lines.size)
        assertTrue(lines[0].startsWith("3 session(s)"))
        assertTrue(lines[1].contains("route=ConsentRequired") && lines[1].contains("id=c"))
        assertTrue(lines[2].contains("route=Data(1 pts)") && lines[2].contains("id=b"))
        assertTrue(lines[3].contains("route=NoData") && lines[3].contains("id=a"))
        // Local time: the record's own zone offset (+02:00) wins over the default zone.
        assertTrue(lines[3].startsWith("2026-01-01 12:00  Walk (type 79)  45 min"))
    }
}
