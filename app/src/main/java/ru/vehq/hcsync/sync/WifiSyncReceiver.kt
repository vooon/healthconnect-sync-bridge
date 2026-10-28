package ru.vehq.hcsync.sync

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import ru.vehq.hcsync.data.Settings

/** Target of the Wi-Fi PendingIntent (see [WifiTrigger]); enqueues the connect-triggered runs. */
class WifiSyncReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != WifiTrigger.ACTION_WIFI_AVAILABLE) return
        // goAsync: the settings read is a suspend call; the receiver has 10 s, DataStore needs milliseconds.
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val settings = Settings(context).read()
                if (settings.syncOnWifiConnect && settings.serverUrl.isNotBlank()) WifiTrigger.enqueueSync(context, settings)
            } finally {
                pending.finish()
            }
        }
    }
}

/**
 * Network-callback registrations do not survive a reboot (WorkManager jobs do), so re-arm the
 * Wi-Fi trigger at boot if it is enabled. Exported because BOOT_COMPLETED is a protected broadcast
 * only the system can send; the Wi-Fi receiver above stays unexported.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (Settings(context).read().syncOnWifiConnect) WifiTrigger.armIfAway(context)
            } finally {
                pending.finish()
            }
        }
    }
}
