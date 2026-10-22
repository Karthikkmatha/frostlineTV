package tv.frostline.feature.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CookieAndQrTest {
    @Test fun findsAuthTokenAmongOtherCookies() {
        assertEquals("abc123", parseAuthToken("unique_id=1; auth-token=abc123; twilight-user=%7B%7D"))
        assertEquals("tok", parseAuthToken("auth-token=\"tok\""))
    }

    @Test fun absentOrEmptyAuthTokenIsNull() {
        assertNull(parseAuthToken(null))
        assertNull(parseAuthToken(""))
        assertNull(parseAuthToken("unique_id=1; auth-token-extra=zzz"))
        assertNull(parseAuthToken("auth-token="))
    }

    @Test fun qrHasFinderPatternsAndIsSquare() {
        val m = qrModules("https://www.twitch.tv/activate?device-code=ABCDEFGH")
        assertTrue(m.size >= 21)
        assertTrue(m.all { it.size == m.size })
        // Top-left finder: dark outer ring corner and dark centre; white ring inside.
        assertTrue(m[0][0] && m[3][3] && m[0][6] && m[6][0])
        assertTrue(!m[1][1] && !m[1][5])
        // Top-right and bottom-left finders.
        val n = m.size
        assertTrue(m[0][n - 1] && m[n - 1][0])
    }

    @Test fun differentTextsGiveDifferentCodes() {
        val a = qrModules("https://www.twitch.tv/activate?device-code=AAAAAAAA")
        val b = qrModules("https://www.twitch.tv/activate?device-code=BBBBBBBB")
        assertTrue(a.indices.any { y -> a[y].indices.any { x -> a[y][x] != b[y][x] } })
    }
}
