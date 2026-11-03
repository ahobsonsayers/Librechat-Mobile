package com.garfiec.librechat.feature.auth.screen

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.garfiec.librechat.core.ui.components.AdaptiveButton
import com.garfiec.librechat.core.ui.components.AdaptiveCircularProgressIndicator
import com.garfiec.librechat.feature.auth.diagnostics.DiagnosticLogLink
import com.garfiec.librechat.feature.auth.resources.*
import com.garfiec.librechat.feature.auth.resources.Res
import com.garfiec.librechat.feature.auth.viewmodel.WebLoginError
import com.garfiec.librechat.feature.auth.viewmodel.WebLoginViewModel
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

/** Signs in on the server's own web UI, loaded in a WebView — see [WebLogin] route. */
@Composable
fun WebLoginScreen(
    onLoginSuccess: () -> Unit,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    viewModel: WebLoginViewModel = koinViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val currentOnLoginSuccess by rememberUpdatedState(onLoginSuccess)

    LaunchedEffect(uiState.isLoggedIn) {
        if (uiState.isLoggedIn) {
            currentOnLoginSuccess()
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        WebLoginWebView(
            serverUrl = viewModel.serverUrl,
            onTokenCapture = viewModel::onTokenCapture,
            onCaptureFail = viewModel::onCaptureFailed,
            // The server's page knows nothing of the system bars or the back button, so the view
            // itself stays clear of both.
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .then(if (onBack != null) Modifier.padding(top = BackAffordanceClearance) else Modifier),
        )

        if (uiState.isLoading) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                AdaptiveCircularProgressIndicator()
            }
        }

        uiState.error?.let { error ->
            if (!uiState.isLoading) {
                WebLoginErrorPane(error = error, onBack = onBack)
            }
        }

        BackAffordanceOverlay(onBack)
    }
}

@Composable
private fun WebLoginErrorPane(error: WebLoginError, onBack: (() -> Unit)?) {
    val fallback = stringResource(Res.string.sso_sign_in_failed)
    val message = when (error) {
        is WebLoginError.Exchange -> error.message ?: fallback
        WebLoginError.CaptureFailed -> fallback
    }

    // Opaque, because this pane is drawn OVER the WebView: Android leaves a blank white page
    // under it and iOS leaves the stopped server page. Without a surface behind it the
    // onSurface-coloured text is white-on-white in dark theme — the reason for the failure is
    // simply unreadable — and on iOS the abandoned page underneath stays interactive.
    Surface(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                )
                Spacer(modifier = Modifier.height(16.dp))
                AdaptiveButton(onClick = { onBack?.invoke() }) {
                    Text(stringResource(Res.string.back_to_sign_in))
                }
                Spacer(modifier = Modifier.height(8.dp))
                DiagnosticLogLink()
            }
        }
    }
}
