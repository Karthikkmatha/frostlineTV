package tv.frostline.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import tv.frostline.baselineprofile.Journeys.coldStartToFollowing
import tv.frostline.baselineprofile.Journeys.dpadFollowingAndTop
import tv.frostline.baselineprofile.Journeys.openTopStream
import tv.frostline.baselineprofile.Journeys.openVodFromChannel

/**
 * Records the classes and methods the four journeys touch. Run (device in ANDROID_SERIAL):
 * `gradlew :app:generateReleaseBaselineProfile`. The result lands in app/src/release/generated/baselineProfiles/.
 * Nothing here may clear or uninstall the app: that wipes the user's sign-in. gradle.properties sets
 * `android.injected.androidTest.leaveApksInstalledAfterRun=true` so the test run leaves both apks installed.
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {
    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    fun coldStart() = rule.collect(TARGET_PACKAGE, includeInStartupProfile = true, maxIterations = 4, stableIterations = 2) {
        coldStartToFollowing()
    }

    @Test
    fun dpadFollowingAndTop() = rule.collect(TARGET_PACKAGE, maxIterations = 3, stableIterations = 2) {
        dpadFollowingAndTop()
    }

    @Test
    fun topStreamWithChat() = rule.collect(TARGET_PACKAGE, maxIterations = 2, stableIterations = 1) {
        openTopStream(watchMs = 40_000L)
    }

    @Test
    fun vodFromChannel() = rule.collect(TARGET_PACKAGE, maxIterations = 2, stableIterations = 1) {
        openVodFromChannel()
    }
}
