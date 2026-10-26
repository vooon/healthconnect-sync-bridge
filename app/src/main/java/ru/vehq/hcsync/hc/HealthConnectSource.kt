// The ExerciseRouteResult mapping below is derived from OpenVitals
// (https://github.com/OpenVitals-MTU/android-app)
// app/src/main/kotlin/tech/mmarca/openvitals/healthconnect/HealthConnectMappers.kt
// at commit d305e83, Copyright the OpenVitals authors, AGPL-3.0-or-later.
// Modified for Wanderer Sync.
package ru.vehq.hcsync.hc

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.ExerciseRoute
import androidx.health.connect.client.records.ExerciseRouteResult
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.vehq.hcsync.model.RoutePoint
import ru.vehq.hcsync.model.RouteResult
import ru.vehq.hcsync.model.Workout
import java.time.Instant

/** Read side of the bridge. An interface so SyncEngine can be tested without Health Connect. */
interface HealthConnectSource {
    /** All exercise sessions whose time range overlaps [from, to), oldest first. */
    suspend fun readWorkouts(from: Instant, to: Instant): List<Workout>
}

class HealthConnectClientSource(context: Context) : HealthConnectSource {
    private val client: HealthConnectClient by lazy {
        HealthConnectClient.getOrCreate(context.applicationContext)
    }

    override suspend fun readWorkouts(from: Instant, to: Instant): List<Workout> =
        withContext(Dispatchers.IO) {
            val out = ArrayList<Workout>()
            var pageToken: String? = null
            do {
                val response = client.readRecords(
                    ReadRecordsRequest(
                        recordType = ExerciseSessionRecord::class,
                        timeRangeFilter = TimeRangeFilter.between(from, to),
                        ascendingOrder = true,
                        pageToken = pageToken,
                    ),
                )
                response.records.mapTo(out) { it.toWorkout() }
                pageToken = response.pageToken
            } while (pageToken != null)
            out
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
