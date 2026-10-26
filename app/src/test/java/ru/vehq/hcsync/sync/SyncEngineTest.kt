package ru.vehq.hcsync.sync

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.vehq.hcsync.data.SyncStateData
import ru.vehq.hcsync.data.SyncStateStore
import ru.vehq.hcsync.hc.HealthConnectSource
import ru.vehq.hcsync.model.RoutePoint
import ru.vehq.hcsync.model.RouteResult
import ru.vehq.hcsync.model.SensorSample
import ru.vehq.hcsync.model.SensorSamples
import ru.vehq.hcsync.model.Workout
import ru.vehq.hcsync.wanderer.WandererClient
import ru.vehq.hcsync.wanderer.WandererHttpException
import java.io.IOException
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

class SyncEngineTest {
    private val now = Instant.parse("2026-04-01T12:00:00Z")
    private val zone = ZoneId.of("UTC")

    private fun pts(n: Int) = List(n) { i -> RoutePoint(now.minusSeconds(3600L - i), 0.001 * i, 0.002 * i, 10.0, null) }

    private fun workout(id: String, daysAgo: Long, route: RouteResult) = Workout(
        id = id, exerciseType = 79, title = null,
        startTime = now.minus(Duration.ofDays(daysAgo)),
        endTime = now.minus(Duration.ofDays(daysAgo)).plusSeconds(1800),
        startZoneOffset = ZoneOffset.UTC, sourcePackage = "com.example.watch", route = route,
    )

    private class FakeSource(val workouts: List<Workout>, val sensors: SensorSamples = SensorSamples()) : HealthConnectSource {
        var lastFrom: Instant? = null
        val sensorReads = ArrayList<Pair<Instant, Instant>>()
        override suspend fun readWorkouts(from: Instant, to: Instant): List<Workout> {
            lastFrom = from
            return workouts.filter { it.endTime >= from && it.startTime <= to }
        }
        override suspend fun readSensorSamples(from: Instant, to: Instant): SensorSamples {
            sensorReads += from to to
            return sensors
        }
    }

    private class FakeClient(val failOn: (String) -> Exception? = { null }) : WandererClient {
        val uploads = ArrayList<Pair<String, String>>() // fileName to gpx
        override suspend fun testConnection() = "ok"
        override suspend fun uploadGpx(gpx: String, fileName: String): String {
            failOn(fileName)?.let { throw it }
            uploads += fileName to gpx
            return "trail-${uploads.size}"
        }
    }

    private class MemoryState(initial: Set<String> = emptySet()) : SyncStateStore {
        var data = SyncStateData(doneIds = initial)
        val marks = ArrayList<String>()
        override suspend fun load() = data
        override suspend fun markDone(id: String, uploadedEnd: Instant?) {
            marks += id
            data = data.copy(
                doneIds = data.doneIds + id,
                newestUploadedEnd = listOfNotNull(data.newestUploadedEnd, uploadedEnd).maxOrNull(),
            )
        }
        override suspend fun recordRun(at: Instant, summary: String, seen: Int) {
            data = data.copy(lastRunAt = at, lastResult = summary, lastSeen = seen)
        }
    }

    private fun engine(source: FakeSource, client: FakeClient, state: MemoryState, clock: () -> Instant = { now }) =
        SyncEngine(source, client, state, clock = clock, zone = zone)

    @Test
    fun uploadsNewRecordsOldestFirstAndPersistsEach() = runTest {
        val source = FakeSource(listOf(workout("b", 1, RouteResult.Data(pts(3))), workout("a", 2, RouteResult.Data(pts(2)))))
        val client = FakeClient()
        val state = MemoryState()
        val r = engine(source, client, state).run(Duration.ofDays(30))

        assertEquals(2, r.uploaded)
        assertNull(r.failure)
        assertEquals(listOf("trail-1", "trail-2"), r.uploadedTrailIds)
        assertEquals(listOf("a", "b"), state.marks)
        assertEquals(setOf("a", "b"), state.data.doneIds)
        assertEquals(source.workouts[0].endTime, state.data.newestUploadedEnd)
        assertEquals("Walk 2026-03-30 12_00.gpx", client.uploads[0].first)
        assertTrue(client.uploads[0].second.contains("<trkpt"))
        assertEquals(now, state.data.lastRunAt)
        assertEquals("uploaded 2", state.data.lastResult)
        assertEquals(2, state.data.lastSeen)
        assertEquals(now.minus(Duration.ofDays(30)), source.lastFrom)
    }

    @Test
    fun skipsIdsAlreadyDone() = runTest {
        val source = FakeSource(listOf(workout("a", 1, RouteResult.Data(pts(2))), workout("b", 2, RouteResult.Data(pts(2)))))
        val client = FakeClient()
        val state = MemoryState(initial = setOf("a"))
        val r = engine(source, client, state).run(Duration.ofDays(30))

        assertEquals(1, r.uploaded)
        assertEquals(1, r.alreadyDone)
        assertEquals(listOf("b"), state.marks)
        assertEquals(1, client.uploads.size)
    }

    @Test
    fun countsNoDataAndConsentWithoutUploading() = runTest {
        val source = FakeSource(
            listOf(
                workout("nodata", 1, RouteResult.NoData),
                workout("consent", 2, RouteResult.ConsentRequired),
                workout("empty", 3, RouteResult.Data(emptyList())),
            ),
        )
        val client = FakeClient()
        val state = MemoryState()
        val r = engine(source, client, state).run(Duration.ofDays(30))

        assertEquals(0, r.uploaded)
        assertEquals(2, r.skippedNoRoute)
        assertEquals(1, r.skippedConsent)
        assertTrue(client.uploads.isEmpty())
        // NoData/empty are final → remembered; ConsentRequired is retried next run → not remembered.
        assertEquals(setOf("nodata", "empty"), state.data.doneIds)
        assertNull(state.data.newestUploadedEnd)
        assertEquals("uploaded 0, no route 2, consent needed 1", r.summary())
    }

