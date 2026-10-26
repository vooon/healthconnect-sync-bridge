package ru.vehq.hcsync.gpx

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.xml.sax.SAXException
import ru.vehq.hcsync.model.RoutePoint
import ru.vehq.hcsync.model.RouteResult
import ru.vehq.hcsync.model.Workout
import java.io.StringReader
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import javax.xml.XMLConstants
import javax.xml.transform.stream.StreamSource
import javax.xml.validation.SchemaFactory

class GpxWriterTest {
    private val start = Instant.parse("2026-03-14T06:30:00Z")

    private fun workout(route: RouteResult, type: Int = 79, offset: ZoneOffset? = ZoneOffset.ofHours(3)) = Workout(
        id = "0f3a6a4e-1111-2222-3333-444455556666",
        exerciseType = type,
        title = "Morning <walk> & \"coffee\"",
        startTime = start,
        endTime = start.plusSeconds(1800),
        startZoneOffset = offset,
        sourcePackage = "com.example.watch",
        route = route,
    )

    private val points = listOf(
        RoutePoint(start.plusSeconds(10), 0.1234567891, 0.7654321, 12.34, 5.0),
        RoutePoint(start, 0.1, 0.7, null, null), // out of order on purpose, no altitude
        RoutePoint(start.plusSeconds(20), -0.2, -0.8, 13.0, null),
    )

    private fun validate(xml: String) {
        val xsd = javaClass.getResource("/gpx.xsd") ?: error("gpx.xsd test resource missing")
        val schema = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI).newSchema(xsd)
        schema.newValidator().validate(StreamSource(StringReader(xml)))
    }

    @Test
    fun fullRouteValidatesAgainstGpx11Xsd() {
        validate(GpxWriter.write(workout(RouteResult.Data(points))))
    }

    @Test
    fun emptyRouteStillValidates() {
        val xml = GpxWriter.write(workout(RouteResult.Data(emptyList())))
        validate(xml)
        assertTrue(xml.contains("<trkseg>\n    </trkseg>"))
    }

    @Test
    fun noDataRouteYieldsEmptySegment() {
        val xml = GpxWriter.write(workout(RouteResult.NoData))
        validate(xml)
        assertFalse(xml.contains("<trkpt"))
    }

    @Test
    fun pointsAreSortedAndAltitudeIsOptional() {
        val xml = GpxWriter.write(workout(RouteResult.Data(points)))
        val first = xml.indexOf("<trkpt lat=\"0.1000000\" lon=\"0.7000000\">")
        val second = xml.indexOf("<trkpt lat=\"0.1234568\" lon=\"0.7654321\">")
        val third = xml.indexOf("<trkpt lat=\"-0.2000000\" lon=\"-0.8000000\">")
        assertTrue(first in 0 until second && second < third)
        // The first point has no altitude → no <ele>; the second has 12.34 → "12.3".
        val firstBlock = xml.substring(first, second)
        assertFalse(firstBlock.contains("<ele>"))
        assertTrue(xml.substring(second, third).contains("<ele>12.3</ele>"))
        assertTrue(xml.contains("<time>2026-03-14T06:30:00Z</time>"))
    }

    @Test
    fun nameUsesRecordZoneAndTypeLabel() {
        val w = workout(RouteResult.Data(points))
        assertEquals("Walk 2026-03-14 09:30", GpxWriter.trailName(w))
        val xml = GpxWriter.write(w)
        assertTrue(xml.contains("<name>Walk 2026-03-14 09:30</name>"))
        assertTrue(xml.contains("<type>Walk</type>"))
    }

    @Test
    fun nameFallsBackToGivenZoneWhenRecordHasNone() {
        val w = workout(RouteResult.Data(points), type = 37, offset = null)
        assertEquals("Hike 2026-03-14 04:30", GpxWriter.trailName(w, ZoneId.of("-02:00")))
    }

    @Test
    fun recordIdLandsInMetadataDescAndTextIsEscaped() {
        val xml = GpxWriter.write(workout(RouteResult.Data(points)))
        validate(xml)
        assertTrue(xml.contains("<metadata>\n    <name>Walk 2026-03-14 09:30</name>\n    <desc>Health Connect record 0f3a6a4e-1111-2222-3333-444455556666 from com.example.watch</desc>"))
        assertTrue(xml.contains("creator=\"Wanderer Sync\""))
        assertFalse(xml.contains("<walk>")) // title is not emitted; only generated name/desc
    }

    @Test(expected = SAXException::class)
    fun validatorRejectsBrokenGpx() {
        validate("<gpx xmlns=\"http://www.topografix.com/GPX/1/1\" version=\"1.1\"><bogus/></gpx>")
    }
}
