package tv.frostline.feature.auth

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.performScrollTo
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import tv.frostline.core.twitch.DeviceCode
import tv.frostline.core.ui.FrostlineTheme

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-xhdpi")
class AuthUiTest {
    @get:Rule val rule = createComposeRule()

    /** TV buttons are activated with OK on the focused element. */
    private fun SemanticsNodeInteraction.activate() {
        requestFocus()
        rule.waitForIdle()
        performKeyInput { pressKey(Key.DirectionCenter) }
        rule.waitForIdle()
    }

    private val code = DeviceCode("dev", "WXYZ9876", "https://www.twitch.tv/activate?device-code=WXYZ9876", System.currentTimeMillis() + 600_000, 5)

    @Test fun landingOffersWebSignInGuestAndQrFallback() {
        var web = 0
        var guest = 0
        rule.setContent {
            FrostlineTheme {
                SignInLanding(SignInUi(code = code, codeStatus = CodeStatus.Ready), { web++ }, { guest++ }, {})
            }
        }
        rule.waitForIdle()
        rule.onNodeWithTag("btn_web_signin").assertIsFocused()
        rule.onNodeWithTag("qr").assertIsDisplayed()
        rule.onNodeWithTag("device_code").assertIsDisplayed()
        rule.onNodeWithTag("btn_guest").activate()
        rule.onNodeWithTag("btn_web_signin").activate()
        assertEquals(1, guest)
        assertEquals(1, web)
    }

    @Test fun codeErrorStateHidesQrButKeepsGuest() {
        rule.setContent {
            FrostlineTheme { SignInLanding(SignInUi(codeStatus = CodeStatus.Error("offline")), {}, {}, {}) }
        }
        rule.onNodeWithTag("qr").assertDoesNotExistCompat()
        rule.onNodeWithTag("btn_guest").assertIsDisplayed()
        rule.onNodeWithTag("code_status").assertIsDisplayed()
    }

    private fun androidx.compose.ui.test.SemanticsNodeInteraction.assertDoesNotExistCompat() = assertDoesNotExist()

    private fun settings(ui: AccountUi, link: () -> Unit = {}, signIn: () -> Unit = {}, raids: (Boolean) -> Unit = {}) {
        rule.setContent {
            FrostlineTheme { SettingsContent(ui, "0.2.0", link, signIn, {}, {}, raids, {}) }
        }
    }

    @Test fun settingsHasNoManualCookieFieldAndLinksViaWebView() {
        var linked = 0
        settings(AccountUi(login = "me", signedIn = true), link = { linked++ })
        rule.onNodeWithText("auth-token cookie value").assertDoesNotExist()
        rule.onNodeWithTag("btn_sign_out").assertIsDisplayed()
        rule.onNodeWithTag("btn_link_web").activate()
        assertEquals(1, linked)
    }

    @Test fun guestSeesSignInInSettings() {
        var signIn = 0
        settings(AccountUi(guest = true), signIn = { signIn++ })
        rule.onNodeWithText("Watching as guest").assertIsDisplayed()
        rule.onNodeWithTag("btn_sign_in").activate()
        assertEquals(1, signIn)
    }

    @Test fun raidToggleReportsNewValue() {
        var value: Boolean? = null
        settings(AccountUi(signedIn = true, followRaids = true), raids = { value = it })
        rule.onNodeWithTag("toggle_raids").performScrollTo().activate()
        assertEquals(false, value)
    }

    @Test fun signOutAsksForConfirmationFirst() {
        var signedOut = 0
        rule.setContent {
            FrostlineTheme { SettingsContent(AccountUi(login = "me", signedIn = true), "0.6.0", {}, {}, { signedOut++ }, {}, {}, {}) }
        }
        rule.onNodeWithTag("btn_sign_out").activate()
        assertEquals(0, signedOut)
        rule.onNodeWithTag("btn_sign_out_cancel").assertIsFocused() // the safe choice has focus
        rule.onNodeWithTag("btn_sign_out_cancel").activate()
        rule.onNodeWithTag("btn_sign_out").assertIsDisplayed()
        rule.onNodeWithTag("btn_sign_out").activate()
        rule.onNodeWithTag("btn_sign_out_confirm").activate()
        assertEquals(1, signedOut)
    }

    @Test fun aboutShowsVersionAndCommitAndCacheSizeIsShown() {
        var cleared = 0
        rule.setContent {
            FrostlineTheme {
                SettingsContent(
                    AccountUi(signedIn = true, cacheBytes = 3_145_728), "0.6.0", {}, {}, {}, {}, {}, {},
                    onClearCache = { cleared++ }, commit = "abc1234",
                )
            }
        }
        rule.onNodeWithTag("settings_list").performScrollToNode(hasTestTag("about_version"))
        rule.onNodeWithTag("about_version").assertIsDisplayed()
        rule.onNodeWithTag("about_version").assertTextEquals("Version 0.6.0 (commit abc1234)")
        rule.onNodeWithTag("settings_list").performScrollToNode(hasTestTag("choice_clear_cache"))
        rule.onNodeWithText("3.0 MB").assertExists()
        rule.onNodeWithTag("choice_clear_cache").activate()
        assertEquals(1, cleared)
    }
}
