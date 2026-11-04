package tv.frostline.feature.chat

import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.Collections

/**
 * The engine's ticker starts in the constructor and runs on another thread: every field it reads must exist by then.
 * (It once crashed launch with a NullPointerException in sweepQueuedReleases, a queue declared after the init block.)
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EmoteEngineInitTest {
    @Test fun theTickerNeverSeesAHalfBuiltEngine() {
        val failures = Collections.synchronizedList(mutableListOf<Throwable>())
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, e -> failures += e }
        try {
            repeat(150) {
                EmoteEngine(RuntimeEnvironment.getApplication(), clockMs = { System.nanoTime() / 1_000_000 })
            }
            Thread.sleep(400)
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(previous)
        }
        assertTrue("ticker crashed: ${failures.firstOrNull()}", failures.isEmpty())
    }
}