    @Test
    fun consentResolverTurnsConsentRequiredIntoUpload() = runTest {
        val source = FakeSource(listOf(workout("c", 1, RouteResult.ConsentRequired)))
        val client = FakeClient()
        val state = MemoryState()
        val asked = ArrayList<String>()
        val r = engine(source, client, state).run(Duration.ofDays(30)) { w ->
            asked += w.id
            RouteResult.Data(pts(5))
        }
        assertEquals(listOf("c"), asked)
        assertEquals(1, r.uploaded)
        assertEquals(0, r.skippedConsent)
        assertTrue(client.uploads.single().second.contains("<trkpt"))
    }

    @Test
    fun consentDeclinedIsCountedAndNotMarkedDone() = runTest {
        val source = FakeSource(listOf(workout("c", 1, RouteResult.ConsentRequired)))
        val state = MemoryState()
        val r = engine(source, FakeClient(), state).run(Duration.ofDays(30)) { null }
        assertEquals(1, r.skippedConsent)
        assertTrue(state.data.doneIds.isEmpty())
    }

    @Test
    fun partialFailureKeepsEarlierSuccessesAndStops() = runTest {
        val source = FakeSource(
            listOf(
                workout("a", 3, RouteResult.Data(pts(2))),
                workout("b", 2, RouteResult.Data(pts(2))),
                workout("c", 1, RouteResult.Data(pts(2))),
            ),
        )
        val client = FakeClient(failOn = { name -> if (name.contains("2026-03-30")) IOException("connection reset") else null })
        val state = MemoryState()
        val r = engine(source, client, state).run(Duration.ofDays(30))

        assertEquals(1, r.uploaded)
        assertEquals("connection reset", r.failure)
        assertTrue(r.retryable)
        assertEquals(setOf("a"), state.data.doneIds) // b failed, c never attempted
        assertEquals(1, client.uploads.size)
        assertEquals("uploaded 1, failed: connection reset", state.data.lastResult)
    }

    @Test
    fun authFailureIsNotRetryable() = runTest {
        val source = FakeSource(listOf(workout("a", 1, RouteResult.Data(pts(2)))))
        val client = FakeClient(failOn = { WandererHttpException(401, "invalid_token") })
        val state = MemoryState()
        val r = engine(source, client, state).run(Duration.ofDays(30))
        assertEquals(0, r.uploaded)
        assertFalse(r.retryable)
        assertTrue(r.failure!!.contains("401"))
        assertTrue(state.data.doneIds.isEmpty())
    }

    @Test
    fun serverErrorIsRetryable() = runTest {
        val source = FakeSource(listOf(workout("a", 1, RouteResult.Data(pts(2)))))
        val r = engine(source, FakeClient(failOn = { WandererHttpException(502, null) }), MemoryState()).run(Duration.ofDays(30))
        assertTrue(r.retryable)
    }

    @Test
    fun sensorsAreReadPerWorkoutAndWrittenAsExtensions() = runTest {
        val w = workout("a", 1, RouteResult.Data(pts(3)))
        val hr = pts(3).map { SensorSample(it.time, 120.0) }
        val source = FakeSource(listOf(w), SensorSamples(heartRateBpm = hr))
        val client = FakeClient()
        engine(source, client, MemoryState()).run(Duration.ofDays(30), includeSensors = true)
        assertEquals(listOf(w.startTime to w.endTime), source.sensorReads)
        assertTrue(client.uploads.single().second.contains("<gpxtpx:hr>120</gpxtpx:hr>"))
    }

    @Test
    fun sensorsAreNotReadWhenDisabled() = runTest {
        val source = FakeSource(listOf(workout("a", 1, RouteResult.Data(pts(2)))), SensorSamples(heartRateBpm = listOf(SensorSample(now, 99.0))))
        val client = FakeClient()
        engine(source, client, MemoryState()).run(Duration.ofDays(30), includeSensors = false)
        assertTrue(source.sensorReads.isEmpty())
        assertFalse(client.uploads.single().second.contains("gpxtpx:hr>"))
    }

    @Test
    fun timeBudgetDefersRemainingRecordsAndFlagsMoreWork() = runTest {
        val source = FakeSource(
            listOf(
                workout("a", 3, RouteResult.Data(pts(2))),
                workout("b", 2, RouteResult.Data(pts(2))),
                workout("c", 1, RouteResult.Data(pts(2))),
            ),
        )
        val client = FakeClient()
        val state = MemoryState()
        // The clock jumps 5 minutes per read: start=0, first check=5 (inside a 6-minute budget), second=10 (past it).
        var ticks = 0L
        val clock = { now.plusSeconds(300 * ticks++) }
        val r = engine(source, client, state, clock).run(Duration.ofDays(30), budget = Duration.ofMinutes(6))

        assertEquals(1, r.uploaded)
        assertTrue(r.moreWork)
        assertNull(r.failure)
        assertEquals(setOf("a"), state.data.doneIds)
        assertEquals("uploaded 1, more pending", r.summary())
    }

    @Test
    fun fileNameIsSafe() {
        val w = workout("x", 1, RouteResult.NoData).copy(exerciseType = 0, startZoneOffset = ZoneOffset.ofHours(2))
        assertEquals("Workout 2026-03-31 14_00.gpx", SyncEngine.fileName(w, zone))
    }
}
