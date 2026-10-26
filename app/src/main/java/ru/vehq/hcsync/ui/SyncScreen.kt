package ru.vehq.hcsync.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import ru.vehq.hcsync.hc.HealthConnectPermissions

/** Everything the single screen needs to render; the Activity owns the mutable state. */
data class SyncUiState(
    val sdkStatusText: String = "checking…",
    val sdkAvailable: Boolean = false,
    val granted: Set<String> = emptySet(),
    val busy: Boolean = false,
    val probeReport: String = "",
    val error: String? = null,
)

@Composable
fun SyncScreen(
    state: SyncUiState,
    onRequestPermissions: () -> Unit,
    onOpenHealthConnect: () -> Unit,
    onProbe: () -> Unit,
    onCopyProbe: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Health Connect", style = MaterialTheme.typography.titleMedium)
        Text("SDK: ${state.sdkStatusText}")
        PermissionRow("Read exercise sessions", HealthConnectPermissions.READ_EXERCISE in state.granted)
        PermissionRow("Read exercise routes (manual toggle)", HealthConnectPermissions.READ_EXERCISE_ROUTES in state.granted)
        PermissionRow("Read in background", HealthConnectPermissions.READ_BACKGROUND in state.granted)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onRequestPermissions, enabled = state.sdkAvailable && !state.busy) {
                Text("Grant permissions")
            }
            OutlinedButton(onClick = onOpenHealthConnect, enabled = state.sdkAvailable) {
                Text("Open Health Connect")
            }
        }
        Text(
            "Routes: in Health Connect open App permissions → Wanderer Sync → " +
                "Additional access → Exercise routes → Always allow. No app can request this for you.",
            style = MaterialTheme.typography.bodySmall,
        )

        HorizontalDivider()

        Text("Probe", style = MaterialTheme.typography.titleMedium)
        Text(
            "Lists the last 30 days of sessions with their route status. Run this once on the " +
                "phone to confirm the watch app writes routes.",
            style = MaterialTheme.typography.bodySmall,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onProbe, enabled = state.sdkAvailable && !state.busy) {
                Text(if (state.busy) "Working…" else "Probe")
            }
            OutlinedButton(onClick = onCopyProbe, enabled = state.probeReport.isNotEmpty()) {
                Text("Copy")
            }
        }
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (state.probeReport.isNotEmpty()) {
            SelectionContainer {
                Text(
                    state.probeReport,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun PermissionRow(label: String, granted: Boolean) {
    Text((if (granted) "✔ " else "✘ ") + label)
}

/** Shown when Health Connect launches us to display our privacy rationale. */
@Composable
fun PrivacyRationaleScreen(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Wanderer Sync – data use", style = MaterialTheme.typography.titleLarge)
        Text(
            "Wanderer Sync reads your exercise sessions and their GPS routes from Health Connect, " +
                "converts each one to a GPX file and uploads it to the Wanderer server you configured. " +
                "Nothing is sent anywhere else, and no data is stored outside your phone and that server.",
        )
    }
}
