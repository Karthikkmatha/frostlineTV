package tv.frostline.core.twitch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.UnknownHostException

class NetworkRetryTest {
    @Test fun dnsFailuresAreNetworkErrorsWithAFriendlyMessage() {
        val e = UnknownHostException("Unable to resolve host \"api.twitch.tv\"")
        assertTrue(NetworkRetry.isNetworkError(e))
        assertEquals(NetworkRetry.MESSAGE, NetworkRetry.userMessage(e, "x"))
        assertTrue(NetworkRetry.isNetworkError(RuntimeException("wrapped", e)))
    }

    @Test fun otherErrorsKeepTheirMessage() {
        assertFalse(NetworkRetry.isNetworkError(IllegalStateException("boom")))
        assertEquals("boom", NetworkRetry.userMessage(IllegalStateException("boom"), "x"))
        assertEquals("x", NetworkRetry.userMessage(IllegalStateException(), "x"))
    }

    @Test fun backoffIsTwoFiveFifteenThenThirty() {
        assertEquals(listOf(2_000L, 5_000L, 15_000L, 30_000L, 30_000L), (0..4).map(NetworkRetry::delayMs))
    }
}
