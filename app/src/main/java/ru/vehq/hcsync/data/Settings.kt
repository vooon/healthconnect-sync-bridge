package ru.vehq.hcsync.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

data class SettingsData(
    val serverUrl: String = "",
    val intervalMinutes: Int = DEFAULT_INTERVAL_MINUTES,
    /** Ask for READ_HEALTH_DATA_HISTORY and sync everything, not only the last 30 days. */
    val includeHistory: Boolean = false,
    /** Background runs only on an unmetered network (Wi-Fi), never on mobile data. */
    val wifiOnly: Boolean = true,
    /** Background runs only while charging. */
    val chargingOnly: Boolean = false,
    /** Merge heart rate and cadence into the GPX (needs the heart-rate and steps permissions). */
    val includeSensors: Boolean = true,
) {
    companion object {
        const val DEFAULT_INTERVAL_MINUTES = 60

        /** WorkManager refuses periodic work more frequent than this. */
        const val MIN_INTERVAL_MINUTES = 15
    }
}

// One DataStore file per process; the delegate must live at top level so every Settings instance shares it.
private val Context.settingsStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/** Non-secret user settings. The API token lives in [Secrets], not here. */
class Settings(context: Context) {
    private val store = context.applicationContext.settingsStore

    val data: Flow<SettingsData> = store.data.map { p ->
        SettingsData(
            serverUrl = p[KEY_SERVER_URL] ?: "",
            intervalMinutes = p[KEY_INTERVAL] ?: SettingsData.DEFAULT_INTERVAL_MINUTES,
            includeHistory = p[KEY_HISTORY] ?: false,
            wifiOnly = p[KEY_WIFI_ONLY] ?: true,
            chargingOnly = p[KEY_CHARGING_ONLY] ?: false,
            includeSensors = p[KEY_SENSORS] ?: true,
        )
    }

    suspend fun read(): SettingsData = data.first()

    suspend fun update(block: (SettingsData) -> SettingsData): SettingsData {
        var result = SettingsData()
        store.edit { p ->
            val current = SettingsData(
                serverUrl = p[KEY_SERVER_URL] ?: "",
                intervalMinutes = p[KEY_INTERVAL] ?: SettingsData.DEFAULT_INTERVAL_MINUTES,
                includeHistory = p[KEY_HISTORY] ?: false,
                wifiOnly = p[KEY_WIFI_ONLY] ?: true,
                chargingOnly = p[KEY_CHARGING_ONLY] ?: false,
                includeSensors = p[KEY_SENSORS] ?: true,
            )
            result = block(current)
            p[KEY_SERVER_URL] = result.serverUrl
            p[KEY_INTERVAL] = result.intervalMinutes
            p[KEY_HISTORY] = result.includeHistory
            p[KEY_WIFI_ONLY] = result.wifiOnly
            p[KEY_CHARGING_ONLY] = result.chargingOnly
            p[KEY_SENSORS] = result.includeSensors
        }
        return result
    }

    private companion object {
        val KEY_SERVER_URL = stringPreferencesKey("server_url")
        val KEY_INTERVAL = intPreferencesKey("interval_minutes")
        val KEY_HISTORY = booleanPreferencesKey("include_history")
        val KEY_WIFI_ONLY = booleanPreferencesKey("wifi_only")
        val KEY_CHARGING_ONLY = booleanPreferencesKey("charging_only")
        val KEY_SENSORS = booleanPreferencesKey("include_sensors")
    }
}
