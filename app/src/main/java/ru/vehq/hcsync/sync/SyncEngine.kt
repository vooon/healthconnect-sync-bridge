package ru.vehq.hcsync.sync

import ru.vehq.hcsync.data.SyncStateStore
import ru.vehq.hcsync.gpx.GpxWriter
import ru.vehq.hcsync.gpx.SensorMerge
import ru.vehq.hcsync.hc.HealthConnectSource
import ru.vehq.hcsync.model.RouteResult
import ru.vehq.hcsync.model.Workout
import ru.vehq.hcsync.wanderer.WandererClient
import ru.vehq.hcsync.wanderer.WandererHttpException
import java.io.IOException
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

data class SyncResult(
    val uploaded: Int = 0,
    val skippedNoRoute: Int = 0,
    val skippedConsent: Int = 0,
    val alreadyDone: Int = 0,
    /** Set when the run stopped early; earlier uploads in the same run are already persisted. */
    val failure: String? = null,
    /** True for transient errors (network, 5xx) where WorkManager should retry with backoff. */
    val retryable: Boolean = false,
    /** True when the run stopped on its time budget with records still to upload. */
    val moreWork: Boolean = false,
    val uploadedTrailIds: List<String> = emptyList(),
) {
    fun summary(): String = buildString {
        append("uploaded ").append(uploaded)
        if (skippedNoRoute > 0) append(", no route ").append(skippedNoRoute)
        if (skippedConsent > 0) append(", consent needed ").append(skippedConsent)
        if (failure != null) append(", failed: ").append(failure)
        if (moreWork) append(", more pending")
    }
}

/**
 * read → drop done ids → (resolve consent) → GPX → upload → persist, one record at a time.
 * No Android types: everything comes in through the three interfaces.
 */
class SyncEngine(
    private val source: HealthConnectSource,
    private val client: WandererClient,
    private val state: SyncStateStore,
    private val clock: () -> Instant = Instant::now,
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val log: (String) -> Unit = {},
) {
    /**
     * @param lookback       how far back to read; 30 days without the history permission, more with it.
     * @param includeSensors merge heart rate / cadence samples into the track points.
     * @param budget         stop starting new uploads once this much time has passed (background
     *                       runs must finish inside WorkManager's 10-minute limit); null = unlimited.
     * @param consent        foreground only: asks the user for a single record's route; null = skip & count.
     */
    suspend fun run(
        lookback: Duration,
        includeSensors: Boolean = false,
        budget: Duration? = null,
        consent: (suspend (Workout) -> RouteResult?)? = null,
    ): SyncResult {
        val now = clock()
        val deadline = budget?.let { now.plus(it) }
        val done = state.load().doneIds
        val workouts = source.readWorkouts(now.minus(lookback), now).sortedBy { it.startTime }

        var result = SyncResult()
        val trailIds = ArrayList<String>()
        for (w in workouts) {
            if (w.id in done) {
                result = result.copy(alreadyDone = result.alreadyDone + 1)
                continue
            }
            var route = w.route
            if (route is RouteResult.ConsentRequired && consent != null) {
                route = consent(w) ?: RouteResult.ConsentRequired
            }
            when (route) {
                RouteResult.NoData -> {
                    // Nothing will ever appear here; remember the id so we stop re-reading it.
                    state.markDone(w.id, uploadedEnd = null)
                    result = result.copy(skippedNoRoute = result.skippedNoRoute + 1)
                }
                RouteResult.ConsentRequired -> {
                    // Not marked done: it becomes uploadable once the user grants route access.
                    result = result.copy(skippedConsent = result.skippedConsent + 1)
                }
                is RouteResult.Data -> {
                    if (deadline != null && clock().isAfter(deadline)) {
                        // Leave the rest for the next run; everything so far is already persisted.
                        result = result.copy(moreWork = true)
                        log("time budget exhausted, deferring remaining records")
                        break
                    }
                    if (route.points.isEmpty()) {
                        state.markDone(w.id, uploadedEnd = null)
                        result = result.copy(skippedNoRoute = result.skippedNoRoute + 1)
                        continue
                    }
                    try {
                        val points = if (includeSensors) {
                            SensorMerge.merge(route.points, source.readSensorSamples(w.startTime, w.endTime), w.exerciseType)
                        } else {
                            route.points
                        }
                        val toUpload = w.copy(route = RouteResult.Data(points))
                        val gpx = GpxWriter.write(toUpload, zone)
                        val trailId = client.uploadGpx(gpx, fileName(toUpload))
                        state.markDone(w.id, uploadedEnd = w.endTime)
                        trailIds += trailId
                        result = result.copy(uploaded = result.uploaded + 1)
                        log("uploaded record ${w.id} as trail $trailId")
                    } catch (e: Exception) {
                        result = result.copy(failure = describe(e), retryable = isRetryable(e))
                        log("upload of record ${w.id} failed: ${describe(e)}")
                        break
                    }
                }
            }
        }
        result = result.copy(uploadedTrailIds = trailIds)
        state.recordRun(now, result.summary(), seen = workouts.size)
        return result
    }

    private fun describe(e: Exception): String = e.message ?: e.javaClass.simpleName

    private fun isRetryable(e: Exception): Boolean = when (e) {
        is WandererHttpException -> e.status >= 500 || e.status == 408 || e.status == 429
        is IOException -> true
        else -> false
    }

    companion object {
        /** `Walk 2026-03-14 09:30.gpx` with characters that upset file systems replaced. */
        fun fileName(w: Workout, zone: ZoneId = ZoneId.systemDefault()): String =
            GpxWriter.trailName(w, zone).replace(Regex("[^A-Za-z0-9 ._-]"), "_") + ".gpx"
    }
}
