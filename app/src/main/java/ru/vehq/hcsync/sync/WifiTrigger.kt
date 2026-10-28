package ru.vehq.hcsync.sync

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import ru.vehq.hcsync.data.SettingsData
import java.time.Duration

/**
 * "Sync when Wi-Fi connects": run the sync as soon as the phone is back on Wi-Fi (e.g. home
 * after a walk) instead of waiting for the next period, and once more [RETRY_DELAY] later for
 * apps that write the session to Health Connect late.
 *
 * Mechanism: `ConnectivityManager.registerNetworkCallback(NetworkRequest, PendingIntent)`. The
 * registration outlives our process, so no service is needed, but it is one-shot: the system
 * sends the intent once (AOSP `ConnectivityService.sendPendingIntentForRequest`, flag
 * `mPendingIntentSent`) and then releases the request. Arming it while Wi-Fi is already up
 * fires immediately, so it is only ever armed while Wi-Fi is absent: by [WifiArmWorker] (periodic,
 * no constraints) and by [BootReceiver]. [WifiSyncReceiver] receives the intent and enqueues the runs.
 */
object WifiTrigger {
    const val ACTION_WIFI_AVAILABLE = "ru.vehq.hcsync.WIFI_AVAILABLE"
    const val NOW_WORK_NAME = "wanderer-sync-wifi-now"
    const val LATER_WORK_NAME = "wanderer-sync-wifi-later"

    /** Second run after a Wi-Fi connect, for sessions the recording app writes with a delay. */
    val RETRY_DELAY: Duration = Duration.ofMinutes(10)

    private const val TAG = "SyncWorker"

    // FLAG_IMMUTABLE drops the system's fill-in extras (EXTRA_NETWORK); we do not read them.
    private fun pendingIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context,
        0,
        Intent(context, WifiSyncReceiver::class.java).setAction(ACTION_WIFI_AVAILABLE),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun connectivity(context: Context): ConnectivityManager =
        context.applicationContext.getSystemService(ConnectivityManager::class.java)

    fun isWifiUp(context: Context): Boolean {
        val cm = connectivity(context)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork ?: return false) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    /** Arms the one-shot callback unless Wi-Fi is up right now; returns whether it is armed. */
    fun armIfAway(context: Context): Boolean {
        if (isWifiUp(context)) return false
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        // An equal PendingIntent (Intent.filterEquals) replaces the earlier registration, so this is idempotent.
        connectivity(context).registerNetworkCallback(request, pendingIntent(context))
        Log.i(TAG, "Wi-Fi trigger armed")
        return true
    }

    fun disarm(context: Context) {
        // Throws if nothing is registered; that is the state we want anyway.
        runCatching { connectivity(context).unregisterNetworkCallback(pendingIntent(context)) }
    }

    /** One run now (expedited when the constraints allow it) and one [RETRY_DELAY] later. */
    fun enqueueSync(context: Context, settings: SettingsData) {
        val constraints = SyncScheduler.constraints(settings)
        val now = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, SyncScheduler.BACKOFF)
            // Expedited jobs run promptly even in a low App Standby bucket, but accept only network
            // (and storage) constraints, so a charging constraint means an ordinary request.
            .apply { if (!settings.chargingOnly) setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST) }
            .build()
        val later = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(constraints)
            .setInitialDelay(RETRY_DELAY)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, SyncScheduler.BACKOFF)
            .build()
        val wm = WorkManager.getInstance(context.applicationContext)
        // KEEP: a second connect while a run is pending or running must not cancel it.
        wm.enqueueUniqueWork(NOW_WORK_NAME, ExistingWorkPolicy.KEEP, now)
        wm.enqueueUniqueWork(LATER_WORK_NAME, ExistingWorkPolicy.KEEP, later)
        Log.i(TAG, "Wi-Fi connected: sync enqueued now and in ${RETRY_DELAY.toMinutes()} min")
    }
}
