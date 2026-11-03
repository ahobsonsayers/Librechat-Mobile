package com.garfiec.librechat.feature.auth.di

import com.garfiec.librechat.feature.auth.diagnostics.DiagnosticLogExportViewModel
import com.garfiec.librechat.feature.auth.viewmodel.ServerUrlViewModel
import com.garfiec.librechat.feature.auth.viewmodel.WebLoginViewModel
import org.koin.core.module.dsl.viewModel
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module

// No authPlatformModule: this module's only platform seam is the `WebLoginWebView` expect/actual
// composable, which the compiler links. An unimplemented expect is a compile error, so it needs no
// DI binding and no runtime check.
val authModule = module {
    // Lambda form (not viewModelOf) for the addAccount mode flag the add-account nav entry passes
    // via parametersOf — see the note below.
    viewModel { params ->
        ServerUrlViewModel(
            serverDataStore = get(),
            serverRepository = get(),
            configRepository = get(),
            accountSwitcher = get(),
            addAccount = params.getOrNull() ?: false,
        )
    }
    viewModelOf(::WebLoginViewModel)
    viewModelOf(::DiagnosticLogExportViewModel)
}
