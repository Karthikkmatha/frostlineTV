package tv.frostline.feature.player

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The one place a decoder-holding object may live. [acquire] always tears down the previous instance
 * first, so at most one exists at any time (hardware decoders are scarce on TV SoCs).
 */
class ExclusiveSlot<T : Any>(
    private val create: () -> T,
    private val destroy: (T) -> Unit,
) {
    private val _current = MutableStateFlow<T?>(null)
    val current: StateFlow<T?> = _current.asStateFlow()

    @Synchronized
    fun acquire(): T {
        _current.value?.let { old ->
            _current.value = null
            destroy(old)
        }
        return create().also { _current.value = it }
    }

    /** Releases the current instance. With [only], does nothing unless that exact instance is current. */
    @Synchronized
    fun release(only: T? = null) {
        val c = _current.value ?: return
        if (only != null && c !== only) return
        _current.value = null
        destroy(c)
    }
}

/**
 * Ties a slot to a screen: [start] (screen visible) acquires, [stop] (onStop or leaving) releases.
 * Both are idempotent.
 */
class PlayerLifecycle<T : Any>(
    private val slot: ExclusiveSlot<T>,
    private val onAcquired: (T) -> Unit,
    private val onReleasing: (T) -> Unit,
) {
    private var owned: T? = null
    val isActive: Boolean get() = owned != null

    fun start(): T {
        owned?.let { return it }
        return slot.acquire().also {
            owned = it
            onAcquired(it)
        }
    }

    fun stop() {
        val p = owned ?: return
        owned = null
        onReleasing(p)
        slot.release(p)
    }
}
