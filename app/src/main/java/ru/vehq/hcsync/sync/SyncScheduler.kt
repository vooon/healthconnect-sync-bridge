package ru.vehq.hcsync.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import ru.vehq.hcsync.data.SettingsData
import java.time.Duration

/**
 * Keeps exactly one periodic SyncWorker enqueued, plus the Wi-Fi trigger's arming job when that
 * setting is on; call again whenever settings change.
 */
object SyncScheduler {
    const val WORK_NAME = "wanderer-sync"
    const val ARM_WORK_NAME = "wanderer-sync-wifi-arm"

    val BACKOFF: Duration = Duration.ofMinutes(10)

    /** WorkManager's minimum period; the arming poll has no constraints and does no I/O, so it is cheap. */
    private val ARM_PERIOD: Duration = Duration.ofMinutes(15)

    fun constraints(settings: SettingsData): Constraints = Constraints.Builder()
        // Uploads need a network; UNMETERED = Wi-Fi (or a hotspot the OS believes is unmetered).
        .setRequiredNetworkType(if (settings.wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
        .setRequiresCharging(settings.chargingOnly)
        .build()

    fun schedule(context: Context, settings: SettingsData) {
        val minutes = settings.intervalMinutes.coerceAtLeast(SettingsData.MIN_INTERVAL_MINUTES).toLong()
        val request = PeriodicWorkRequestBuilder<SyncWorker>(Duration.ofMinutes(minutes))
            .setConstraints(constraints(settings))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF)
            .build()
        val wm = WorkManager.getInstance(context.applicationContext)
        // UPDATE keeps the existing schedule position but applies the new interval/constraints.
        wm.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)

        if (settings.syncOnWifiConnect) {
            val arm = PeriodicWorkRequestBuilder<WifiArmWorker>(ARM_PERIOD).build()
            wm.enqueueUniquePeriodicWork(ARM_WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, arm)
            WifiTrigger.armIfAway(context)
        } else {
            wm.cancelUniqueWork(ARM_WORK_NAME)
            WifiTrigger.disarm(context)
        }
    }

    fun cancel(context: Context) {
        val wm = WorkManager.getInstance(context.applicationContext)
        wm.cancelUniqueWork(WORK_NAME)
        wm.cancelUniqueWork(ARM_WORK_NAME)
        WifiTrigger.disarm(context)
    }
}
