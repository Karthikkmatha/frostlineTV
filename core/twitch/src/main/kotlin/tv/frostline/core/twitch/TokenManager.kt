package tv.frostline.core.twitch

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import tv.frostline.core.data.TokenSet
import tv.frostline.core.data.TokenVault
import java.io.IOException

/**
 * Hands out a valid access token, refreshing 10 minutes before expiry and after a 401.
 * When the refresh token is rejected the vault is cleared, which flips [signedIn] to false.
 */
class TokenManager(
    private val vault: TokenVault,
    private val oauth: TwitchOAuth,
    private val now: () -> Long = System::currentTimeMillis,
    private val refreshLeadMs: Long = 10 * 60_000L,
) {
    private val mutex = Mutex()

    val signedIn: Flow<Boolean> = vault.tokens.map { it != null }.distinctUntilChanged()

    suspend fun accessToken(): String? = mutex.withLock {
        val t = vault.currentTokens() ?: return@withLock null
        if (t.refreshToken != null && now() >= t.expiresAtMs - refreshLeadMs) {
            refreshLocked(t)?.accessToken ?: t.accessToken.takeIf { now() < t.expiresAtMs }
        } else {
            t.accessToken
        }
    }

    /** Call after a 401. [failedToken] is the token that was rejected; if the vault already holds a newer one, use it. */
    suspend fun refreshAfter401(failedToken: String?): String? = mutex.withLock {
        val t = vault.currentTokens() ?: return@withLock null
        if (failedToken != null && t.accessToken != failedToken) return@withLock t.accessToken
        refreshLocked(t)?.accessToken
    }

    /** Clears the tokens, the web session and guest mode. */
    suspend fun signOut() = vault.clearAll()

    /**
     * Keeps the token fresh while the app runs. It wakes at least every 5 minutes (a coroutine delay does not count the
     * time the device slept, so one long wait could end after the token expired), refreshes inside the lead window, and
     * backs off (5 s doubling to 5 min) while refreshing fails.
     */
    fun startBackgroundRefresh(scope: CoroutineScope): Job = scope.launch {
        var failures = 0
        while (true) {
            val t = vault.currentTokens()
            if (t == null) {
                failures = 0
                delay(30_000)
                continue
            }
            delay(nextWaitMs(t.expiresAtMs, now(), refreshLeadMs, failures))
            val before = vault.currentTokens()
            accessToken()
            val after = vault.currentTokens()
            failures = if (after != null && after.refreshToken != null && now() >= after.expiresAtMs - refreshLeadMs && after.accessToken == before?.accessToken) failures + 1 else 0
        }
    }

    /** Wake-up or app-resume hook: refresh now when the token is inside the lead window (or expired while asleep). */
    suspend fun checkNow() { accessToken() }

    companion object {
        const val MAX_WAIT_MS = 5 * 60_000L

        /** How long the refresh loop sleeps before looking again. */
        fun nextWaitMs(expiresAtMs: Long, nowMs: Long, leadMs: Long, failures: Int): Long =
            if (failures > 0) minOf(MAX_WAIT_MS, 5_000L shl minOf(failures - 1, 6))
            else (expiresAtMs - leadMs - nowMs).coerceIn(5_000L, MAX_WAIT_MS)
    }

    private suspend fun refreshLocked(t: TokenSet): TokenSet? {
        val refresh = t.refreshToken ?: return null
        return try {
            oauth.refresh(refresh, t).also { vault.saveTokens(it) }
        } catch (e: RefreshRejectedException) {
            vault.clearTokens()
            null
        } catch (e: IOException) {
            null
        }
    }
}
