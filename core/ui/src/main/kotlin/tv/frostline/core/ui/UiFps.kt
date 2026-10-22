package tv.frostline.core.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Handler
import android.os.Looper
import android.view.Window
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView
import kotlinx.coroutines.delay

/** Counts rendered frames and turns the count into frames per second at each sample. Pure, unit-tested. */
class FpsCounter {
    private var frames = 0

    fun onFrame() {
        frames++
    }

    /** Frames per second over the last [elapsedMs]; resets the count. */
    fun sample(elapsedMs: Long): Int {
        val fps = if (elapsedMs <= 0) 0 else ((frames * 1000L + elapsedMs / 2) / elapsedMs).toInt()
        frames = 0
        return fps
    }
}

private fun Context.findWindow(): Window? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is Activity) return c.window
        c = c.baseContext
    }
    return null
}

/**
 * The frame rate at which this window is actually being drawn (frames the UI thread and RenderThread produced),
 * refreshed once a second. Uses frame metrics rather than a Choreographer callback, so measuring does not
 * itself ask for frames: an idle screen reads close to 0 (the 1 Hz overlay refresh counts as one frame).
 * Does nothing unless [enabled].
 */
@Composable
fun rememberUiFps(enabled: Boolean): State<Int> {
    val fps = remember { mutableIntStateOf(0) }
    val view = LocalView.current
    val counter = remember(enabled, view) { FpsCounter() }
    DisposableEffect(counter, enabled) {
        val window = if (enabled) view.context.findWindow() else null
        if (window == null) {
            onDispose { }
        } else {
            val listener = Window.OnFrameMetricsAvailableListener { _, _, _ -> counter.onFrame() }
            window.addOnFrameMetricsAvailableListener(listener, Handler(Looper.getMainLooper()))
            onDispose { window.removeOnFrameMetricsAvailableListener(listener) }
        }
    }
    LaunchedEffect(counter, enabled) {
        if (!enabled) {
            fps.intValue = 0
            return@LaunchedEffect
        }
        while (true) {
            delay(1_000)
            fps.intValue = counter.sample(1_000)
        }
    }
    return fps
}
