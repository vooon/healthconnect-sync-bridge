package ru.vehq.hcsync.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import ru.vehq.hcsync.hc.HealthConnectPermissions
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** A short message shown next to the buttons that produced it. */
data class Notice(val text: String, val isError: Boolean = false)

/** Everything the single screen renders; the Activity owns the mutable copy. */
data class SyncUiState(
    val sdkStatusText: String = "checking…",
    val sdkAvailable: Boolean = false,
    val granted: Set<String> = emptySet(),
    // Settings fields as typed (unsaved until Save / Test / Sync).
    val serverUrl: String = "",
    val apiToken: String = "",
    val intervalText: String = "60",
    val includeHistory: Boolean = false,
    val wifiOnly: Boolean = true,
    val chargingOnly: Boolean = false,
    val settingsLoaded: Boolean = false,
    // Status.
    val serverNotice: Notice? = null,
    val syncNotice: Notice? = null,
    val lastRunAt: Instant? = null,
    val lastResult: String? = null,
    val uploadedTotal: Int = 0,
    val noRouteTotal: Int = 0,
    val lastSeen: Int = 0,
    val busy: Boolean = false,
    val output: String = "",
)

class SyncScreenActions(
    val onServerUrlChange: (String) -> Unit,
    val onTokenChange: (String) -> Unit,
    val onIntervalChange: (String) -> Unit,
    val onHistoryChange: (Boolean) -> Unit,
    val onWifiOnlyChange: (Boolean) -> Unit,
    val onChargingOnlyChange: (Boolean) -> Unit,
    val onSave: () -> Unit,
    val onRequestPermissions: () -> Unit,
    val onOpenHealthConnect: () -> Unit,
    val onTestConnection: () -> Unit,
    val onSyncNow: () -> Unit,
    val onProbe: () -> Unit,
    val onCopyOutput: () -> Unit,
)

private val statusTimeFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

@Composable
fun SyncScreen(state: SyncUiState, actions: SyncScreenActions, modifier: Modifier = Modifier) {
    val canAct = state.sdkAvailable && !state.busy && state.settingsLoaded
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Wanderer server", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            value = state.serverUrl,
            onValueChange = actions.onServerUrlChange,
            label = { Text("Server URL") },
            placeholder = { Text("https://wanderer.example.com") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = state.apiToken,
            onValueChange = actions.onTokenChange,
            label = { Text("API token (Settings → Account → API tokens)") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = state.intervalText,
            onValueChange = actions.onIntervalChange,
            label = { Text("Sync interval, minutes (min 15)") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )
        ToggleRow("Background sync only on Wi-Fi", state.wifiOnly, actions.onWifiOnlyChange)
        ToggleRow("Background sync only while charging", state.chargingOnly, actions.onChargingOnlyChange)
        ToggleRow("Include workouts older than 30 days (needs the history permission)", state.includeHistory, actions.onHistoryChange)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = actions.onSave, enabled = !state.busy && state.settingsLoaded) { Text("Save & schedule") }
            OutlinedButton(onClick = actions.onTestConnection, enabled = canAct) { Text("Test connection") }
        }
        NoticeText(state.serverNotice)

        HorizontalDivider()

        Text("Health Connect", style = MaterialTheme.typography.titleMedium)
        Text("SDK: ${state.sdkStatusText}")
        PermissionRow("Read exercise sessions", HealthConnectPermissions.READ_EXERCISE in state.granted)
        PermissionRow("Read exercise routes (manual toggle)", HealthConnectPermissions.READ_EXERCISE_ROUTES in state.granted)
        PermissionRow("Read in background", HealthConnectPermissions.READ_BACKGROUND in state.granted)
        if (state.includeHistory) {
            PermissionRow("Read history (older than 30 days)", HealthConnectPermissions.READ_HISTORY in state.granted)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = actions.onRequestPermissions, enabled = canAct) { Text("Grant permissions") }
            OutlinedButton(onClick = actions.onOpenHealthConnect, enabled = state.sdkAvailable) { Text("Open Health Connect") }
        }
        Text(
            "Routes: in Health Connect open App permissions → Wanderer Sync → " +
                "Additional access → Exercise routes → Always allow. No app can request this for you.",
            style = MaterialTheme.typography.bodySmall,
        )

        HorizontalDivider()

        Text("Sync", style = MaterialTheme.typography.titleMedium)
        Text("Uploaded ${state.uploadedTotal} trail(s) in total · ${state.noRouteTotal} session(s) had no route")
        Text("Last run: " + (state.lastRunAt?.let { statusTimeFormat.format(it.atZone(ZoneId.systemDefault())) } ?: "never") +
            (state.lastRunAt?.let { " · ${state.lastSeen} session(s) in window" } ?: ""))
        Text("Last result: " + (state.lastResult ?: "–"))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = actions.onSyncNow, enabled = canAct) { Text(if (state.busy) "Working…" else "Sync now") }
            OutlinedButton(onClick = actions.onProbe, enabled = canAct) { Text("Probe") }
            OutlinedButton(onClick = actions.onCopyOutput, enabled = state.output.isNotEmpty()) { Text("Copy") }
        }
        NoticeText(state.syncNotice)
        if (state.output.isNotEmpty()) {
            SelectionContainer {
                Text(
                    state.output,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun NoticeText(notice: Notice?) {
    if (notice != null) {
        Text(notice.text, color = if (notice.isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Switch(checked = checked, onCheckedChange = onChange)
        Text(label)
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
