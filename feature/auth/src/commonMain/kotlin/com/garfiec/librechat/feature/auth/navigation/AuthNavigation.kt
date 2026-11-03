package com.garfiec.librechat.feature.auth.navigation

import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavKey
import com.garfiec.librechat.feature.auth.screen.ServerUrlScreen
import com.garfiec.librechat.feature.auth.screen.WebLoginScreen
import kotlinx.serialization.Serializable
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

@Serializable sealed interface AuthRoute : NavKey

@Serializable data object ServerUrl : AuthRoute

/**
 * Sign in inside the server's own web UI, loaded in a full-screen in-app WebView at `/login`. The
 * server's page owns every flow the app used to mirror — email/password, social providers, 2FA,
 * registration and password reset — and hands the session back the only way that is uniform across
 * servers: the `refreshToken` cookie the web app sets after a successful sign-in.
 */
@Serializable data object WebLogin : AuthRoute

/**
 * Add-account (multi-account) variants of the server-URL + web login screens: reached from the
 * account switcher while another account stays live, so their ViewModels run in add mode (pending
 * request identity — the live account's URL/tokens/config are never touched). Distinct routes rather
 * than a flag on [ServerUrl]/[WebLogin], so the NavHost can tell from the back stack alone whether
 * an add flow is still in progress and cancel the pending session once its routes are gone.
 */
@Serializable data object AddAccountServerUrl : AuthRoute

@Serializable data object AddAccountWebLogin : AuthRoute

/** True for the routes that constitute an in-progress add-account flow. */
val NavKey.isAddAccountFlowRoute: Boolean
    get() = this is AddAccountServerUrl || this is AddAccountWebLogin

fun EntryProviderScope<NavKey>.authEntries(
    onNavigate: (NavKey) -> Unit,
    onBack: () -> Unit,
    onAuthComplete: () -> Unit,
) {
    entry<ServerUrl> {
        ServerUrlScreen(
            onServerValidate = { onNavigate(WebLogin) },
        )
    }
    entry<AddAccountServerUrl> {
        // Unlike the onboarding root, this is pushed on top of the live account's stack, so it
        // gets a back affordance to abandon the add flow (popping it cancels the pending session).
        ServerUrlScreen(
            onServerValidate = { onNavigate(AddAccountWebLogin) },
            onBack = onBack,
            viewModel = koinViewModel(parameters = { parametersOf(true) }),
        )
    }
    entry<WebLogin> {
        WebLoginScreen(
            onLoginSuccess = onAuthComplete,
        )
    }
    entry<AddAccountWebLogin> {
        // Same screen + ViewModel class as WebLogin; the VM detects add mode via the pending add
        // session (set by the AddAccountServerUrl step) and the auth repository routes the sign-in
        // to the pending server. Success completes the add and lands on the new account's chat.
        WebLoginScreen(
            onLoginSuccess = onAuthComplete,
            onBack = onBack,
        )
    }
}

val authSerializersModule = SerializersModule {
    polymorphic(NavKey::class) {
        subclass(ServerUrl::class, ServerUrl.serializer())
        subclass(WebLogin::class, WebLogin.serializer())
        subclass(AddAccountServerUrl::class, AddAccountServerUrl.serializer())
        subclass(AddAccountWebLogin::class, AddAccountWebLogin.serializer())
    }
}
