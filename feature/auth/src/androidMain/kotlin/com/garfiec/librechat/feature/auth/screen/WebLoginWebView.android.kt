package com.garfiec.librechat.feature.auth.screen

import android.annotation.SuppressLint
import android.net.Uri
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.garfiec.librechat.core.common.identity.cleanServerUrl
import com.garfiec.librechat.core.model.auth.AuthCookies
import com.garfiec.librechat.core.network.client.CookieHelper
import com.garfiec.librechat.feature.auth.oauth.SSO_ANDROID_USER_AGENT
import com.garfiec.librechat.feature.auth.oauth.extractAuthCookies
import com.garfiec.librechat.feature.auth.oauth.extractRefreshTokenFromCookies
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * `HttpOnly` is load-bearing, not decoration: the server sets every auth cookie with it, and
 * Chromium refuses to overwrite an HttpOnly cookie from a value that lacks the attribute. Without it
 * this expiry never lands and a previous identity's cookie survives into the next sign-in.
 */
private fun expiredCookie(serverUrl: String, name: String): String {
    val secure = if (serverUrl.startsWith("https://", ignoreCase = true)) "; Secure" else ""
    return "$name=; Path=/; Expires=Thu, 01 Jan 1970 00:00:00 GMT; HttpOnly$secure"
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
actual fun WebLoginWebView(
    serverUrl: String,
    onTokenCapture: (AuthCookies) -> Unit,
    onCaptureFail: () -> Unit,
    modifier: Modifier,
) {
    val scope = rememberCoroutineScope()
    val currentOnTokenCapture by rememberUpdatedState(onTokenCapture)
    val currentOnCaptureFail by rememberUpdatedState(onCaptureFail)
    val serverHost = remember(serverUrl) { Uri.parse(serverUrl).host.orEmpty() }

    AndroidView(
        modifier = modifier,
        factory = { context ->
            WebView(context).apply {
                // Without explicit MATCH_PARENT, Chromium resolves CSS `100vh` to 0 here, and any
                // login page that centres itself in a viewport-height box collapses to the top of
                // the screen, clipped.
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
                settings.userAgentString = SSO_ANDROID_USER_AGENT
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true

                // Its own latch, not the ViewModel's. stopLoading() from inside a WebViewClient
                // callback can still let a later onPageFinished fire for the stopped URL and
                // re-enter here while the first dispatch is on the stack.
                var finished = false

                fun finish(view: WebView) {
                    if (finished) return
                    finished = true
                    view.stopLoading()
                    scope.launch {
                        val cookies = CookieManager.getInstance()
                        var captured: AuthCookies? = null
                        // CookieManager can lag the Set-Cookie behind the navigation, hence the
                        // second read; see the iOS side's COOKIE_READ_ATTEMPTS for the same reason.
                        repeat(2) { attempt ->
                            if (captured == null) {
                                if (attempt > 0) delay(150L)
                                captured = extractAuthCookies(cookies.getCookie(serverUrl))
                            }
                        }
                        // The complete bundle is copied before clearing the WebView's auth cookies.
                        view.loadUrl("about:blank")
                        if (captured != null) {
                            CookieHelper.authCookieNames.forEach { name ->
                                cookies.setCookie(serverUrl, expiredCookie(serverUrl, name))
                            }
                            currentOnTokenCapture(checkNotNull(captured))
                        } else {
                            currentOnCaptureFail()
                        }
                    }
                }

                webViewClient = object : WebViewClient() {
                    // The server's login page sets the token by fetch or callback redirect, then keeps
                    // navigating inside its own SPA — there is no fixed callback URL to gate on. The
                    // cookie itself is the finish line: checked at every committed navigation, it stops
                    // the round-trip the moment it exists, and stopLoading beats the page's own
                    // refresh rotation the same way the provider flows' post-callback race is won.
                    override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
                        super.doUpdateVisitedHistory(view, url, isReload)
                        captureIfTokenPresent(view, url)
                    }

                    override fun onPageFinished(view: WebView, url: String) {
                        super.onPageFinished(view, url)
                        captureIfTokenPresent(view, url)
                    }

                    // A main-frame load that never arrives ends the round-trip just as surely as
                    // one that does, and nothing else would report it: no navigation follows, so
                    // the cookie probe never fires again. Without these the screen keeps a dead
                    // Chromium error page with no spinner and no error pane — offline, DNS/TLS
                    // failure, or a 404 (a server with no /login route at all). finish() still
                    // reads the jar first, so a failure that lands after the cookie was already
                    // set is a capture, not a loss.
                    override fun onReceivedError(
                        view: WebView,
                        request: WebResourceRequest,
                        error: WebResourceError,
                    ) {
                        super.onReceivedError(view, request, error)
                        if (request.isForMainFrame) finish(view)
                    }

                    // Restricted to the server's own host, unlike onReceivedError. A status code
                    // is flow-meaningful at an identity provider — a 401 carrying a login page is
                    // an ordinary step — so only OUR end returning one (no /login route, reverse
                    // proxy not routing) is a dead end worth reporting.
                    override fun onReceivedHttpError(
                        view: WebView,
                        request: WebResourceRequest,
                        errorResponse: WebResourceResponse,
                    ) {
                        super.onReceivedHttpError(view, request, errorResponse)
                        if (!request.isForMainFrame) return
                        if (!request.url.host.equals(serverHost, ignoreCase = true)) return
                        finish(view)
                    }

                    private fun captureIfTokenPresent(view: WebView, url: String) {
                        if (finished || url.startsWith("about:")) return
                        val cookies = CookieManager.getInstance().getCookie(serverUrl)
                        if (extractRefreshTokenFromCookies(cookies) != null) {
                            finish(view)
                        }
                    }
                }

                val cookies = CookieManager.getInstance()
                cookies.setAcceptCookie(true)
                cookies.setAcceptThirdPartyCookies(this, true)
                val startUrl = "${cleanServerUrl(serverUrl)}/login"
                // The load is issued from inside the clear's callback, because removeAllCookies is
                // asynchronous and that ordering is the only thing keeping a stale token from being
                // read back before the login page has even loaded. (The whole jar, not just the
                // server's refreshToken: a provider's session cookie is process-global and would
                // re-select whoever was last signed in there. AndroidSwitchCacheCleaner wipes it
                // whole on account switches for the same reason.)
                cookies.removeAllCookies { loadUrl(startUrl) }
            }
        },
        onRelease = { webView ->
            webView.stopLoading()
            webView.destroy()
        },
    )
}
