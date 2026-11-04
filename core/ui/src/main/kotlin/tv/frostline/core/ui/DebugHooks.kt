package tv.frostline.core.ui

import java.util.concurrent.ConcurrentHashMap

/**
 * Actions the debug deep links (`frostline://debug/<action>`) can trigger inside a screen's own objects.
 * A screen registers while it is alive and unregisters when it goes; the app only runs actions when Settings > Debug overlay is on.
 */
object DebugHooks {
    private val hooks = ConcurrentHashMap<String, suspend () -> Unit>()

    fun register(name: String, action: suspend () -> Unit) { hooks[name] = action }
    fun unregister(name: String) { hooks.remove(name) }
    fun has(name: String) = hooks.containsKey(name)

    /** Runs the hook; false when there is none. Exceptions propagate to the caller, which logs ok/err. */
    suspend fun run(name: String): Boolean {
        val h = hooks[name] ?: return false
        h()
        return true
    }

    /**
     * Which channel `debug/open-vod` takes its latest broadcast from. On a player it is that player's channel; on a VOD it is
     * the live player behind it (so the VOD is replaced, never stacked); anywhere else (Settings, Home...) it is null and the
     * link does nothing, so the screens cannot pile up.
     */
    fun openVodChannel(route: String?, currentLogin: String?, previousLogin: String?): String? = when {
        route == null -> null
        route.startsWith("vod/") -> previousLogin
        route.startsWith("player/") -> currentLogin
        else -> null
    }
}
