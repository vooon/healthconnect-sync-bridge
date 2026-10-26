package ru.vehq.hcsync.model

import java.time.Instant
import java.time.ZoneOffset

/** A Health Connect exercise session reduced to what the sync needs. Pure Kotlin, no Android types. */
data class Workout(
    /** Health Connect record UUID; the dedup key. */
    val id: String,
    /** `ExerciseSessionRecord.EXERCISE_TYPE_*` constant. */
    val exerciseType: Int,
    val title: String?,
    val startTime: Instant,
    val endTime: Instant,
    /** Offset the session was recorded in, used for the human-readable GPX name. */
    val startZoneOffset: ZoneOffset?,
    /** Package that wrote the record (e.g. the watch companion app). */
    val sourcePackage: String,
    val route: RouteResult,
)

sealed interface RouteResult {
    data class Data(val points: List<RoutePoint>) : RouteResult

    /** Route exists but this app may not read it without per-record consent. */
    data object ConsentRequired : RouteResult

    /** The writing app stored no route; nothing to retry. */
    data object NoData : RouteResult
}

data class RoutePoint(
    val time: Instant,
    val latitude: Double,
    val longitude: Double,
    val altitudeMeters: Double?,
    val horizontalAccuracyMeters: Double?,
)
