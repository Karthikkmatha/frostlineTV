package tv.frostline.baselineprofile

import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import tv.frostline.baselineprofile.Journeys.coldStartToFollowing

/** D-pad scrolling through the Top grid: frame durations and overruns (target: 0 janky frames). */
@RunWith(AndroidJUnit4::class)
class ScrollBenchmark {
    @get:Rule
    val rule = MacrobenchmarkRule()

    @Test
    fun dpadScrollTopGrid() = rule.measureRepeated(
        packageName = TARGET_PACKAGE,
        metrics = listOf(FrameTimingMetric()),
        compilationMode = CompilationMode.Partial(BaselineProfileMode.Require),
        startupMode = StartupMode.WARM,
        iterations = 5,
        setupBlock = {
            coldStartToFollowing()
            device.pressDPadLeft()
            device.pressDPadDown() // Top
            device.pressDPadCenter()
            device.wait(Until.hasObject(By.res("stream_card")), 15_000L)
            device.pressDPadRight()
        },
    ) {
        repeat(12) { device.pressDPadDown() }
        repeat(12) { device.pressDPadUp() }
    }
}
