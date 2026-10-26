package ru.vehq.hcsync.hc

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.HealthConnectFeatures
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ExerciseSessionRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Which Health Connect permissions we have, and which ones can even be asked for. */
class HealthConnectPermissions(context: Context) {
    private val appContext = context.applicationContext

    /** `HealthConnectClient.SDK_*`; anything but SDK_AVAILABLE means stop and tell the user. */
    fun sdkStatus(): Int = HealthConnectClient.getSdkStatus(appContext)

    private val client: HealthConnectClient by lazy { HealthConnectClient.getOrCreate(appContext) }

    private fun featureAvailable(feature: Int): Boolean =
        client.features.getFeatureStatus(feature) == HealthConnectFeatures.FEATURE_STATUS_AVAILABLE

    /** True when this Health Connect build lets WorkManager read data while the app is in background. */
    fun backgroundReadAvailable(): Boolean =
        featureAvailable(HealthConnectFeatures.FEATURE_READ_HEALTH_DATA_IN_BACKGROUND)

    /** True when this Health Connect build can grant access to data older than 30 days. */
    fun historyReadAvailable(): Boolean =
        featureAvailable(HealthConnectFeatures.FEATURE_READ_HEALTH_DATA_HISTORY)

    /**
     * Permissions the request dialog can grant. READ_EXERCISE_ROUTES is deliberately absent:
     * the dialog ignores it; the user must flip it under Health Connect → Additional access.
     */
    fun requestable(includeHistory: Boolean): Set<String> = buildSet {
        add(READ_EXERCISE)
        if (backgroundReadAvailable()) add(HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND)
        if (includeHistory && historyReadAvailable()) add(HealthPermission.PERMISSION_READ_HEALTH_DATA_HISTORY)
    }

    suspend fun granted(): Set<String> = withContext(Dispatchers.IO) {
        client.permissionController.getGrantedPermissions()
    }

    companion object {
        val READ_EXERCISE: String = HealthPermission.getReadPermission(ExerciseSessionRecord::class)

        /** No constant for this in connect-client 1.1.0, so the literal from the manifest. */
        const val READ_EXERCISE_ROUTES = "android.permission.health.READ_EXERCISE_ROUTES"
        const val READ_BACKGROUND = HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND
        const val READ_HISTORY = HealthPermission.PERMISSION_READ_HEALTH_DATA_HISTORY
    }
}
