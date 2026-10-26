package ru.vehq.hcsync.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.time.Instant

data class SyncStateData(
    /** Health Connect record ids that are finished: uploaded, or permanently skipped (no route). */
    val doneIds: Set<String> = emptySet(),
    /** End time of the newest uploaded workout; informational. */
    val newestUploadedEnd: Instant? = null,
    val lastRunAt: Instant? = null,
    val lastResult: String? = null,
)

/** Persistence the sync engine needs. An interface so the engine is testable with an in-memory map. */
interface SyncStateStore {
    suspend fun load(): SyncStateData

    /** Called once per record, immediately after its upload (or its permanent skip) succeeded. */
    suspend fun markDone(id: String, uploadedEnd: Instant?)

    suspend fun recordRun(at: Instant, summary: String)
}

private val Context.syncStateStore: DataStore<Preferences> by preferencesDataStore(name = "sync_state")

class DataStoreSyncState(context: Context) : SyncStateStore {
    private val store = context.applicationContext.syncStateStore

    val data: Flow<SyncStateData> = store.data.map { it.toData() }

    override suspend fun load(): SyncStateData = data.first()

    override suspend fun markDone(id: String, uploadedEnd: Instant?) {
        store.edit { p ->
            p[KEY_DONE_IDS] = (p[KEY_DONE_IDS] ?: emptySet()) + id
            if (uploadedEnd != null) {
                val prev = p[KEY_NEWEST_END]
                if (prev == null || uploadedEnd.toEpochMilli() > prev) p[KEY_NEWEST_END] = uploadedEnd.toEpochMilli()
            }
        }
    }

    override suspend fun recordRun(at: Instant, summary: String) {
        store.edit { p ->
            p[KEY_LAST_RUN] = at.toEpochMilli()
            p[KEY_LAST_RESULT] = summary
        }
    }

    private fun Preferences.toData() = SyncStateData(
        doneIds = this[KEY_DONE_IDS] ?: emptySet(),
        newestUploadedEnd = this[KEY_NEWEST_END]?.let(Instant::ofEpochMilli),
        lastRunAt = this[KEY_LAST_RUN]?.let(Instant::ofEpochMilli),
        lastResult = this[KEY_LAST_RESULT],
    )

    private companion object {
        val KEY_DONE_IDS = stringSetPreferencesKey("done_ids")
        val KEY_NEWEST_END = longPreferencesKey("newest_uploaded_end")
        val KEY_LAST_RUN = longPreferencesKey("last_run")
        val KEY_LAST_RESULT = stringPreferencesKey("last_result")
    }
}
