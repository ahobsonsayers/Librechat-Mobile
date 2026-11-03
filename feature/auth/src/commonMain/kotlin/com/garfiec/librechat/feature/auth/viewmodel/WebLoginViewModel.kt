package com.garfiec.librechat.feature.auth.viewmodel

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.garfiec.librechat.core.common.result.Result
import com.garfiec.librechat.core.data.datastore.ServerDataStore
import com.garfiec.librechat.core.data.repository.AccountSwitcher
import com.garfiec.librechat.core.data.repository.AuthRepository
import com.garfiec.librechat.core.model.auth.AuthCookies
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@Immutable
sealed interface WebLoginError {
    /** The sign-in completed but no `refreshToken` cookie appeared. The primary failure signal. */
    data object CaptureFailed : WebLoginError

    /** Cookie exchange failure. [message] is already screened by `Throwable.toSafeError`. */
    data class Exchange(val message: String?) : WebLoginError
}

@Immutable
data class WebLoginUiState(
    val isLoading: Boolean = false,
    val error: WebLoginError? = null,
    val isLoggedIn: Boolean = false,
)

class WebLoginViewModel(
    private val authRepository: AuthRepository,
    serverDataStore: ServerDataStore,
    accountSwitcher: AccountSwitcher,
) : ViewModel() {

    private val _uiState = MutableStateFlow(WebLoginUiState())
    val uiState: StateFlow<WebLoginUiState> = _uiState.asStateFlow()

    /** The server this screen signs into: the pending add target when set, else the live one. */
    val serverUrl: String = accountSwitcher.pendingAdd?.serverUrl ?: serverDataStore.getBaseUrl()

    private var tokenConsumed = false

    fun onTokenCapture(cookies: AuthCookies) {
        if (tokenConsumed) return
        tokenConsumed = true

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }

            when (val result = authRepository.loginWithOAuthToken(cookies)) {
                is Result.Success ->
                    _uiState.update { it.copy(isLoading = false, isLoggedIn = true) }
                is Result.Error ->
                    _uiState.update {
                        it.copy(isLoading = false, error = WebLoginError.Exchange(result.message))
                    }
                is Result.Loading -> Unit
            }
        }
    }

    fun onCaptureFailed() {
        if (tokenConsumed) return
        _uiState.update { it.copy(isLoading = false, error = WebLoginError.CaptureFailed) }
    }
}
