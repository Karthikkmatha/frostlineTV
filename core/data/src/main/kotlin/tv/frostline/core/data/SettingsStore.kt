package tv.frostline.core.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Chat panel preferences. The font index refers to ChatOptions.fontSizesSp. */
data class ChatSettings(
    val enabled: Boolean = true,
    /** Panel width in percent of the screen (20-45), set by the resize control. */
    val widthPct: Int = ChatOptions.DEFAULT_WIDTH_PCT,
    val fontIndex: Int = 1,
    val timestamps: Boolean = false,
    val show7tv: Boolean = true,
    val showBttv: Boolean = true,
    val showFfz: Boolean = true,
    /** Animated emotes play (at a throttled frame rate). Off shows the first frame only. */
    val animatedEmotes: Boolean = true,
)

/** Plain (non-secret) app settings. */
class SettingsStore(private val store: DataStore<Preferences>) {
    /** Follow raids automatically after a 10 s notice. Default on. */
    val followRaids: Flow<Boolean> = store.data.map { it[FOLLOW_RAIDS] ?: true }

    /** Claim the channel-points bonus chest automatically while watching (signed in). Default on. */
    val autoClaimPoints: Flow<Boolean> = store.data.map { it[AUTO_CLAIM] ?: true }

    /** Playback stats overlay (Settings > Developer). Default off. */
    val debugOverlay: Flow<Boolean> = store.data.map { it[DEBUG_OVERLAY] ?: false }

    /** Shows the measured UI frame rate in the debug overlay. Default off. */
    val showFps: Flow<Boolean> = store.data.map { it[SHOW_FPS] ?: false }

    val chat: Flow<ChatSettings> = store.data.map { it.toChat() }

    suspend fun updateChat(transform: (ChatSettings) -> ChatSettings) {
        store.edit { p ->
            val n = transform(p.toChat())
            p[CHAT_ENABLED] = n.enabled
            p[CHAT_WIDTH_PCT] = ChatOptions.clampWidth(n.widthPct)
            p[CHAT_FONT] = n.fontIndex.coerceIn(0, 3)
            p[CHAT_TIME] = n.timestamps
            p[CHAT_7TV] = n.show7tv
            p[CHAT_BTTV] = n.showBttv
            p[CHAT_FFZ] = n.showFfz
            p[CHAT_ANIMATED] = n.animatedEmotes
        }
    }

    private fun Preferences.toChat() = ChatSettings(
        enabled = this[CHAT_ENABLED] ?: true,
        widthPct = ChatOptions.clampWidth(this[CHAT_WIDTH_PCT] ?: ChatOptions.fromLegacyIndex(this[CHAT_WIDTH] ?: 1)),
        fontIndex = (this[CHAT_FONT] ?: 1).coerceIn(0, 3),
        timestamps = this[CHAT_TIME] ?: false,
        show7tv = this[CHAT_7TV] ?: true,
        showBttv = this[CHAT_BTTV] ?: true,
        showFfz = this[CHAT_FFZ] ?: true,
        animatedEmotes = this[CHAT_ANIMATED] ?: true,
    )

    suspend fun setFollowRaids(value: Boolean) {
        store.edit { it[FOLLOW_RAIDS] = value }
    }

    suspend fun setAutoClaimPoints(value: Boolean) {
        store.edit { it[AUTO_CLAIM] = value }
    }

    suspend fun setDebugOverlay(value: Boolean) {
        store.edit { it[DEBUG_OVERLAY] = value }
    }

    suspend fun setShowFps(value: Boolean) {
        store.edit { it[SHOW_FPS] = value }
    }

    private companion object {
        val AUTO_CLAIM = booleanPreferencesKey("points_auto_claim_v1")
        val SHOW_FPS = booleanPreferencesKey("debug_fps_v1")
        val CHAT_ANIMATED = booleanPreferencesKey("chat_animated_emotes_v1")
        val FOLLOW_RAIDS = booleanPreferencesKey("follow_raids_v1")
        val DEBUG_OVERLAY = booleanPreferencesKey("debug_overlay_v1")
        val CHAT_ENABLED = booleanPreferencesKey("chat_enabled_v1")
        /** Legacy preset index; read once to migrate to CHAT_WIDTH_PCT. */
        val CHAT_WIDTH = intPreferencesKey("chat_width_v1")
        val CHAT_WIDTH_PCT = intPreferencesKey("chat_width_pct_v2")
        val CHAT_FONT = intPreferencesKey("chat_font_v1")
        val CHAT_TIME = booleanPreferencesKey("chat_timestamps_v1")
        val CHAT_7TV = booleanPreferencesKey("chat_7tv_v1")
        val CHAT_BTTV = booleanPreferencesKey("chat_bttv_v1")
        val CHAT_FFZ = booleanPreferencesKey("chat_ffz_v1")
    }
}

fun createSettings(context: android.content.Context): SettingsStore =
    SettingsStore(context.applicationContext.vaultDataStore())
