package com.garfiec.librechat.feature.auth.screen

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.garfiec.librechat.core.model.auth.AuthCookies

/**
 * Embedded WebView that signs in on the server's own web UI.
 *
 * Loads `{serverUrl}/login`, so every sign-in method the server offers — email/password, social
 * providers, 2FA, registration, password reset — happens on the server's page exactly as it works
 * in a browser, instead of the app re-creating any of it. The User-Agent is a browser string
 * because the server's ua-parser middleware rejects WebView agents, and because providers block
 * embedded-browser sign-ins.
 *
 * On a successful sign-in, LibreChat's web app sets the `refreshToken` cookie on the server's own
 * origin. The view watches for that cookie (whatever navigation produced it — a page load, an SPA
 * route change or an OAuth callback redirect) and reports it via [onTokenCapture]. A failed OAuth
 * round-trip or a wrong password never navigates away, so the user simply keeps the server's page
 * and its own error display.
 *
 * This view owns the jar's whole lifecycle, because the clear has to be ordered against the load
 * and only the side that owns both can guarantee that. Every sign-in starts from an empty jar, not
 * merely one without the server's `refreshToken`: a stale cookie would complete the sign-in the
 * moment the login page loads, and the provider's own session cookie is what makes an OAuth
 * round-trip re-select an identity the user never chose. iOS gets this by construction from its
 * non-persistent store; Android has to ask for it.
 *
 * Exactly one of [onTokenCapture] or [onCaptureFail] fires. [onCaptureFail] is the primary failure
 * signal — no cookie, no session.
 */
@Composable
expect fun WebLoginWebView(
    serverUrl: String,
    onTokenCapture: (AuthCookies) -> Unit,
    onCaptureFail: () -> Unit,
    modifier: Modifier = Modifier,
)
