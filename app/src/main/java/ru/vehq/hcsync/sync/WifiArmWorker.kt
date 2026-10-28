package ru.vehq.hcsync.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

/**
 * Periodic, no constraints: re-arms the one-shot Wi-Fi trigger whenever it runs while Wi-Fi is
 * down. Android has no "network lost" trigger for apps without a running process, so this poll is
 * what turns the one-shot callback into a repeatable one. Does no I/O beyond one system call.
 */
class WifiArmWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        WifiTrigger.armIfAway(applicationContext)
        return Result.success()
    }
}
