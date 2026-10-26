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
}
