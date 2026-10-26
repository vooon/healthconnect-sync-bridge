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

/** Keeps exactly one periodic SyncWorker enqueued; call again whenever the interval changes. */
object SyncScheduler {
    const val WORK_NAME = "wanderer-sync"

    fun schedule(context: Context, intervalMinutes: Int) {
        val minutes = intervalMinutes.coerceAtLeast(SettingsData.MIN_INTERVAL_MINUTES).toLong()
        val request = PeriodicWorkRequestBuilder<SyncWorker>(Duration.ofMinutes(minutes))
            // Uploads need a network; Health Connect reads do not, but there is nothing to do without both.
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, Duration.ofMinutes(10))
            .build()
        WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
            WORK_NAME,
            // UPDATE keeps the existing schedule position but applies the new interval/constraints.
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context.applicationContext).cancelUniqueWork(WORK_NAME)
    }
}
