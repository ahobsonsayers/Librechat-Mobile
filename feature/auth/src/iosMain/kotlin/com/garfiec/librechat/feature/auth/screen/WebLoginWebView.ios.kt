package com.garfiec.librechat.feature.auth.screen

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.UIKitView
import com.garfiec.librechat.core.common.identity.cleanServerUrl
import com.garfiec.librechat.core.model.auth.AuthCookies
import com.garfiec.librechat.feature.auth.oauth.cookieAppliesTo
import com.garfiec.librechat.feature.auth.oauth.cookiePathAppliesTo
import com.garfiec.librechat.feature.auth.oauth.extractAuthCookies
import com.garfiec.librechat.feature.auth.oauth.safariApplicationName
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCSignatureOverride
import kotlinx.cinterop.cValue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import platform.Foundation.NSError
import platform.Foundation.NSHTTPCookie
import platform.Foundation.NSURL
import platform.Foundation.NSURLRequest
import platform.Foundation.timeIntervalSinceNow
import platform.UIKit.UIDevice
import platform.WebKit.WKNavigation
import platform.WebKit.WKNavigationDelegateProtocol
import platform.WebKit.WKWebView
import platform.WebKit.WKWebViewConfiguration
import platform.WebKit.WKWebsiteDataStore
import platform.darwin.NSObject

/**
 * WKHTTPCookieStore reads cross-process and can lag a Set-Cookie the navigation has already
 * committed; the poll reads twice to absorb that.
 */
private const val COOKIE_READ_ATTEMPTS = 2
private const val COOKIE_RETRY_DELAY_MS = 150L
private const val COOKIE_READ_TIMEOUT_MS = 2_000L

/**
 * `NSURLErrorCancelled`. WebKit reports it whenever a provisional navigation is superseded — a
 * redirect starting the next one, or this delegate's own `stopLoading()` — which is routine during
 * a sign-in rather than a failure. Reporting it would abort sign-ins that are going fine.
 */
private const val NSURL_ERROR_CANCELLED = -999L

private class WebLoginCallbacks(
    val onTokenCapture: (AuthCookies) -> Unit,
    val onCaptureFail: () -> Unit,
)

/**
 * Held by [remember] for the composition's lifetime. `navigationDelegate` is a weak Obj-C property
 * and Kotlin/Native will not retain the object for us, so an anonymous delegate assigned inside
 * `factory` can be collected mid-flow and the sign-in simply stops responding.
 *
 * It reads the callbacks off a [State] rather than capturing them, because these methods run on
 * WebKit's callback and not in composition — captured lambdas would be frozen at first composition.
 */
