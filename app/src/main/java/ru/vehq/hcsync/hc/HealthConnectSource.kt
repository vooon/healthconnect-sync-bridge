// The ExerciseRouteResult mapping below is derived from OpenVitals
// (https://github.com/OpenVitals-MTU/android-app)
// app/src/main/kotlin/tech/mmarca/openvitals/healthconnect/HealthConnectMappers.kt
// at commit d305e83, Copyright the OpenVitals authors, AGPL-3.0-or-later.
// Modified for Wanderer Sync.
package ru.vehq.hcsync.hc

import android.content.Context
import android.util.Log
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.CyclingPedalingCadenceRecord
import androidx.health.connect.client.records.ExerciseRoute
import androidx.health.connect.client.records.ExerciseRouteResult
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.StepsCadenceRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.vehq.hcsync.model.RoutePoint
import ru.vehq.hcsync.model.RouteResult
import ru.vehq.hcsync.model.SensorSample
import ru.vehq.hcsync.model.SensorSamples
import ru.vehq.hcsync.model.Workout
import java.time.Instant
import kotlin.reflect.KClass

/** Read side of the bridge. An interface so SyncEngine can be tested without Health Connect. */
interface HealthConnectSource {
    /** All exercise sessions whose time range overlaps [from, to), oldest first. */
    suspend fun readWorkouts(from: Instant, to: Instant): List<Workout>

    /**
     * Heart-rate and cadence samples within [from, to), typically one workout's span. A series
     * whose permission is not granted comes back empty rather than failing the sync.
     */
    suspend fun readSensorSamples(from: Instant, to: Instant): SensorSamples
}

class HealthConnectClientSource(context: Context) : HealthConnectSource {
    private val client: HealthConnectClient by lazy {
        HealthConnectClient.getOrCreate(context.applicationContext)
    }

    override suspend fun readWorkouts(from: Instant, to: Instant): List<Workout> =
        withContext(Dispatchers.IO) { readAll(ExerciseSessionRecord::class, from, to).map { it.toWorkout() } }

    override suspend fun readSensorSamples(from: Instant, to: Instant): SensorSamples =
        withContext(Dispatchers.IO) {
            SensorSamples(
                heartRateBpm = readOrEmpty(HeartRateRecord::class, from, to)
                    .flatMap { r -> r.samples.map { SensorSample(it.time, it.beatsPerMinute.toDouble()) } },
                stepsPerMinute = readOrEmpty(StepsCadenceRecord::class, from, to)
                    .flatMap { r -> r.samples.map { SensorSample(it.time, it.rate) } },
                pedalRpm = readOrEmpty(CyclingPedalingCadenceRecord::class, from, to)
                    .flatMap { r -> r.samples.map { SensorSample(it.time, it.revolutionsPerMinute) } },
            )
        }

    /** Pages through every record of [type] overlapping [from, to). */
    private suspend fun <T : Record> readAll(type: KClass<T>, from: Instant, to: Instant): List<T> {
        val out = ArrayList<T>()
        var pageToken: String? = null
        do {
            val response = client.readRecords(
                ReadRecordsRequest(
                    recordType = type,
                    timeRangeFilter = TimeRangeFilter.between(from, to),
                    ascendingOrder = true,
                    pageToken = pageToken,
                ),
            )
            out += response.records
            pageToken = response.pageToken
        } while (pageToken != null)
        return out
    }

    /** Sensor series are optional: a missing permission (SecurityException) yields an empty list. */
    private suspend fun <T : Record> readOrEmpty(type: KClass<T>, from: Instant, to: Instant): List<T> =
        try {
            readAll(type, from, to)
        } catch (e: SecurityException) {
            Log.i(TAG, "no permission for ${type.simpleName}; skipping")
            emptyList()
        }

    private companion object {
        const val TAG = "WandererSync"
    }
}

internal fun ExerciseSessionRecord.toWorkout(): Workout = Workout(
    id = metadata.id,
    exerciseType = exerciseType,
    title = title,
    startTime = startTime,
    endTime = endTime,
    startZoneOffset = startZoneOffset,
    sourcePackage = metadata.dataOrigin.packageName,
    route = exerciseRouteResult.toRouteResult(),
)

/** Also used after a per-record consent dialog returns an [ExerciseRoute] directly. */
fun ExerciseRoute.toRoutePoints(): List<RoutePoint> = route.map { point ->
    RoutePoint(
        time = point.time,
        latitude = point.latitude,
        longitude = point.longitude,
        altitudeMeters = point.altitude?.inMeters,
        horizontalAccuracyMeters = point.horizontalAccuracy?.inMeters,
    )
}

private fun ExerciseRouteResult.toRouteResult(): RouteResult = when (this) {
    is ExerciseRouteResult.Data -> RouteResult.Data(exerciseRoute.toRoutePoints())
    is ExerciseRouteResult.ConsentRequired -> RouteResult.ConsentRequired
    is ExerciseRouteResult.NoData -> RouteResult.NoData
    // ExerciseRouteResult is abstract, not sealed; treat unknown subclasses as "no route".
    else -> RouteResult.NoData
}
