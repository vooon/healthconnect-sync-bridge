package ru.vehq.hcsync.model

import androidx.health.connect.client.records.ExerciseSessionRecord as R

/** Human label for the common outdoor `EXERCISE_TYPE_*` values; everything else is "Workout". */
fun exerciseTypeLabel(type: Int): String = when (type) {
    R.EXERCISE_TYPE_WALKING -> "Walk"
    R.EXERCISE_TYPE_HIKING -> "Hike"
    R.EXERCISE_TYPE_RUNNING -> "Run"
    R.EXERCISE_TYPE_RUNNING_TREADMILL -> "Treadmill run"
    R.EXERCISE_TYPE_BIKING -> "Ride"
    R.EXERCISE_TYPE_BIKING_STATIONARY -> "Stationary ride"
    R.EXERCISE_TYPE_SWIMMING_OPEN_WATER -> "Open water swim"
    R.EXERCISE_TYPE_SKIING -> "Ski"
    R.EXERCISE_TYPE_SNOWBOARDING -> "Snowboard"
    R.EXERCISE_TYPE_SKATING -> "Skate"
    R.EXERCISE_TYPE_ROWING -> "Row"
    R.EXERCISE_TYPE_PADDLING -> "Paddle"
    R.EXERCISE_TYPE_SAILING -> "Sail"
    R.EXERCISE_TYPE_SURFING -> "Surf"
    R.EXERCISE_TYPE_GOLF -> "Golf"
    R.EXERCISE_TYPE_WHEELCHAIR -> "Wheelchair"
    else -> "Workout"
}
