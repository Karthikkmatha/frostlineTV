package tv.frostline.app

import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * `frostline://channel/<login>` opens that channel's player (testing and the soak script: `am start -d frostline://channel/xqc`).
 * Switching from a running player replaces it, so every switch releases the previous channel.
 */
object DeepLinks {
    val pending = MutableStateFlow<String?>(null)

    /** `frostline://debug/<action>`, passed on only when Settings > Debug overlay is on. */
    val debugActions = MutableSharedFlow<String>(extraBufferCapacity = 8)

    private val ACTIONS = setOf("gc", "reconnect", "refresh-token", "refresh-integrity", "chat-toggle", "open-vod", "back", "stats", "showcase")

    /** [debugOn] is read only for debug links. */
    fun handle(intent: Intent?, scope: CoroutineScope? = null, debugOn: suspend () -> Boolean = { false }) {
        val uri = intent?.data ?: return
        if (uri.scheme == "frostline" && uri.host == "debug") {
            val action = uri.lastPathSegment ?: return
            if (action !in ACTIONS) return
            scope?.launch { if (debugOn()) debugActions.emit(action) }
            return
        }
        if (uri.scheme != "frostline" || uri.host != "channel") return
        val login = uri.lastPathSegment?.lowercase()?.takeIf { it.matches(Regex("[a-z0-9_]{1,25}")) } ?: return
        pending.value = login
    }
}
