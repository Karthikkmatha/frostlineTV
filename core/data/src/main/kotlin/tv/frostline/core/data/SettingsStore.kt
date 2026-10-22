package tv.frostline.core.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Plain (non-secret) app settings. */
class SettingsStore(private val store: DataStore<Preferences>) {
    /** Follow raids automatically after a 10 s notice. Default on. */
    val followRaids: Flow<Boolean> = store.data.map { it[FOLLOW_RAIDS] ?: true }

    /** Playback stats overlay (Settings > Developer). Default off. */
    val debugOverlay: Flow<Boolean> = store.data.map { it[DEBUG_OVERLAY] ?: false }

    suspend fun setFollowRaids(value: Boolean) {
        store.edit { it[FOLLOW_RAIDS] = value }
    }

    suspend fun setDebugOverlay(value: Boolean) {
        store.edit { it[DEBUG_OVERLAY] = value }
    }

    private companion object {
        val FOLLOW_RAIDS = booleanPreferencesKey("follow_raids_v1")
        val DEBUG_OVERLAY = booleanPreferencesKey("debug_overlay_v1")
    }
}

fun createSettings(context: android.content.Context): SettingsStore =
    SettingsStore(context.applicationContext.vaultDataStore())
