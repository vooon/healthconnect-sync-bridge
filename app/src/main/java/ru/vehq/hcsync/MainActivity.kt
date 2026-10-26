package ru.vehq.hcsync

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
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
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import ru.vehq.hcsync.hc.HealthConnectClientSource
import ru.vehq.hcsync.hc.HealthConnectPermissions
import ru.vehq.hcsync.ui.PrivacyRationaleScreen
import ru.vehq.hcsync.ui.SyncScreen
import ru.vehq.hcsync.ui.SyncUiState
import ru.vehq.hcsync.ui.formatProbeReport
import java.time.Duration
import java.time.Instant

class MainActivity : ComponentActivity() {
    private val permissions by lazy { HealthConnectPermissions(this) }
    private val source by lazy { HealthConnectClientSource(this) }

    private var state by mutableStateOf(SyncUiState())

    // Health Connect's own permission dialog; result = the set of permissions now granted.
    private lateinit var permissionLauncher: ActivityResultLauncher<Set<String>>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        permissionLauncher = registerForActivityResult(
            PermissionController.createRequestPermissionResultContract(),
        ) { refreshPermissions() }

        // Health Connect opens us with these actions to show why we want the data (see manifest).
        val rationaleOnly = intent.action == RATIONALE_ACTION || intent.action == Intent.ACTION_VIEW_PERMISSION_USAGE

        setContent {
            MaterialTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    if (rationaleOnly) {
                        PrivacyRationaleScreen(Modifier.padding(innerPadding))
                    } else {
                        SyncScreen(
                            state = state,
                            onRequestPermissions = { permissionLauncher.launch(permissions.requestable(includeHistory = false)) },
                            onOpenHealthConnect = ::openHealthConnect,
                            onProbe = ::runProbe,
                            onCopyProbe = ::copyProbe,
                            modifier = Modifier.padding(innerPadding),
                        )
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshSdkStatus()
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

    private fun refreshPermissions() {
        lifecycleScope.launch {
            runCatching { permissions.granted() }
                .onSuccess { state = state.copy(granted = it, error = null) }
                .onFailure { state = state.copy(error = "Reading permissions failed: ${it.message}") }
        }
    }

    private fun openHealthConnect() {
        // Deep link into Health Connect's settings; the route toggle lives under Additional access.
        runCatching { startActivity(Intent(HealthConnectClient.ACTION_HEALTH_CONNECT_SETTINGS)) }
            .onFailure { state = state.copy(error = "Cannot open Health Connect: ${it.message}") }
    }

    private fun runProbe() {
        state = state.copy(busy = true, error = null)
        lifecycleScope.launch {
            val now = Instant.now()
            runCatching { source.readWorkouts(now.minus(Duration.ofDays(30)), now) }
                .onSuccess { workouts ->
                    Log.i(TAG, "probe: ${workouts.size} session(s)")
                    state = state.copy(busy = false, probeReport = formatProbeReport(workouts))
                }
                .onFailure { e ->
                    Log.w(TAG, "probe failed", e)
                    state = state.copy(busy = false, error = "Probe failed: ${e.message}")
                }
        }
    }

    private fun copyProbe() {
        val clipboard = getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText("probe", state.probeReport))
        Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
    }

    private companion object {
        const val TAG = "WandererSync"
        const val RATIONALE_ACTION = "androidx.health.ACTION_SHOW_PERMISSIONS_RATIONALE"
    }
}
