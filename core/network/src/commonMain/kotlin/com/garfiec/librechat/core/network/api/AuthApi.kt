package com.garfiec.librechat.core.network.api

import com.garfiec.librechat.core.model.auth.AuthCookies
import com.garfiec.librechat.core.model.request.OtpVerificationRequest
import com.garfiec.librechat.core.model.request.TwoFactorDisableRequest
import com.garfiec.librechat.core.model.response.BackupCodesResponse
import com.garfiec.librechat.core.model.response.RefreshResponse
import com.garfiec.librechat.core.model.response.TwoFactorSetupResponse
import com.garfiec.librechat.core.network.api.dto.RefreshResult
import com.garfiec.librechat.core.network.api.dto.TwoFactorConfirmRequest
import com.garfiec.librechat.core.network.client.CookieHelper
import com.garfiec.librechat.core.network.client.attachAuthCookies
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.path

/**
 * The endpoints the app itself drives. Sign-in is NOT here any more: it happens on the server's own
 * web page (see WebLoginWebView) and enters this layer through [AuthRepository.loginWithOAuthToken] →
 * [refresh], which is also how the captured session becomes an access token. 2FA *settings* (enable,
 * confirm, disable, backup codes) stay here because the account settings feature calls them.
 */
class AuthApi constructor(
    private val client: HttpClient,
) {
    /**
     * The server deletes the session [refreshToken] names. It reads the token from the cookie only, and
     * without one it deletes whichever of the user's sessions it finds first — possibly another device's.
     */
    suspend fun logout(refreshToken: String) = logout(AuthCookies(refreshToken))

    suspend fun logout(cookies: AuthCookies) {
        client.post {
            url { path("api/auth/logout") }
            attachAuthCookies(cookies)
        }
    }

    suspend fun refresh(refreshToken: String): RefreshResult = refresh(AuthCookies(refreshToken))

    suspend fun refresh(cookies: AuthCookies): RefreshResult {
        val httpResponse = client.post {
            url { path("api/auth/refresh") }
            // The backend reads the cookie only; the body copy is redundant.
            attachAuthCookies(cookies)
            setBody(mapOf("refreshToken" to cookies.refreshToken))
        }
        val body = httpResponse.body<RefreshResponse>()
        return RefreshResult(body, CookieHelper.update(cookies, httpResponse.headers))
    }

    suspend fun enableTwoFactor(token: String? = null, backupCode: String? = null): TwoFactorSetupResponse =
        client.post {
            url { path("api/auth/2fa/enable") }
            if (token != null || backupCode != null) {
                setBody(OtpVerificationRequest(token = token, backupCode = backupCode))
            }
        }.body()

    // confirm2FA returns an empty 200 body (`res.status(200).json()`); there is nothing to decode.
    suspend fun confirmTwoFactor(code: String) {
        client.post {
            url { path("api/auth/2fa/confirm") }
            setBody(TwoFactorConfirmRequest(token = code))
        }
    }

    suspend fun regenerateBackupCodes(token: String? = null, backupCode: String? = null): BackupCodesResponse =
        client.post {
            url { path("api/auth/2fa/backup/regenerate") }
            if (token != null || backupCode != null) {
                setBody(OtpVerificationRequest(token = token, backupCode = backupCode))
            }
        }.body()

    /** The server verifies whichever of [token] and [backupCode] is sent, so either one disables 2FA. */
    suspend fun disableTwoFactor(token: String? = null, backupCode: String? = null) {
        client.post {
            url { path("api/auth/2fa/disable") }
            setBody(TwoFactorDisableRequest(token = token, backupCode = backupCode))
        }
    }
}
