package tv.frostline.app

import android.content.Intent
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * `frostline://channel/<login>` opens that channel's player (testing and the soak script: `am start -d frostline://channel/xqc`).
 * Switching from a running player replaces it, so every switch releases the previous channel.
 */
object DeepLinks {
    val pending = MutableStateFlow<String?>(null)

    fun handle(intent: Intent?) {
        val uri = intent?.data ?: return
        if (uri.scheme != "frostline" || uri.host != "channel") return
        val login = uri.lastPathSegment?.lowercase()?.takeIf { it.matches(Regex("[a-z0-9_]{1,25}")) } ?: return
        pending.value = login
    }
}
