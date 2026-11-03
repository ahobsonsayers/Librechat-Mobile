package com.garfiec.librechat.core.data.repository

import co.touchlab.kermit.Logger
import com.garfiec.librechat.core.common.identity.AccountId
import com.garfiec.librechat.core.common.identity.AccountState
import com.garfiec.librechat.core.common.identity.ActiveAccountProvider
import com.garfiec.librechat.core.common.identity.SessionManager
import com.garfiec.librechat.core.common.identity.currentAccountId
import com.garfiec.librechat.core.common.result.Result
import com.garfiec.librechat.core.common.result.apiCallCatching
import com.garfiec.librechat.core.common.result.safeApiCall
import com.garfiec.librechat.core.data.datastore.AccountRegistry
import com.garfiec.librechat.core.data.util.SessionTaskRunner
import com.garfiec.librechat.core.model.User
import com.garfiec.librechat.core.model.auth.AuthCookies
import com.garfiec.librechat.core.model.auth.SessionCredentials
import com.garfiec.librechat.core.model.response.BackupCodesResponse
import com.garfiec.librechat.core.model.response.TwoFactorSetupResponse
import com.garfiec.librechat.core.network.api.AuthApi
import com.garfiec.librechat.core.network.api.UserApi
import com.garfiec.librechat.core.network.client.PendingRequestIdentity
import com.garfiec.librechat.core.network.client.RequestIdentity
import com.garfiec.librechat.core.network.client.SwitchGate
import com.garfiec.librechat.core.network.client.TokenManager
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

