package tv.frostline.feature.auth

import android.webkit.CookieManager
import android.webkit.WebStorage

/** Extracts the `auth-token` value from a `Cookie:` style header ("a=b; auth-token=XYZ; c=d"). */
fun parseAuthToken(cookieHeader: String?): String? {
    if (cookieHeader.isNullOrBlank()) return null
    return cookieHeader.split(';')
        .map { it.trim() }
        .firstOrNull { it.startsWith("auth-token=") }
        ?.substringAfter('=')
        ?.trim('"')
        ?.takeIf { it.isNotEmpty() }
}

class AndroidWebCookies : WebCookies {
    override fun authToken(): String? {
        val cm = CookieManager.getInstance()
        return parseAuthToken(cm.getCookie("https://www.twitch.tv")) ?: parseAuthToken(cm.getCookie("https://twitch.tv"))
    }

    override fun clearAll() {
        val cm = CookieManager.getInstance()
        cm.removeAllCookies(null)
        cm.flush()
        WebStorage.getInstance().deleteAllData()
    }
}
