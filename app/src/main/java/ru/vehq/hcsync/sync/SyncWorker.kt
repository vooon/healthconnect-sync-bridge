package ru.vehq.hcsync.sync

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ru.vehq.hcsync.data.DataStoreSyncState
import ru.vehq.hcsync.data.Secrets
import ru.vehq.hcsync.data.Settings
import ru.vehq.hcsync.hc.HealthConnectClientSource
import ru.vehq.hcsync.hc.HealthConnectPermissions
import ru.vehq.hcsync.wanderer.OkHttpWandererClient
import java.time.Duration
import java.time.Instant

/**
 * Background sync, run periodically (SyncScheduler) and after a Wi-Fi connect (WifiTrigger).
 * WorkManager instantiates this by class name, so all dependencies are built here from the
 * application context.
 */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    // The periodic and the Wi-Fi-triggered requests have different unique names, so WorkManager may
    // start two at once; both run in this process, and two engines over the same records would
    // upload duplicates. The second waits here.
    override suspend fun doWork(): Result = runLock.withLock { doWorkLocked() }

    private suspend fun doWorkLocked(): Result {
        val context = applicationContext
        val settings = Settings(context).read()
        val secrets = Secrets(context)
        val state = DataStoreSyncState(context)
        val permissions = HealthConnectPermissions(context)

        fun bail(reason: String): Result {
            Log.w(TAG, "skipping run: $reason")
            // Recorded so the screen shows why nothing happened; not retried, the user must act.
            return Result.success()
        }

        if (settings.serverUrl.isBlank()) return bail("no server URL configured")
        if (secrets.apiToken == null) return bail("no API token configured")

        val granted = runCatching { permissions.granted() }.getOrElse { return bail("cannot read Health Connect permissions: ${it.message}") }
        if (HealthConnectPermissions.READ_EXERCISE !in granted) return bail("READ_EXERCISE not granted")
        if (HealthConnectPermissions.READ_BACKGROUND !in granted) {
            state.recordRun(Instant.now(), "failed: background read permission not granted", seen = 0)
            return bail("READ_HEALTH_DATA_IN_BACKGROUND not granted")
        }

        val engine = SyncEngine(
            source = HealthConnectClientSource(context),
            client = OkHttpWandererClient(settings.serverUrl, { secrets.apiToken }),
            state = state,
            log = { Log.i(TAG, it) },
        )
        val lookback = lookbackFor(settings.includeHistory, HealthConnectPermissions.READ_HISTORY in granted)

        val result = try {
            engine.run(lookback, includeSensors = settings.includeSensors, budget = RUN_BUDGET)
        } catch (e: Exception) {
            // Reading Health Connect itself failed (e.g. SecurityException); engine never persisted a run.
            Log.w(TAG, "sync failed before upload", e)
            state.recordRun(Instant.now(), "failed: ${e.message ?: e.javaClass.simpleName}", seen = 0)
            return Result.success()
        }
        Log.i(TAG, "run finished: ${result.summary()}")
        // retry(): WorkManager re-runs after backoff, i.e. soon, instead of waiting a full interval.
        return if (result.retryable || result.moreWork) Result.retry() else Result.success()
    }

    companion object {
        /** Tag to filter with: `adb logcat -s SyncWorker`. */
        const val TAG = "SyncWorker"

        private val runLock = Mutex()

        /** WorkManager stops a worker after 10 min; stop starting uploads well before that. */
        val RUN_BUDGET: Duration = Duration.ofMinutes(6)

        /** 30 days is all Health Connect allows without READ_HEALTH_DATA_HISTORY. */
        fun lookbackFor(includeHistory: Boolean, historyGranted: Boolean): Duration =
            if (includeHistory && historyGranted) Duration.ofDays(365L * 10) else Duration.ofDays(30)
    }
}
