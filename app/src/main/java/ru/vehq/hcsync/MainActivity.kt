package ru.vehq.hcsync

import android.content.ClipData
import android.content.ClipboardManager
import android.app.usage.UsageStatsManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings as SystemSettings
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.contracts.ExerciseRouteRequestContract
import androidx.health.connect.client.records.ExerciseRoute
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.vehq.hcsync.data.DataStoreSyncState
import ru.vehq.hcsync.data.Secrets
import ru.vehq.hcsync.data.Settings
import ru.vehq.hcsync.data.SettingsData
import ru.vehq.hcsync.hc.HealthConnectClientSource
import ru.vehq.hcsync.hc.HealthConnectPermissions
import ru.vehq.hcsync.hc.toRoutePoints
import ru.vehq.hcsync.model.RouteResult
import ru.vehq.hcsync.model.Workout
import ru.vehq.hcsync.sync.SyncEngine
import ru.vehq.hcsync.sync.SyncScheduler
import ru.vehq.hcsync.sync.SyncWorker
import ru.vehq.hcsync.ui.Notice
import ru.vehq.hcsync.ui.PrivacyRationaleScreen
import ru.vehq.hcsync.ui.SyncScreen
import ru.vehq.hcsync.ui.SyncScreenActions
import ru.vehq.hcsync.ui.SyncUiState
import ru.vehq.hcsync.ui.formatProbeReport
import ru.vehq.hcsync.wanderer.OkHttpWandererClient
import java.time.Duration
import java.time.Instant

/**
 * The one screen. State lives here (no ViewModel): the manifest keeps this Activity alive across
 * rotation, so an in-flight sync is not cancelled by a config change.
 */
class MainActivity : ComponentActivity() {
    private val permissions by lazy { HealthConnectPermissions(this) }
    private val source by lazy { HealthConnectClientSource(this) }
    private val settings by lazy { Settings(this) }
    private val secrets by lazy { Secrets(this) }
    private val syncState by lazy { DataStoreSyncState(this) }

    private var state by mutableStateOf(SyncUiState())

    // Health Connect's own permission dialog; result = the set of permissions now granted.
    private lateinit var permissionLauncher: ActivityResultLauncher<Set<String>>

    // Per-record route consent dialog (input: record id, output: the route or null if declined).
    private lateinit var routeLauncher: ActivityResultLauncher<String>
    private var pendingRoute: CompletableDeferred<ExerciseRoute?>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        permissionLauncher = registerForActivityResult(
            PermissionController.createRequestPermissionResultContract(),
        ) { refreshPermissions() }
        routeLauncher = registerForActivityResult(ExerciseRouteRequestContract()) { route ->
            pendingRoute?.complete(route)
        }

        // Health Connect opens us with these actions to show why we want the data (see manifest).
        val rationaleOnly = intent.action == RATIONALE_ACTION || intent.action == Intent.ACTION_VIEW_PERMISSION_USAGE

        val actions = SyncScreenActions(
            onServerUrlChange = { state = state.copy(serverUrl = it) },
            onTokenChange = { state = state.copy(apiToken = it) },
            onIntervalChange = { state = state.copy(intervalText = it.filter(Char::isDigit)) },
            onHistoryChange = { state = state.copy(includeHistory = it) },
            onWifiOnlyChange = { state = state.copy(wifiOnly = it) },
            onChargingOnlyChange = { state = state.copy(chargingOnly = it) },
            onSensorsChange = { state = state.copy(includeSensors = it) },
            onSave = { lifecycleScope.launch { busy(Target.SERVER) { saveSettings().let { s -> "Saved. Background sync every ${s.intervalMinutes} min" + (if (s.wifiOnly) " on Wi-Fi" else "") + (if (s.chargingOnly) " while charging" else "") + "." } } } },
            onRequestPermissions = {
                permissionLauncher.launch(permissions.requestable(includeHistory = state.includeHistory, includeSensors = state.includeSensors))
            },
            onOpenHealthConnect = ::openHealthConnect,
            onBatterySettings = ::openBatterySettings,
            onTestConnection = { lifecycleScope.launch { busy(Target.SERVER) { testConnection() } } },
            onSyncNow = { lifecycleScope.launch { busy(Target.OUTPUT) { syncNow() } } },
            onResync = { lifecycleScope.launch { busy(Target.OUTPUT) { syncNow(recheckServer = true) } } },
            onProbe = { lifecycleScope.launch { busy(Target.OUTPUT) { probe() } } },
            onCopyOutput = ::copyOutput,
        )

