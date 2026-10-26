package tv.frostline.core.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The cache key of live stream previews. Twitch refreshes a stream's preview image about every five minutes; the same
 * URL would be served from Coil's cache for ever, so the URLs carry `?t=<bucket>` and the bucket changes every five
 * minutes. [run] is one coroutine in the app root that sleeps until the next boundary (no polling, nothing per card).
 */
object PreviewClock {
    const val PERIOD_MS = 5 * 60 * 1000L

    fun bucket(nowMs: Long = System.currentTimeMillis()): Long = nowMs / PERIOD_MS

    private val _bucket = MutableStateFlow(bucket())
    val bucketFlow: StateFlow<Long> = _bucket

    suspend fun run() {
        while (true) {
            val now = System.currentTimeMillis()
            _bucket.value = bucket(now)
            delay(PERIOD_MS - now % PERIOD_MS + 50)
        }
    }
}

/** The current five-minute bucket as compose state; changes (and so recomposes) at most every five minutes. */
@Composable
fun rememberPreviewBucket(): State<Long> = PreviewClock.bucketFlow.collectAsState()

/** Run in the app root: keeps [PreviewClock] ticking while the UI is shown. */
@Composable
fun PreviewClockEffect() {
    LaunchedEffect(Unit) { PreviewClock.run() }
}