@OptIn(ExperimentalForeignApi::class)
private class WebLoginNavigationDelegate(
    serverUrl: String,
    private val scope: CoroutineScope,
    private val callbacks: State<WebLoginCallbacks>,
) : NSObject(), WKNavigationDelegateProtocol {

    private val serverHost: String = NSURL.URLWithString(serverUrl)?.host.orEmpty()
    private val cookiePath = NSURL.URLWithString("${cleanServerUrl(serverUrl)}/api/auth/refresh")?.path ?: "/"
    private val secureServer = serverUrl.startsWith("https://", ignoreCase = true)
    private var finished = false

    @ObjCSignatureOverride
    override fun webView(webView: WKWebView, didFinishNavigation: WKNavigation?) {
        captureIfTokenPresent(webView)
    }

    @ObjCSignatureOverride
    override fun webView(
        webView: WKWebView,
        didReceiveServerRedirectForProvisionalNavigation: WKNavigation?,
    ) {
        captureIfTokenPresent(webView)
    }

    // A main-frame load that never arrives ends the sign-in too, and nothing else would report it:
    // no navigation follows, so the cookie probe fires again never, and the screen keeps a dead
    // WebKit error page with no spinner and no error pane. finish() still reads the jar first, so
    // a failure landing after the server set the cookie is a capture.
    @ObjCSignatureOverride
    override fun webView(
        webView: WKWebView,
        didFailProvisionalNavigation: WKNavigation?,
        withError: NSError,
    ) {
        failLoad(webView, withError)
    }

    @ObjCSignatureOverride
    override fun webView(webView: WKWebView, didFailNavigation: WKNavigation?, withError: NSError) {
        failLoad(webView, withError)
    }

    private fun failLoad(webView: WKWebView, error: NSError) {
        if (error.code == NSURL_ERROR_CANCELLED) return
        finish(webView)
    }

    /**
     * The server's login page sets the cookie by fetch or callback redirect, then keeps navigating
     * inside its own SPA — there is no fixed callback URL to gate on. The cookie itself is the
     * finish line: checked at every committed navigation and redirect, it ends the round-trip the
     * moment it exists.
     */
    private fun captureIfTokenPresent(webView: WKWebView) {
        if (finished) return
        scope.launch {
            val token = readAuthCookies(webView, attempts = COOKIE_READ_ATTEMPTS) ?: return@launch
            if (finished) return@launch
            finished = true
            webView.stopLoading()
            callbacks.value.onTokenCapture(token)
        }
    }

    private fun finish(webView: WKWebView) {
        if (finished) return
        finished = true
        webView.stopLoading()
        // A load that never arrived usually carries no capture: read the jar once more anyway so
        // a failure landing after the server already set the cookie is a capture, not a loss,
        // then report the dead end so the screen shows its error pane instead of sitting on a
        // blank WebView forever.
        scope.launch {
            val token = readAuthCookies(webView, attempts = COOKIE_READ_ATTEMPTS)
            when {
                token != null -> callbacks.value.onTokenCapture(token)
                else -> callbacks.value.onCaptureFail()
            }
        }
    }

    private suspend fun readAuthCookies(webView: WKWebView, attempts: Int): AuthCookies? =
        withContext(Dispatchers.Main) {
            repeat(attempts) { attempt ->
                if (attempt > 0) delay(COOKIE_RETRY_DELAY_MS)
                val cookies = CompletableDeferred<List<*>?>()
                webView.configuration.websiteDataStore.httpCookieStore
                    .getAllCookies { cookies.complete(it) }
                val fetched = withTimeoutOrNull(COOKIE_READ_TIMEOUT_MS) { cookies.await() }
                val captured = authCookiesFrom(fetched)
                if (captured != null) return@withContext captured
            }
            null
        }

    private fun authCookiesFrom(cookies: List<*>?): AuthCookies? {
        val selected = cookies.orEmpty().filterIsInstance<NSHTTPCookie>()
            .filter {
                cookieAppliesTo(it.domain, serverHost) && cookiePathAppliesTo(it.path, cookiePath) &&
                    (!it.secure || secureServer) && (it.expiresDate?.timeIntervalSinceNow?.let { age -> age > 0 } != false)
            }
            .sortedByDescending { it.path.length }
            .distinctBy { it.name }
        return extractAuthCookies(selected.joinToString("; ") { "${it.name}=${it.value}" })
    }
}

@OptIn(ExperimentalForeignApi::class)
@Composable
actual fun WebLoginWebView(
    serverUrl: String,
    onTokenCapture: (AuthCookies) -> Unit,
    onCaptureFail: () -> Unit,
    modifier: Modifier,
) {
    val callbacks = rememberUpdatedState(
        WebLoginCallbacks(onTokenCapture, onCaptureFail),
    )
    val scope = rememberCoroutineScope()
    val delegate = remember(serverUrl) {
        WebLoginNavigationDelegate(serverUrl, scope, callbacks)
    }

    UIKitView(
        modifier = modifier,
        factory = {
            // A non-persistent store is created fresh here and dies with the view, so there is no
            // stale cookie to clear and no provider session to re-select. Android has to ask for
            // the same guarantee with removeAllCookies; here it is structural.
            val config = WKWebViewConfiguration().apply {
                websiteDataStore = WKWebsiteDataStore.nonPersistentDataStore()
                defaultWebpagePreferences.allowsContentJavaScript = true
                // Appended to WebKit's own prefix rather than replacing the agent, so the device,
                // idiom and OS it reports stay the real ones. See [safariApplicationName].
                applicationNameForUserAgent =
                    safariApplicationName(UIDevice.currentDevice.systemVersion)
            }
            val webView = WKWebView(frame = cValue { }, configuration = config)
            webView.navigationDelegate = delegate
            val startUrl = "${cleanServerUrl(serverUrl)}/login"
            val url = NSURL.URLWithString(startUrl)
            if (url != null) {
                webView.loadRequest(NSURLRequest(uRL = url))
            } else {
                // Nothing loads, so no delegate callback can ever fire. Report the dead end here
                // or the screen sits blank forever.
                scope.launch { callbacks.value.onCaptureFail() }
            }
            webView
        },
        onRelease = { webView ->
            webView.navigationDelegate = null
            webView.stopLoading()
        },
    )
}