        setContent {
            MaterialTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    if (rationaleOnly) {
                        PrivacyRationaleScreen(Modifier.padding(innerPadding))
                    } else {
                        SyncScreen(state = state, actions = actions, modifier = Modifier.padding(innerPadding))
                    }
                }
            }
        }

        if (!rationaleOnly) {
            loadSettings()
            lifecycleScope.launch {
                syncState.data.collect {
                    state = state.copy(
                        lastRunAt = it.lastRunAt,
                        lastResult = it.lastResult,
                        uploadedTotal = it.uploadedTotal,
                        noRouteTotal = it.noRouteTotal,
                        lastSeen = it.lastSeen,
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshSdkStatus()
        refreshBatteryState()
    }

    // ---- state loading -------------------------------------------------------------------------

    private fun loadSettings() {
        lifecycleScope.launch {
            val s = settings.read()
            val token = withContext(Dispatchers.IO) { secrets.apiToken ?: "" }
            state = state.copy(
                serverUrl = s.serverUrl,
                apiToken = token,
                intervalText = s.intervalMinutes.toString(),
                includeHistory = s.includeHistory,
                wifiOnly = s.wifiOnly,
                chargingOnly = s.chargingOnly,
                includeSensors = s.includeSensors,
                settingsLoaded = true,
            )
        }
    }

    private fun refreshSdkStatus() {
        val status = permissions.sdkStatus()
        state = state.copy(
            sdkAvailable = status == HealthConnectClient.SDK_AVAILABLE,
            sdkStatusText = when (status) {
                HealthConnectClient.SDK_AVAILABLE -> "available"
                HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> "Health Connect needs an update"
                else -> "Health Connect is not available on this device"
            },
        )
        if (status == HealthConnectClient.SDK_AVAILABLE) refreshPermissions()
    }

    /** Doze/App Standby exemption and the standby bucket; shown so a deferred background job is explainable. */
    private fun refreshBatteryState() {
        val exempt = getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)
        val bucket = when (getSystemService(UsageStatsManager::class.java).appStandbyBucket) {
            5 -> "exempted" // STANDBY_BUCKET_EXEMPTED is @SystemApi, hence the literal.
            UsageStatsManager.STANDBY_BUCKET_ACTIVE -> "active"
            UsageStatsManager.STANDBY_BUCKET_WORKING_SET -> "working set"
            UsageStatsManager.STANDBY_BUCKET_FREQUENT -> "frequent"
            UsageStatsManager.STANDBY_BUCKET_RARE -> "rare"
            UsageStatsManager.STANDBY_BUCKET_RESTRICTED -> "restricted"
            else -> "unknown"
        }
        state = state.copy(batteryUnrestricted = exempt, standbyBucket = bucket)
    }

    private fun refreshPermissions() {
        lifecycleScope.launch {
            runCatching { permissions.granted() }
                .onSuccess { state = state.copy(granted = it) }
                .onFailure { state = state.copy(syncNotice = Notice("Reading permissions failed: ${it.message}", isError = true)) }
        }
    }

    // ---- actions -------------------------------------------------------------------------------

    /** Where an action reports: a one-line notice under the server buttons, or the sync output area. */
    private enum class Target { SERVER, OUTPUT }

    /** Runs [block] with the busy flag set; its return value (or error) is shown at [target]. */
    private suspend fun busy(target: Target, block: suspend () -> String) {
        state = when (target) {
            Target.SERVER -> state.copy(busy = true, serverNotice = Notice("Working…"))
            Target.OUTPUT -> state.copy(busy = true, syncNotice = Notice("Working…"))
        }
        val notice = try {
            val text = block()
            when (target) {
                Target.SERVER -> Notice(text)
                Target.OUTPUT -> { state = state.copy(output = text); null }
            }
        } catch (e: Exception) {
            Log.w(TAG, "action failed", e)
            Notice(e.message ?: e.javaClass.simpleName, isError = true)
        }
        state = when (target) {
            Target.SERVER -> state.copy(busy = false, serverNotice = notice)
            Target.OUTPUT -> state.copy(busy = false, syncNotice = notice)
        }
    }

    private fun intervalMinutes(): Int =
        (state.intervalText.toIntOrNull() ?: SettingsData.DEFAULT_INTERVAL_MINUTES).coerceAtLeast(SettingsData.MIN_INTERVAL_MINUTES)

    private suspend fun saveSettings(): SettingsData {
        val interval = intervalMinutes()
        val saved = settings.update {
            it.copy(
                serverUrl = state.serverUrl.trim(),
                intervalMinutes = interval,
                includeHistory = state.includeHistory,
                wifiOnly = state.wifiOnly,
                chargingOnly = state.chargingOnly,
                includeSensors = state.includeSensors,
            )
        }
        withContext(Dispatchers.IO) { secrets.apiToken = state.apiToken }
        state = state.copy(intervalText = interval.toString())
        SyncScheduler.schedule(this, saved)
        return saved
    }

    private fun client(saved: SettingsData) = OkHttpWandererClient(saved.serverUrl, { secrets.apiToken })

    private suspend fun testConnection(): String {
        val saved = saveSettings()
        require(saved.serverUrl.isNotBlank()) { "Enter the server URL first" }
        return client(saved).testConnection()
    }

    private suspend fun syncNow(recheckServer: Boolean = false): String {
        val saved = saveSettings()
        require(saved.serverUrl.isNotBlank()) { "Enter the server URL first" }
        val granted = permissions.granted()
        require(HealthConnectPermissions.READ_EXERCISE in granted) { "Grant the exercise permission first" }
        val engine = SyncEngine(source, client(saved), syncState, log = { Log.i(TAG, it) })
        val lookback = SyncWorker.lookbackFor(saved.includeHistory, HealthConnectPermissions.READ_HISTORY in granted)
        val result = engine.run(
            lookback,
            includeSensors = saved.includeSensors,
            recheckServer = recheckServer,
            consent = ::askRouteConsent,
        )
        refreshPermissions()
        return buildString {
            append(result.summary()).append('\n')
            if (result.alreadyDone > 0) append(result.alreadyDone).append(" already uploaded earlier\n")
            result.uploadedTrailIds.forEach { append("trail ").append(it).append('\n') }
        }
    }

    /** Foreground only: shows Health Connect's per-record route dialog and waits for the answer. */
    private suspend fun askRouteConsent(workout: Workout): RouteResult? {
        val deferred = CompletableDeferred<ExerciseRoute?>()
        pendingRoute = deferred
        routeLauncher.launch(workout.id)
        val route = deferred.await()
        pendingRoute = null
        return route?.let { RouteResult.Data(it.toRoutePoints()) }
    }

    private suspend fun probe(): String {
        val now = Instant.now()
        val workouts = source.readWorkouts(now.minus(Duration.ofDays(30)), now)
        Log.i(TAG, "probe: ${workouts.size} session(s)")
        val sensors = if (state.includeSensors) {
            workouts.associate { it.id to source.readSensorSamples(it.startTime, it.endTime) }
        } else {
            emptyMap()
        }
        return formatProbeReport(workouts, sensors = sensors)
    }

    private fun openHealthConnect() {
        // Deep link into Health Connect's settings; the route toggle lives under Additional access.
        runCatching { startActivity(Intent(HealthConnectClient.ACTION_HEALTH_CONNECT_SETTINGS)) }
            .onFailure { state = state.copy(syncNotice = Notice("Cannot open Health Connect: ${it.message}", isError = true)) }
    }

    private fun openBatterySettings() {
        // Not exempt: the system "let app always run in background?" dialog sets Unrestricted in one tap
        // (needs REQUEST_IGNORE_BATTERY_OPTIMIZATIONS in the manifest). Exempt: the app's info page, where
        // Battery → Optimized reverts it. Never asked automatically; the user decides from the screen.
        val action = if (state.batteryUnrestricted) {
            SystemSettings.ACTION_APPLICATION_DETAILS_SETTINGS
        } else {
            SystemSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
        }
        runCatching { startActivity(Intent(action, Uri.parse("package:$packageName"))) }
            .onFailure { state = state.copy(serverNotice = Notice("Cannot open battery settings: ${it.message}", isError = true)) }
    }

    private fun copyOutput() {
        val clipboard = getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText("Wanderer Sync", state.output))
        Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
    }

    private companion object {
        const val TAG = "WandererSync"
        const val RATIONALE_ACTION = "androidx.health.ACTION_SHOW_PERMISSIONS_RATIONALE"
    }
}