@Suppress("LongParameterList") // debt: constructor dependencies
class AuthRepositoryImpl(
    private val authApi: AuthApi,
    private val userApi: UserApi,
    private val tokenManager: TokenManager,
    private val sessionCacheCleaner: SessionCacheCleaner,
    private val sessionTaskRunner: SessionTaskRunner,
    private val accountSessionEstablisher: AccountSessionEstablisher,
    private val accountRegistry: AccountRegistry,
    private val activeAccountProvider: ActiveAccountProvider,
    private val sessionManager: SessionManager,
    private val accountSwitcher: AccountSwitcher,
    private val switchGate: SwitchGate,
    private val serverSessionRevoker: ServerSessionRevoker,
) : AuthRepository {

    /**
     * Fires session tasks when [this] represents an authenticated success. Callers pass
     * [isAuthenticated] to discriminate sign-ins that carry a completed session from pending
     * states that defer to a later call.
     */
    private fun <T> Result<T>.fireSessionTasksIfLoggedIn(previousAccountId: AccountId?): Result<T> =
        also {
            if (it is Result.Success) {
                // The NavHost's accountTransitions (Switched) collector already runs the session tasks
                // for any account→different-account flip — an add-completion (A→B) AND a soft-expiry
                // re-auth as a different account (A→B). Firing here too would double every post-login
                // fetch and race two writers onto the same acct: keys. Fire here only when this sign-in
                // was NOT such a transition and the collector therefore won't cover it: a fresh login
                // from logged-out (null→B) or a re-auth as the same account (A→A). [previousAccountId]
                // is captured before establishSession; the current id reflects the post-establish flip.
                // The one window where a transition could slip past the collector — the NavHost VM being
                // re-created (process death) exactly across the flip — is backstopped by the cold-start
                // path (NavHostViewModel.init calls sessionTaskRunner.runAll for the resolved account),
                // so no resolved login is ever left without its session tasks.
                val current = activeAccountProvider.currentAccountId()
                val handledByCollector =
                    previousAccountId != null && current != null && previousAccountId != current
                if (!handledByCollector) sessionTaskRunner.runAll()
            }
        }

    /**
     * Runs [block] under the pending add-account identity when an add flow is in progress, so its
     * HTTP calls target the server being added (with the staged bearer) instead of the active
     * account's server. Passes [pending] explicitly so one capture drives both the request routing
     * and the completion dispatch — the two can't disagree mid-call.
     */
    private suspend fun <T> withAuthIdentity(pending: PendingAddSession?, block: suspend () -> T): T =
        pending.withRequestIdentity(block)

    /**
     * Turns the authenticated [user] into the active account: through the add-completion (atomic
     * URL+token+roster+identity flip to the new account) when [pending] is an add flow, else through
     * the normal establish path.
     */
    private suspend fun establishSession(pending: PendingAddSession?, user: User) {
        if (pending != null) accountSwitcher.completeAdd(user) else accountSessionEstablisher.establish(user)
    }

    /**
     * Fetches the just-authenticated user's profile carrying the *staged* bearer. After
     * [TokenManager.setTokens] the fresh pair sits under the bare staging keys with no active
     * binding — but a re-login after a hard session expiry still has the previous account
     * Resolved, so the switch barrier would key this request's bearer to that account's (now
     * empty) slot and deliberately never fall back to staging ([SwitchGate.captureSnapshot]),
     * turning every OAuth re-login into a 401 loop. Route the fetch under an explicit
     * staged-bearer identity instead. In add mode the surrounding pending identity already
     * carries the staged bearer.
     */
    private suspend fun fetchAuthenticatedUser(pending: PendingAddSession?): User {
        if (pending != null) return userApi.getUser()
        val snapshot = switchGate.captureSnapshot()
        return withContext(
            PendingRequestIdentity(
                baseUrl = snapshot.baseUrl,
                // Carried over from the snapshot rather than re-read: this overrides the identity for
                // a request to the *same* server, so dropping the gateway headers here would make the
                // post-login profile fetch the one call that can't get through the gateway.
                headers = { snapshot.customHeaders },
                auth = { tokenManager.getStagedRequestAuth() },
            ),
        ) { userApi.getUser() }
    }

    override suspend fun loginWithOAuthToken(cookies: AuthCookies): Result<User> {
        val pending = accountSwitcher.pendingAdd
        val previousAccountId = activeAccountProvider.currentAccountId()
        return safeApiCall {
            withAuthIdentity(pending) {
                val result = authApi.refresh(cookies)
                tokenManager.setSession(SessionCredentials(result.response.token, result.cookies))
                // Fetch the user profile, then establish identity before session tasks fire.
                val user = fetchAuthenticatedUser(pending)
                establishSession(pending, user)
                user
            }
        }.fireSessionTasksIfLoggedIn(previousAccountId)
    }

    override suspend fun logout(): Result<Unit> {
        return safeApiCall {
            // Let the cold-start seed resolve identity first: a logout during the warming window
            // would otherwise read a null account and skip the scoped Room purge below (the leak).
            accountRegistry.awaitReady()
            // One atomic snapshot drives both the server-side revocation and the local teardown
            // target: the POST otherwise captures its identity at request-build time, so a switch
            // landing between entry and the request would revoke the server session of the account
            // the user just switched TO (and never revoke this one). Revocation is best-effort; the
            // teardown below is what must not miss.
            signOut(switchGate.captureSnapshot())
        }
    }

    private suspend fun signOut(snapshot: RequestIdentity) {
        val account = snapshot.accountId?.let { AccountId(it) }
        try {
            serverSessionRevoker.revoke(snapshot)
        } finally {
            // Teardown must finish even if this coroutine is cancelled mid-way (e.g. the Activity
            // finishes during the WebView wipe). Without NonCancellable a cancellation could abort
            // after the identity flip but before the cookie/WebView wipe, leaving the server's OAuth
            // refreshToken cookie alive for the next resume's checkOAuthResult() to re-consume —
            // silently signing the just-logged-out user back in. The switch/remove paths already
            // run their teardown under NonCancellable; logout must too.
            withContext(NonCancellable) {
                // End the account session scope only if the captured account is still the live one.
                // A switch/add landing during the slow revocation POST flips the live identity;
                // remove() below then reaps only the captured account without touching the
                // switched-to session, so we must not end that session. NOTE: tenant writes are not
                // yet bound to Session.scope (the structural SessionWriter is deferred), so this does
                // not by itself fence a debounced/in-flight write against the purge; it is mitigated
                // per-path (draft debounce re-checks identity; cache writes capture+guard the account
                // at request time).
                if (account == null || activeAccountProvider.currentAccountId() == account) {
                    sessionManager.endCurrentSession()
                }
                if (account != null) {
                    // One teardown owner: remove() promotes the most-recently-used survivor (the user
                    // stays signed in) or, when this was the last account, tears down to logged-out
                    // and emits session-expired to route to auth — the same path the account-switcher's
                    // remove-account uses. It serializes the identity flip under the SwitchGate itself
                    // and runs the slow cache/WebView wipes after the gate reopens. The switched-away
                    // case (a switch landed mid-POST, so the captured account is no longer active) is
                    // handled inside remove(): it reaps just that account without touching the live one.
                    accountSwitcher.remove(account.value)
                } else {
                    // Unresolved identity (should not happen after awaitReady) — no account to reap;
                    // clear the account-blind file caches so nothing is left behind.
                    sessionCacheCleaner.clearFileCaches()
                }
            }
        }
    }

    override suspend fun removeAccount(accountId: String) {
        accountRegistry.awaitReady()
        // Decide from the snapshot the sign-out acts on: a live-identity check followed by logout()
        // would sign out whichever account a switch landing in between made active.
        val snapshot = switchGate.captureSnapshot()
        if (snapshot.accountId == accountId) {
            safeApiCall { signOut(snapshot) }
            return
        }
        try {
            apiCallCatching({ serverSessionRevoker.revokeStored(accountId) }) {
                Logger.w(it) { "Server-side revocation failed for a removed account" }
            }
        } finally {
            withContext(NonCancellable) { accountSwitcher.remove(accountId) }
        }
    }

    override suspend fun isLoggedIn(): Boolean {
        return tokenManager.getAccessToken() != null
    }

    override suspend fun restoreAccountIfNeeded(): Boolean {
        if (!isLoggedIn()) return true
        // Let the registry's cold-start seed finish, then act only if it found no persisted account.
        accountRegistry.awaitReady()
        val state = activeAccountProvider.state.value
        if (state is AccountState.Resolved && state.id != null) return true
        // Logged in but unaccounted → fresh upgrade. Derive identity from the current user. Wrap the
        // live getUser() so a transient/offline failure returns false (retry-worthy) instead of throwing
        // and leaving the provider stuck at Resolved(null) — which would render every tenant read empty
        // for an otherwise logged-in user until a manual relaunch while online.
        val result = safeApiCall {
            val user = userApi.getUser()
            accountSessionEstablisher.establish(user)
        }
        return result is Result.Success
    }

    override suspend fun enableTwoFactor(token: String?, backupCode: String?): Result<TwoFactorSetupResponse> {
        return safeApiCall {
            authApi.enableTwoFactor(token = token, backupCode = backupCode)
        }
    }

    override suspend fun confirmTwoFactor(code: String): Result<Unit> {
        return safeApiCall {
            authApi.confirmTwoFactor(code)
        }
    }

    override suspend fun disableTwoFactor(token: String?, backupCode: String?): Result<Unit> {
        return safeApiCall {
            authApi.disableTwoFactor(token = token, backupCode = backupCode)
        }
    }

    override suspend fun regenerateBackupCodes(token: String?, backupCode: String?): Result<BackupCodesResponse> {
        return safeApiCall {
            authApi.regenerateBackupCodes(token = token, backupCode = backupCode)
        }
    }
}
